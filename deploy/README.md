# Deploy

Infrastructure-as-code and CI/CD config for pairwisevote.com.

## Stacks

- `deploy/bootstrap.yaml` — one-time per-account scaffolding: GitHub OIDC
  provider (conditional), the `github-actions-deploy` IAM role, the
  deploy-artifacts S3 bucket. Run via `deploy/bootstrap.sh`.

- `deploy/template.yaml` — the application stack `pairwisevote-frontend`.
  S3 + CloudFront + ACM (frontend), Lambda + API Gateway HTTP API + custom
  domain (backend), Route 53 records, single ACM cert covering
  `pairwisevote.com`, `www.pairwisevote.com`, and `api.pairwisevote.com`.

See [`MONITORING.md`](MONITORING.md) for where to watch progress while
deploys run.

## CI

`.github/workflows/deploy.yml` runs on every push to `master`/`main`
(except docs-only changes). It:

1. Builds the backend shadow JAR (`./gradlew :backend:shadowJar`).
2. Builds the production frontend bundle, baking in
   `https://api.pairwisevote.com` as the API URL.
3. Resolves Route 53 zone ID + the deploy-artifacts bucket from the
   bootstrap stack outputs.
4. Runs `aws cloudformation package` — uploads the JAR to the artifacts
   bucket, rewrites `CodeUri` references in the template.
5. Runs `aws cloudformation deploy` against the application stack.
6. `aws s3 sync` the frontend bundle to the frontend bucket.
7. Creates a CloudFront invalidation.
8. Smoke-tests `https://api.pairwisevote.com/health`.

First run takes ~5-10 minutes (cert validation + CloudFront propagation).
Subsequent deploys are ~2-3 minutes.

## Pre-deploy step (only once, after pulling the monitoring changes)

The Lambda log group is now managed by CFN with explicit retention. The
previously auto-created log group must be deleted once so CFN can
adopt the name without a conflict on next deploy:

```bash
aws logs delete-log-group \
    --log-group-name /aws/lambda/pairwisevote-frontend-backend \
    --region us-east-1
```

After CFN creates the new (managed) log group on next deploy, retention
is set to 1 day. Subsequent deploys are no-ops.

## One-time AWS bootstrap

Run the bootstrap script from a shell with AWS admin credentials configured:

```bash
deploy/bootstrap.sh
```

This deploys `deploy/bootstrap.yaml` as the `pairwisevote-bootstrap` stack,
which creates:

- The GitHub OIDC identity provider (auto-detects existing one)
- The `github-actions-deploy` IAM role with trust scoped to
  `repo:SeanShubin/vote:*`
- AWS-managed policies attached for CloudFormation, S3, CloudFront, ACM,
  Route 53, Lambda, API Gateway, IAM, DynamoDB, CloudWatch Logs (broad
  for v1; scope down later)
- An `iam:PassRole` permission scoped to roles named `pairwisevote-*`
- The `pairwisevote-deploy-artifacts-<account-id>` S3 bucket where CFN
  uploads packaged Lambda artifacts (30-day lifecycle)

If you have the `gh` CLI installed, the script also sets the
`AWS_ACCOUNT_ID` GitHub secret. Otherwise it prints the command to run
manually (or set it in the web UI: Settings → Secrets and variables →
Actions).

The Route 53 hosted zone already exists per project setup. The workflow
looks the zone ID up by name at deploy time, so nothing to record in CI.

## Local sanity checks

Validate the templates:

```bash
aws cloudformation validate-template --template-body file://deploy/bootstrap.yaml
aws cloudformation validate-template --template-body file://deploy/template.yaml
```

Build the artifacts CI builds:

```bash
./gradlew :backend:shadowJar
ls backend/build/libs/vote-backend-lambda.jar

./gradlew :frontend:assemble -Papi.base.url=https://api.pairwisevote.com
ls frontend/build/dist/js/productionExecutable/
```

## Discord OAuth secrets

Sign-in is Discord-only. The four OAuth values the Lambda needs at runtime
live in SSM Parameter Store under `/${StackName}/discord/`:

| Path                                           | Type           | Source                                                                 |
| ---------------------------------------------- | -------------- | ---------------------------------------------------------------------- |
| `/pairwisevote-frontend/discord/client-id`     | `String`       | Discord Developer Portal → Application → General                       |
| `/pairwisevote-frontend/discord/redirect-uri`  | `String`       | `https://pairwisevote.com/api/auth/discord/callback`                   |
| `/pairwisevote-frontend/discord/guild-id`      | `String`       | Right-click the Discord server with Developer Mode on → Copy Server ID |
| `/pairwisevote-frontend/discord/client-secret` | `SecureString` | Discord Developer Portal → Application → OAuth2 → Reset Secret         |

All four parameters are operator-managed — they live outside the CFN
template entirely. `template.yaml` only declares their paths in the
Lambda's env vars and IAM policy. This decouples secret provisioning
from deploy timing: you can create the parameters before the first
deploy (so local-dev testing against deployed SSM works immediately),
and re-creating the stack later doesn't trip "parameter already exists"
errors against the values you've populated.

The Lambda re-reads each value at most every 5 minutes (in-process cache),
so rotating any of them takes effect within one TTL window — no redeploy
needed.

The IAM policy on the Lambda function grants `ssm:GetParameter` on these
four paths only, plus `kms:Decrypt` against the AWS-managed SSM key (the
default KMS key for `SecureString`). A compromised Lambda can't enumerate
other secrets in SSM.

For the one-time setup (first deploy + secret rotation), see the
PowerShell scripts in `.local/temp-scripts/`:

- `set-discord-ssm-parameters.ps1` — push all four values into SSM
- `verify-discord-ssm-parameters.ps1` — read them back (SecureString masked)
- `set-local-dev-discord-env.ps1` — point a local dev shell at the deployed SSM
