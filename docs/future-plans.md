# Future Plans

Genuine pending work — things designed but not yet built, organized by topic.
History (the migration plans, retrospectives) lives elsewhere; this file is
forward-looking only.

## How to use this file

- Each section is one self-contained proposal. Add new ones; resolve and
  remove (or move to a "shipped" appendix) when done.
- If a section grows large enough to warrant its own document, link out to
  it from the section header but keep a one-paragraph summary here so the
  full set of pending work stays discoverable in one place.

---

## 1. Make policy constants runtime-configurable

**Problem**: TTLs, validation limits, and password requirements are
hardcoded across the backend. Tuning them today requires a code change and
redeploy. They're security/UX dials we're likely to want to turn without
shipping a release.

**Strong candidates** (with current values):

| Constant                    | Where it lives                      | Default                          | Why dynamic helps                                                              |
| --------------------------- | ----------------------------------- | -------------------------------- | ------------------------------------------------------------------------------ |
| Access token TTL            | `TokenEncoder.accessTokenDuration`  | 10 min                           | Tighten under suspected compromise; loosen to reduce refresh frequency.        |
| Refresh token TTL           | `TokenEncoder.refreshTokenDuration` | 30 days                          | Direct knob for "how long does a logged-in session survive?"                   |
| Reset token TTL             | `TokenEncoder.resetTokenDuration`   | 1 hr                             | Common dial — paranoid (15 min) vs forgiving (24 hr).                          |
| Refresh cookie maxAge       | `CookieConfig.maxAge`               | 30 days                          | Should always equal refresh token TTL — group as one "session lifetime" param. |
| Max input length            | `Validation.kt` (×5 places)         | 200 chars                        | Tighten for abuse, relax for a specific election with long names.              |
| **Minimum password length** | `Validation.validatePassword`       | currently `>= 1` (any non-empty) | **Most impactful**: real security gap today.                                   |

**Proposed mechanism**: AWS Systems Manager Parameter Store under a
`/pairwisevote/config/` prefix. Free for standard parameters, the Lambda
role gets `ssm:GetParameter`, edits happen in the AWS console (or CLI) with
no redeploy. Lambda re-reads on cold start; warm Lambdas pick up changes
within a 5-minute memoization window.

Path names (suggested):
- `/pairwisevote/config/access-token-ttl-seconds`
- `/pairwisevote/config/refresh-token-ttl-days` (also drives cookie maxAge)
- `/pairwisevote/config/reset-token-ttl-hours`
- `/pairwisevote/config/max-input-length`
- `/pairwisevote/config/min-password-length`

**Alternative**: DynamoDB-backed config item visible from an admin UI page
(reuses the AUDITOR/data-browser pattern). More UX work but no extra AWS
service.

**Priority pick if doing one**: minimum password length. The current
"non-empty" floor is a real gap and the only one where the default value
itself is wrong rather than just inflexible.

