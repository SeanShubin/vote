# Monitoring deploys

Where to look while bootstrap and CI/CD runs are in flight. URLs are
pre-filled for `SeanShubin/vote` in account `964638509728`, region
`us-east-1`.

## Phase 1 — `deploy/bootstrap.sh` (~30 seconds)

Run once per AWS account. Provisions the GitHub OIDC provider and the
`github-actions-deploy` IAM role.

| What | Where |
|---|---|
| Terminal output | the shell you ran it from |
| Bootstrap stack events | [CloudFormation → pairwisevote-bootstrap](https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks) → click the stack → **Events** tab |
| Resulting IAM role | [IAM → Roles → github-actions-deploy](https://us-east-1.console.aws.amazon.com/iam/home#/roles/details/github-actions-deploy) |
| GitHub secret | [github.com/SeanShubin/vote/settings/secrets/actions](https://github.com/SeanShubin/vote/settings/secrets/actions) — verify `AWS_ACCOUNT_ID` exists |

## Phase 2 — first push to `master` (~5-10 minutes)

The deploy workflow runs in GitHub but provisions resources in AWS.
Both sides have signal.

| What | Where |
|---|---|
| Live workflow logs | [github.com/SeanShubin/vote/actions](https://github.com/SeanShubin/vote/actions) — click the running job, expand each step |
| Frontend+backend CFN stack | [CloudFormation → pairwisevote-frontend](https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks) → Events tab |
| ACM cert issuance | [Certificate Manager (us-east-1)](https://us-east-1.console.aws.amazon.com/acm/home?region=us-east-1#/certificates/list) — covers `pairwisevote.com`, `www.pairwisevote.com`, `api.pairwisevote.com` |
| Route 53 records | [Route 53 → pairwisevote.com](https://us-east-1.console.aws.amazon.com/route53/v2/hostedzones) |
| S3 frontend bucket | [S3 → pairwisevote.com-frontend](https://us-east-1.console.aws.amazon.com/s3/buckets) |
| CloudFront distribution | [CloudFront → Distributions](https://us-east-1.console.aws.amazon.com/cloudfront/v4/home#/distributions) |
| Lambda function | [Lambda → pairwisevote-frontend-backend](https://us-east-1.console.aws.amazon.com/lambda/home?region=us-east-1#/functions) — Monitoring tab for invocations, Configuration → SnapStart |
| API Gateway | [API Gateway → HTTP APIs](https://us-east-1.console.aws.amazon.com/apigateway/main/apis?region=us-east-1) — Custom domain names → `api.pairwisevote.com` |
| Lambda logs | [CloudWatch → Log groups](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:log-groups) — `/aws/lambda/pairwisevote-frontend-backend` |
| DynamoDB tables | [DynamoDB → Tables](https://us-east-1.console.aws.amazon.com/dynamodbv2/home?region=us-east-1#tables) — `vote_data` (single-table) + `vote_event_log` (event sourcing) |

**The CFN Events tab is the single best "is it working" view** — it
ticks through each resource as CFN creates it, and any failure shows
the exact reason inline.

## Phase 3 — live site

| What | Where |
|---|---|
| Frontend | https://pairwisevote.com |
| Backend health | `curl https://api.pairwisevote.com/health` |
| DNS | `nslookup pairwisevote.com` (CloudFront), `nslookup api.pairwisevote.com` (APIGW) |
| Cert | browser lock icon, or `openssl s_client -connect pairwisevote.com:443 -servername pairwisevote.com` |

## Production logs and alerts

### Where errors land

Frontend exceptions are caught in UI handlers and shipped via
`apiClient.logErrorToServer(e)` → `POST /log-client-error` →
`RequestRouter.handleLogClientError` (logs at ERROR with the
literal prefix `CLIENT ERROR:`) → stdout → CloudWatch.

Backend logs are JSON-encoded by logback's `LogstashEncoder` and
forwarded structured by Lambda's `LogFormat: JSON` setting. Use
**Logs Insights field queries** rather than grep.

| What | Where |
|---|---|
| Log group | [`/aws/lambda/pairwisevote-frontend-backend`](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:log-groups/log-group/$252Faws$252Flambda$252Fpairwisevote-frontend-backend) — retention is 1 day, deliberately short |
| Logs Insights | [console](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:logs-insights) — pick the log group, then run a query (examples below) |
| Alarms | [CloudWatch → Alarms](https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#alarmsV2:) — `pairwisevote-frontend-client-errors` and `pairwisevote-frontend-backend-errors` |
| SNS topic | [SNS → Topics](https://us-east-1.console.aws.amazon.com/sns/v3/home?region=us-east-1#/topics) — `pairwisevote-frontend-alerts` |

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

Two CloudWatch alarms fire to an SNS topic that emails
`seanshubin@gmail.com`:

- **`pairwisevote-frontend-client-errors`** — any line containing
  `CLIENT ERROR:` in the last 5 minutes.
- **`pairwisevote-frontend-backend-errors`** — Lambda's built-in
  `Errors` metric (uncaught exceptions / non-2xx Lambda invocations)
  in the last 5 minutes.

**First-deploy gotcha**: SNS sends a confirmation email when the
subscription is first created. Click the link in that email or
notifications stay in `PendingConfirmation` and never arrive.

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

| Command | Effect |
|---|---|
| `gh secret set AWS_ACCOUNT_ID --body 964638509728 --repo SeanShubin/vote` | set the GitHub secret |
| `gh run watch` | tail the latest workflow run |
| `gh run list --workflow=deploy.yml` | list recent deploy runs |
| `gh run view <run-id> --log-failed` | print only the failing step's log |
