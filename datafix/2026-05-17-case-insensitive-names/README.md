# Datafix: case-insensitive names (2026-05-17)

## What this fixes

Before this fix, the backend treated election names, candidate names, and
tier names as case-sensitive. Two elections named "GoT" and "got" could
both exist as separate entities; a candidate "Alice" plus a candidate
"alice" was allowed; and a ballot referencing "Popcorn" failed to match
when the candidate was stored as "popcorn". Usernames were already
case-insensitive — the fix extends the same treatment to the other
user-supplied identifiers.

The transform rewrites every event in the log so all references to a
given name use the *first-occurrence* display case. Subsequent events
that referenced the same entity with a different case are rewritten to
the canonical form. Renames update the canonical mapping going forward —
ballots cast after the rename use the new display case.

## When to run this

After the case-sensitivity code fix lands in `master` and is ready to
deploy. The transform must run **before** the new code is live, because
the new code's lowercase keys would silently collapse case-variant
entities at projection time (which is fine for clean data but would lose
data if two separately-tracked entities happened to share a lowercase
name).

## What you need

- A workstation with the `vote-dev` CLI built (`scripts/dev --help` to
  verify, or `./gradlew :tools:installDist`).
- AWS credentials configured for the production account (the AWS SDK
  will pick them up from the standard chain).
- The current deploy procedure (script, button, manual upload — whatever
  applies). The deploy step is *not* automated by this runbook.
- Optional but recommended: a separate "what if?" pass against the
  production data first by running `vote-dev normalize-case` in
  `--dry-run` mode on a fresh backup.

## The runbook

### 1. Dry run (recommended)

Take a backup and run the transform with `--dry-run` to surface any hard
collisions before you commit to the maintenance window.

PowerShell:
```pwsh
scripts\dev backup-dynamodb datafix\2026-05-17-case-insensitive-names\backup\dryrun.jsonl --prod
scripts\dev normalize-case datafix\2026-05-17-case-insensitive-names\backup\dryrun.jsonl `
    datafix\2026-05-17-case-insensitive-names\backup\dryrun.transformed.jsonl --dry-run
```

Bash:
```sh
scripts/dev backup-dynamodb datafix/2026-05-17-case-insensitive-names/backup/dryrun.jsonl --prod
scripts/dev normalize-case datafix/2026-05-17-case-insensitive-names/backup/dryrun.jsonl \
    datafix/2026-05-17-case-insensitive-names/backup/dryrun.transformed.jsonl --dry-run
```

- If the transform reports collisions, you must resolve them before
  proceeding — see [Resolving collisions](#resolving-collisions).
- If it reports `Rewrote N name reference(s)` and exits cleanly, you're
  ready for the real run.

### 2. The real run

The driver script does pause → backup → transform → nuke → restore in
one shot. It stops short of the deploy and prints a reminder.

PowerShell:
```pwsh
datafix\2026-05-17-case-insensitive-names\run.ps1 --prod
```

Bash:
```sh
datafix/2026-05-17-case-insensitive-names/run.sh --prod
```

Each destructive step (pause / nuke / restore) requires a confirmation
prompt of the form `type 'pause production' to continue`, so a stray
keypress can't fire the whole pipeline. Add `--yes` to skip every prompt
when you've already done the dry run and trust the result.

### 3. Deploy

After the script finishes, deploy the new backend code via your normal
process. The event log is paused while you deploy, so no writes land
during the window.

### 4. Resume

When the deploy is verified, run:

```pwsh
datafix\2026-05-17-case-insensitive-names\finalize.ps1 --prod
```

or directly:

```pwsh
scripts\dev resume-event-log --prod
```

(Both do the same thing; the finalize script is just a reminder.)

## Resolving collisions

A hard collision happens when the old (broken) code accepted two
independent registrations of the same lowercase identity — e.g., two
`ElectionCreated` events for "GoT" and "got" that produced two distinct
elections with their own ballots. The new code wouldn't allow this, so
the transform refuses to silently collapse them; you have to decide
which one wins.

To resolve:

1. The transform prints a collision report identifying each pair and the
   event ID where the second one was created.
2. Open the backup JSONL in an editor. It's one JSON object per line, in
   event_id order.
3. For each collision, decide:
   - **Keep the first, delete the second**: remove the line for the second
     `ElectionCreated` *and* every subsequent line that references it
     (ballots cast on it, candidates added to it, etc.). Be sure to also
     delete its `ElectionDeleted` if one exists.
   - **Merge into the first**: rewrite the second `ElectionCreated`'s name
     to match the first's case, then let the transform do the rest.
   - **Rename one to disambiguate**: edit one of the `ElectionCreated`
     events to use a genuinely different name, then update every later
     event that references it.
4. Re-run `normalize-case` on the edited backup. Repeat until it exits
   cleanly.
5. Use the cleaned backup as input to the real run.

## What's in this directory

- `README.md` — this file.
- `run.ps1` / `run.sh` — driver script.
- `finalize.ps1` / `finalize.sh` — post-deploy resume helper.
- `backup/` — timestamped backup and transformed files. Gitignored; this
  is operator state, not source. The script writes here.

## Rolling back

If something goes wrong after the restore but before deploy:

1. The original `backup-{timestamp}.jsonl` is the untouched
   pre-transform snapshot.
2. To roll back: `scripts/dev nuke-dynamodb --prod --yes` then
   `scripts/dev restore-dynamodb backup/backup-{timestamp}.jsonl --prod --yes`.
3. Then resume the event log.

If something goes wrong after deploy: roll the deploy back to the
previous version, then follow the rollback above.
