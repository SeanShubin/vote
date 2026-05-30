# Event Log Protection Strategy

The event log (`vote_event_log`) is the system's single source of truth. Every
piece of application state is derived from it by replay; the projection
(`vote_data`) is a disposable cache. Because the log is irreplaceable, its
integrity is defended in layers — each layer assumes the one above it might
fail. This document is the canonical description of those layers.

The driving principle: **append-only is enforced, never assumed.** Anything that
relies on convention ("we only ever append") eventually breaks; every guarantee
here is backed by a mechanism that makes the bad state impossible or loud.

## The model

- `vote_event_log` — the append-only log. Single partition (`PK = "EVENT_LOG"`),
  `event_id` (Number) as the sort key. **Source of truth. Irreplaceable.**
- `vote_data` — the projection/read model. Fully rebuildable from the log.
  **Disposable.** `rebuild-projection` drops and replays it.
- `vote_operator_state` — pause flag + feature flags. Has *no* event-log source
  of truth, so it is **not rebuildable** and is protected like the log.

All three are app-managed (created by `DynamoDbSingleTableSchema` /
`DynamoDbOperatorStateSchema`), intentionally **not** CloudFormation resources,
so a stack update or projection rebuild can never clobber the log or operator
state. See `deploy/template.yaml` (the "intentionally NOT a CloudFormation
resource" note).

## Layer 1 — Append-only is enforced at the storage layer

Two independent mechanisms in `DynamoDbEventLog.appendEvent`:

1. **The log allocates its own ids.** The next id is derived from the log
   itself: `max(event_id) + 1`, read with a single descending sort-key query
   (`scanIndexForward = false, limit = 1`) — one item, O(1), not a scan or a
   count. There is **no separate counter**, so there is no id-allocation state
   that can desync from the log. (This mirrors how the MySQL backend has always
   worked, via the table's `AUTO_INCREMENT`.)

   > Why `max`, not `count`: a count breaks the moment any event is deleted
   > (`count < maxId`, so `count + 1` collides with a live id). The high-water
   > mark is monotonic even across gaps.

2. **The write refuses to overwrite.** `putItem` carries
   `conditionExpression = "attribute_not_exists(event_id)"`. A `PutItem` is an
   upsert; without this condition a reused id would silently clobber an existing
   event. With it, a collision *fails the write* instead.

These compose into the concurrency primitive: two appenders can read the same
`max` and target the same id; the conditional write lets exactly one win, and
the loser re-reads `max` and retries (bounded, `MAX_APPEND_ATTEMPTS`). A lost
race retries — it never overwrites and never burns an id, so the log stays a
gap-free `1..N`.

Covered by `DynamoDbEventLogAppendOnlyTest` (integration, real DynamoDB):
sequential appends yield a contiguous `1..N`; 16 concurrent appends all survive
with distinct ids.

## Layer 2 — The projection is disposable; its cursor self-heals

The only projection-resident pointer is the sync cursor `last_synced`
(`PK=METADATA, SK=SYNC`). `synchronize()` applies events with
`event_id > last_synced` and advances it.

When `rebuild-projection` drops `vote_data`, the cursor is wiped to 0. That is
**self-healing**: `eventsToSync(0)` returns the entire log and the projection
replays from scratch. A wiped cursor only ever causes *more replay*, never data
loss.

This is the whole reason id allocation must **not** live in the projection. In
May 2026 it did (an `EVENT_COUNTER` item in `vote_data`); a rebuild wiped it, the
counter restarted at 1, and new events overwrote the five oldest ones — silent
data loss with no error. The fix was Layer 1: move id allocation back into the
log so the disposable table holds nothing that can't self-heal.

## Layer 3 — Fail-closed startup guards

`DynamoDbStartup` runs at Lambda cold-init and refuses traffic (returns 500
until reconciled) rather than serve a corrupt state:

- **Shape mismatch** → `ProjectionShapeMismatchException`. The live `vote_data`
  shape differs from what the code expects (missing GSI/attr). Fix:
  `rebuild-projection`.
- **Cursor ahead of log** (`last_synced > max(event_id)`) →
  `CursorAheadOfLogException`. The projection references events that no longer
  exist — a tail event was removed behind the cursor (e.g. an operator
  `delete-event` without a rewind). The projection may *lag* the log (self-heals
  by replay) but must never *lead* it.

Also fail-closed: `eventsToSync` throws on an event whose `event_data` no longer
deserializes (unknown type — a schema/rollback mismatch) rather than silently
dropping it.

## Layer 4 — Physical durability (catastrophe protection)

For the two non-rebuildable tables (`vote_event_log`, `vote_operator_state`):

- **Deletion protection** (`DeletionProtectionEnabled`) — blocks an accidental
  `delete-table` (script slip, console misclick, bad CFN edit) from destroying
  the log in one call. Set as a `CreateTable` flag so fresh environments get it,
  and enabled on the live tables.
- **Point-In-Time Recovery (PITR)** — DynamoDB's continuous, ~35-day backup.
  Restores the table to *any second* in the window into a *new* table (never
  in place). This is the last-resort recovery; enabling it is best-effort in
  `DynamoDbSingleTableSchema.enablePointInTimeRecovery` (waits for `ACTIVE`
  first, swallows LocalStack's lack of support). Requires
  `dynamodb:UpdateContinuousBackups` in the role.

`vote_data` is deliberately **excluded** from both — it must stay droppable for
`rebuild-projection`, and it is rebuildable so it needs no backup.

## Recovery sources (best first)

1. **PITR** on `vote_event_log` — continuous, point-in-time, ~35-day window.
   `restore-table-to-point-in-time` into a temp table, copy the needed rows
   back, delete the temp table. The May 2026 originals were recovered this way.
2. **`.local/prod-snapshots/prod-snapshot.jsonl`** — narrative JSONL written
   (overwritten, rolling) by `dev.ps1 launch-from-snapshot --prod`. In
   `restore-dynamodb` format, but only as fresh as the last run.
3. **`.local/event-log-backup.jsonl`** — older static snapshot, last resort.

`restore-dynamodb` consumes the narrative JSONL (line position becomes
`event_id`) and **refuses if the log is non-empty**, so it can't overwrite a
live log.

## Operator surgery — the deliberate exceptions

Append-only is a *runtime* invariant, not an operator one. These CLI tools
(not wired into the Lambda) can mutate the log on purpose, each gated by a typed
`--prod` confirmation:

- `delete-event` — removes rows by id. Source-of-truth surgery. May leave the
  cursor ahead of the log → Layer 3's guard fires until a rebuild.
- `nuke-dynamodb` — wipes all items (tables themselves survive; deletion
  protection now blocks dropping the tables).
- `restore-dynamodb` — repopulates an empty log.

## Open / future hardening

- **Monitoring & alerting.** Every failure above is now *loud* (throws), but
  nothing pages a human yet. Alert on the startup guards
  (`ProjectionShapeMismatchException`, `CursorAheadOfLogException`), on
  `appendEvent` retry-exhaustion, and on backend 500 spikes — so an anomaly is
  noticed in minutes, not days. (The May 2026 incident stayed silent partly
  because nothing watched.)
- **Regression tests for the guards** — e.g. cursor-ahead-of-log fails closed;
  `eventsToSync` rejects an unknown event type.
- **Verify PITR/deletion-protection stay on** — a periodic check, since both are
  best-effort/out-of-band and could lapse if a table is ever recreated by a path
  that bypasses the create helpers.

## Related

- `docs/dynamodb-single-table-design.md` — the projection's table design.
- `docs/dynamodb-design-summary.md` — three-backend (InMemory / MySQL /
  DynamoDB) architecture.
- Code: `DynamoDbEventLog`, `DynamoDbStartup`, `EventApplier`,
  `RebuildProjection`, `RestoreDynamodb`.
