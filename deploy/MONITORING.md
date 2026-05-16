# Monitoring deploys

Where to look while bootstrap and CI/CD runs are in flight. URLs are
pre-filled for `SeanShubin/vote` in account `964638509728`, region
`us-east-1`.

## Phase 1 — `deploy/bootstrap.sh` (~30 seconds)

Run once per AWS account. Provisions the GitHub OIDC provider and the
`github-actions-deploy` IAM role.

| What                   | Where                                                                                                                                                               |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Terminal output        | the shell you ran it from                                                                                                                                           |
| Bootstrap stack events | [CloudFormation → pairwisevote-bootstrap](https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks) → click the stack → **Events** tab |
| Resulting IAM role     | [IAM → Roles → github-actions-deploy](https://us-east-1.console.aws.amazon.com/iam/home#/roles/details/github-actions-deploy)                                       |
| GitHub secret          | [github.com/SeanShubin/vote/settings/secrets/actions](https://github.com/SeanShubin/vote/settings/secrets/actions) — verify `AWS_ACCOUNT_ID` exists                 |

## Phase 2 — first push to `master` (~5-10 minutes)

The deploy workflow runs in GitHub but provisions resources in AWS.
Both sides have signal.

| What                       | Where                                                                                                                                                                                                |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Live workflow logs         | [github.com/SeanShubin/vote/actions](https://github.com/SeanShubin/vote/actions) — click the running job, expand each step                                                                           |
| Frontend+backend CFN stack | [CloudFormation → pairwisevote-frontend](https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks) → Events tab                                                         |
| ACM cert issuance          | [Certificate Manager (us-east-1)](https://us-east-1.console.aws.amazon.com/acm/home?region=us-east-1#/certificates/list) — covers `pairwisevote.com`, `www.pairwisevote.com`, `api.pairwisevote.com` |
| Route 53 records           | [Route 53 → pairwisevote.com](https://us-east-1.console.aws.amazon.com/route53/v2/hostedzones)                                                                                                       |
| S3 frontend bucket         | [S3 → pairwisevote.com-frontend](https://us-east-1.console.aws.amazon.com/s3/buckets)                                                                                                                |
| CloudFront distribution    | [CloudFront → Distributions](https://us-east-1.console.aws.amazon.com/cloudfront/v4/home#/distributions)                                                                                             |
| Lambda function            | [Lambda → pairwisevote-frontend-backend](https://us-east-1.console.aws.amazon.com/lambda/home?region=us-east-1#/functions) — Monitoring tab for invocations, Configuration → SnapStart               |
| API Gateway                | [API Gateway → HTTP APIs](https://us-east-1.console.aws.amazon.com/apigateway/main/apis?region=us-east-1) — Custom domain names → `api.pairwisevote.com`                                             |
| Lambda logs                | [CloudWatch → Log groups](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:log-groups) — `/aws/lambda/pairwisevote-frontend-backend`                                 |
| DynamoDB tables            | [DynamoDB → Tables](https://us-east-1.console.aws.amazon.com/dynamodbv2/home?region=us-east-1#tables) — `vote_data` (single-table) + `vote_event_log` (event sourcing)                               |

**The CFN Events tab is the single best "is it working" view** — it
ticks through each resource as CFN creates it, and any failure shows
the exact reason inline.

## Phase 3 — live site

| What           | Where                                                                                               |
| -------------- | --------------------------------------------------------------------------------------------------- |
| Frontend       | https://pairwisevote.com                                                                            |
| Backend health | `curl https://api.pairwisevote.com/health`                                                          |
| DNS            | `nslookup pairwisevote.com` (CloudFront), `nslookup api.pairwisevote.com` (APIGW)                   |
| Cert           | browser lock icon, or `openssl s_client -connect pairwisevote.com:443 -servername pairwisevote.com` |

## Production logs and alerts

### Where errors land

Frontend exceptions are caught in UI handlers and shipped via
`apiClient.logErrorToServer(e)` → `POST /log-client-error` →
`RequestRouter.handleLogClientError` (logs at ERROR with the
literal prefix `CLIENT ERROR:`) → stdout → CloudWatch.

Backend logs are JSON-encoded by logback's `LogstashEncoder` and
forwarded structured by Lambda's `LogFormat: JSON` setting. Use
**Logs Insights field queries** rather than grep.

| What          | Where                                                                                                                                                                                                                                               |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Log group     | [`/aws/lambda/pairwisevote-frontend-backend`](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:log-groups/log-group/$252Faws$252Flambda$252Fpairwisevote-frontend-backend) — retention is 1 day, deliberately short |
| Logs Insights | [console](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:logs-insights) — pick the log group, then run a query (examples below)                                                                                   |
| Alarms        | [CloudWatch → Alarms](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#alarmsV2:) — `pairwisevote-frontend-client-errors` and `pairwisevote-frontend-backend-errors`                                                       |
| SNS topic     | [SNS → Topics](https://us-east-1.console.aws.amazon.com/sns/v3/home?region=us-east-1#/topics) — `pairwisevote-frontend-alerts`                                                                                                                      |

### Useful Logs Insights queries

```
# All client-reported errors with timestamps and URLs
fields @timestamp, message
| filter message like /CLIENT ERROR:/
| sort @timestamp desc
| limit 50

# Backend exceptions only (JSON layout exposes level field)
fields @timestamp, level, logger_name, message, stack_trace
| filter level = "ERROR"
| sort @timestamp desc
| limit 50

# Slow handlers — find requests that took > 500ms
fields @timestamp, message, @duration
| filter @duration > 500
| sort @duration desc
```

### Alerts

There are now **two independent alert paths**, by design:

**Path A — direct SES emails from the backend (primary).** The four
top-level exception handlers (`LambdaHandler.handleRequest`,
`LambdaHandler.buildRouter` cold-init, `backend/app/Main.kt`,
`SimpleHttpHandler.handle`) plus the per-request `RequestRouter`
catch and the frontend `/log-client-error` endpoint all route through
`Notifications`. `SesNotifications` decorates `ConsoleNotifications`
in prod (gated on `EMAIL_FROM_ADDRESS` + `EMAIL_TO_ADDRESS` being set —
both come from CFN) and emails the recipient with the full message +
stack trace + request context. **This is what makes the email itself
the artifact** — no Logs Insights round-trip.

**Path B — SNS-via-CloudWatch (backstop).** Two CloudWatch alarms
fire to an SNS topic that emails `seanshubin@gmail.com`:

- **`pairwisevote-frontend-client-errors`** — any line containing
  `CLIENT ERROR:` in the last 5 minutes.
- **`pairwisevote-frontend-backend-errors`** — Lambda's built-in
  `Errors` metric (uncaught exceptions / non-2xx Lambda invocations)
  in the last 5 minutes.

These fire even when SES is broken (which is the whole point — they're
the "did Path A fail to deliver?" canary). Expect each real incident
to produce one rich SES email *plus* one terse SNS email; ignore the
SNS one if you already got the SES one.

**First-deploy gotchas**:

1. **SES domain verification** — the `AWS::SES::EmailIdentity` and
   three DKIM CNAMEs in `template.yaml` provision automatically, but
   verification takes minutes to ~hours for DNS to propagate and SES
   to revalidate. Until then, Path A silently logs `SesNotifications:
   failed to send alert email (...)` to stderr and Path B carries the
   only signal. Check status:
   ```
   aws ses get-identity-verification-attributes \
       --identities pairwisevote.com --region us-east-1
   ```
2. **SNS subscription confirmation** — SNS sends a confirmation email
   when the subscription is first created. Click the link in that email
   or notifications stay in `PendingConfirmation` and never arrive.
3. **SES sandbox** — if the account is still in the SES sandbox, the
   To address must also be a verified SES identity. Production
   account is out of sandbox; new sandboxed accounts need a one-time
   "production access" request through the SES console.

### On-demand log purge

Retention=1 day means yesterday's logs age out automatically. To
wipe everything *right now* (e.g., before reproducing a clean
test scenario):

```bash
aws logs delete-log-group \
    --log-group-name /aws/lambda/pairwisevote-frontend-backend \
    --region us-east-1
```

Lambda recreates the group on next invocation; CFN reconciles the
retention policy on next deploy.

## Inspecting application state

The architectural premise of the project is that **natural keys make
debugging obvious** — and the production tables follow that. Most state
lives in the `vote_data` single table keyed by PK (entity) and SK
(sub-entity or metadata). `vote_event_log` is the append-only audit trail.

### Key prefix reference

| PK             | SK                 | What                           |
| -------------- | ------------------ | ------------------------------ |
| `USER#alice`   | `METADATA`         | a registered user              |
| `ELECTION#Foo` | `METADATA`         | election metadata              |
| `ELECTION#Foo` | `CANDIDATE#Kotlin` | a candidate in election Foo    |
| `ELECTION#Foo` | `VOTER#bob`        | bob is eligible to vote in Foo |
| `ELECTION#Foo` | `BALLOT#bob`       | bob's cast ballot in Foo       |

(Source of truth: `backend/.../repository/DynamoDbSingleTableSchema.kt`.)

### Console UI (good for ad-hoc browsing)

[DynamoDB → Tables → vote_data → Explore table items](https://us-east-1.console.aws.amazon.com/dynamodbv2/home?region=us-east-1#item-explorer?initialTagKey=&maximize=true&table=vote_data)
— filter by Partition key (`PK = ELECTION#Foo`) to see everything in
one election; "begins with" on Sort key (`SK begins with BALLOT#`) to
narrow further. Same for `vote_event_log` to scroll the event stream.

### AWS CLI (good for scripts and copy-paste debugging)

```bash
# Did alice register?
aws dynamodb get-item --table-name vote_data --region us-east-1 \
    --key '{"PK":{"S":"USER#alice"},"SK":{"S":"METADATA"}}'

# Everything in election "Foo" (candidates, voters, ballots, metadata)
aws dynamodb query --table-name vote_data --region us-east-1 \
    --key-condition-expression "PK = :pk" \
    --expression-attribute-values '{":pk":{"S":"ELECTION#Foo"}}'

# Just the cast ballots in "Foo"
aws dynamodb query --table-name vote_data --region us-east-1 \
    --key-condition-expression "PK = :pk AND begins_with(SK, :sk)" \
    --expression-attribute-values '{":pk":{"S":"ELECTION#Foo"},":sk":{"S":"BALLOT#"}}'

# All registered users
aws dynamodb scan --table-name vote_data --region us-east-1 \
    --filter-expression "begins_with(PK, :p) AND SK = :s" \
    --expression-attribute-values '{":p":{"S":"USER#"},":s":{"S":"METADATA"}}'

# Tail the latest 20 domain events
aws dynamodb scan --table-name vote_event_log --region us-east-1 --max-items 20
```

PowerShell quoting tip: replace single-quotes around JSON with backticks
or use `--cli-input-json file://...` to avoid shell escape pain.

### Two-views debugging (project's documented strategy)

Per `docs/debugging-workflow.md`, when something looks wrong:

1. **Relational projection first** — query the conceptual model (what
   does the API say? what does `getUser` return?). Tells you **what** is wrong.
2. **Raw storage second** — direct `vote_data` query. Tells you **how**
   it's stored, exposing projection-vs-storage divergence.

The CLI snippets above are the raw-storage view. The relational view
in production is `curl https://api.pairwisevote.com/...` for whatever
endpoint covers the question.

## Lambda runtime metrics

[Lambda → pairwisevote-frontend-backend → Monitor tab](https://us-east-1.console.aws.amazon.com/lambda/home?region=us-east-1#/functions/pairwisevote-frontend-backend?tab=monitoring)
shows invocations, duration, errors, throttles, concurrent executions,
and **SnapStart restore times** (the cold-start cost we care about).
Most useful at-a-glance metric: the `Duration` chart's p50 vs p99.

## Cost monitoring

[Billing → Bills](https://us-east-1.console.aws.amazon.com/billing/home#/bills) —
the entire stack should be **$0** at friend-group scale. Anything
non-zero AWS line item that isn't Route 53 hosted zone (~$0.50/mo) is
a misconfiguration signal.

For active monitoring, set a [zero-spend budget alert](https://us-east-1.console.aws.amazon.com/billing/home#/budgets/create?budgetType=COST_BUDGET)
($1/month threshold, email to seanshubin@gmail.com). Free, fires once if
you ever leave the free tier.

## Single-terminal tail

```bash
gh run watch                              # follow the GitHub Actions run live
aws cloudformation describe-stack-events \
    --stack-name pairwisevote-frontend \
    --region us-east-1 --max-items 20     # latest stack events
```

The first command blocks until the workflow finishes; re-run the second
as needed.

## Common failure modes

- **ACM cert validation hangs** → almost always Route 53 hosted zone ID
  mismatch. The CFN Events tab will say so.
- **CloudFormation `DELETE_FAILED` on the bucket** → bucket isn't empty.
  A prior failed deploy left objects; empty it manually then retry.
- **GitHub Actions auth fails** → trust policy mismatch. Verify the
  role's trust policy `sub` matches `repo:SeanShubin/vote:*`.

## About `gh`

`gh` is [GitHub's official CLI](https://cli.github.com/). Optional —
the bootstrap script falls back to printing the manual command if
`gh` isn't installed.

Install on Windows:

```powershell
winget install --id GitHub.cli
gh auth login   # GitHub.com → HTTPS → browser login
```

Useful commands:

| Command                                                                   | Effect                            |
| ------------------------------------------------------------------------- | --------------------------------- |
| `gh secret set AWS_ACCOUNT_ID --body 964638509728 --repo SeanShubin/vote` | set the GitHub secret             |
| `gh run watch`                                                            | tail the latest workflow run      |
| `gh run list --workflow=deploy.yml`                                       | list recent deploy runs           |
| `gh run view <run-id> --log-failed`                                       | print only the failing step's log |