**Out of scope for this proposal** (don't make these dynamic):
- Password hash algorithm — changing breaks every existing user's password.
- JWT signing algorithm — changing invalidates all in-flight tokens.
- DynamoDB key prefixes — changing breaks existing data layout.

---

## 2. Personal access tokens for CLI / debug auth

**Problem**: Web sessions are deliberately walled off from CLI tools (the
HttpOnly refresh cookie is meant for a browser tab only). When debugging
together over chat, that means there's no fast way to share authenticated
state — only screenshots, network-tab pastes, etc.

**Decision (chosen)**: Personal Access Tokens, like GitHub PATs.

**Sketch**:
- Settings page in the SPA, visible to the logged-in user, with "Generate
  token" → returns a one-time `pwv_…` string the user copies into a local
  file (or chat).
- Backend stores a **hash** of the token in DynamoDB keyed by `(user, token-id)`,
  plus a label and optional expiry.
- Each token carries a label (`claude-debug`, `my-laptop`, etc.); the
  `event_log` records `authority=Sean(via=claude-debug)` so a third party
  reviewing actions later can tell which actor performed them.
- Tokens are revocable individually from the same Settings page.
- Authentication: `Authorization: Bearer pwv_…` against the existing
  `/api/*` paths. Backend validates (DynamoDB lookup) and proceeds with
  the matching user's identity + role.

**Why deferred**: nothing urgent breaks without it; the value is purely
"easier collaborative debugging." Re-evaluate when we're spending real time
working around the lack of CLI auth.

---

## 3. Multi-site deployment (separate stack per site)

**Problem**: The deployment is hardcoded to a single site (`pairwisevote.com`).
Reusing this codebase for a second site (different domain, separate S3 bucket,
separate DynamoDB tables, fully isolated data) currently means forking or
hand-editing config. Goal: deploy the same code to N sites from the same repo,
each fully isolated — separate stacks, no shared data, no tenant concept in
the domain model.

**Why separate stacks (not multi-tenant)**: Cleaner isolation (one site's
incident can't leak to another), per-site billing, no schema changes to
domain entities, and the data layer already assumes single-tenant. Cost is
duplicated AWS resources per site (one CloudFront distribution, one ACM cert,
two DynamoDB tables) — fine for ~2–10 sites, clunky beyond that.

**Changes required**:

1. **Workflow parameterization** (`.github/workflows/deploy.yml:21-25`).
   The four hardcoded env vars (`AWS_REGION`, `STACK_NAME`, `BOOTSTRAP_STACK`,
   `DOMAIN_NAME`) become per-site inputs. Suggested shape: a `sites/<name>.env`
   file per site committed to the repo, plus a workflow matrix that deploys
   each on push (or `workflow_dispatch` with a site picker for one-at-a-time
   deploys).

2. **Per-stack DynamoDB table names** (`deploy/template.yaml:215, 246`).
   Currently `TableName: vote_data` / `vote_event_log` — collide if two
   stacks land in the same AWS account. Change to
   `!Sub "${AWS::StackName}-vote-data"` and pass the resolved names to the
   Lambda as new env vars (`MAIN_TABLE_NAME`, `EVENT_LOG_TABLE_NAME`).

3. **Backend table-name constants → runtime config**
   (`backend/.../repository/DynamoDbSingleTableSchema.kt:7-9`). The two
   table-name `const val`s are referenced from four files
   (`DynamoDbSingleTableCommandModel`, `…QueryModel`, `DynamoDbEventLog`,
   `DynamoDbRawTableScanner`) at ~50 sites total. Refactor `DynamoDbSingleTableSchema`
   from a singleton to an injected class with `mainTable`/`eventLogTable`
   fields; `RepositoryFactory` reads them from env vars and constructs the
   schema. Keep the prefix constants (`USER_PREFIX`, etc.) — those are wire
   format, not infrastructure. Mechanical refactor, low risk.

4. **EmailFromAddress default** (`deploy/template.yaml:18`). Drop the
   `pairwisevote.com` default — make it required so a misconfigured deploy
   fails fast instead of silently sending from the wrong domain.

5. **Per-site bootstrap stack**. `BOOTSTRAP_STACK` is already
   per-site-named, so each site gets its own artifacts bucket. Just deploy
   it once per new site before the first main-stack deploy.

**What does NOT need to change**:
- Domain entities (User, Election, Candidate, Ballot) — no `siteId` needed
  because each site has its own database.
- `JwtSecret`, `BackendLogGroup`, `BackendFunction`, alarms — all already
  scoped by `${AWS::StackName}`.
- IAM role for GitHub Actions — one role can deploy multiple stacks.
- Hosted-zone resolution (workflow lines 69–71) — already domain-driven.

**Effort**: ~half a day for the workflow + CFN changes, plus ~half a day
for the backend refactor and verification. Each new site after that is a
new `sites/<name>.env` file plus a one-time bootstrap-stack deploy.

**Risk**: The schema-name refactor touches every DynamoDB call site. Worth
running the integration tests against a real DynamoDB Local to verify nothing
slipped through, since unit tests with mocks won't catch a misnamed table at
the AWS API boundary.

---

## 4. Considered alternatives (not pursued)

These are alternative designs we evaluated and decided against. Linked here
so they're easy to find if circumstances change.

- **[Invite-link authentication](./invite-link-authentication.md)** — auth
  model where users get a one-time invite link instead of choosing a
  password. Decision: stuck with email/password (see this session's
  "Decent authorization system" discussion). Invite-link doc kept as
  reference; would be revisited if we wanted to remove the password-storage
  burden entirely.
