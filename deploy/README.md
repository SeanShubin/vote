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
