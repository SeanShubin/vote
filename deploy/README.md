# Deploy

Infrastructure-as-code and CI/CD config for pairwisevote.com.

## Stacks

- `deploy/template.yaml` — SAM/CloudFormation template for the frontend stack
  (S3, CloudFront, ACM cert, Route 53 records). Milestone B will add Lambda +
  API Gateway resources to this same template.

See [`MONITORING.md`](MONITORING.md) for where to watch progress while
deploys run (CFN console, GitHub Actions, ACM, etc.).

## CI

`.github/workflows/deploy-frontend.yml` runs on every push to `master`/`main`
that touches frontend, shared modules, the template, or the workflow itself.
It:

1. Resolves the Route 53 hosted zone ID for `pairwisevote.com`.
2. Runs `aws cloudformation deploy` (idempotent — no-op when nothing changed).
3. Builds the production frontend bundle with
   `./gradlew :frontend:jsBrowserProductionWebpack -Papi.base.url=https://api.pairwisevote.com`.
4. `aws s3 sync` to the bucket.
5. Creates a CloudFront invalidation.

## One-time AWS bootstrap

Run the bootstrap script from a shell with AWS admin credentials configured:

```bash
deploy/bootstrap.sh
```

This deploys `deploy/bootstrap.yaml` as the `pairwisevote-bootstrap` stack,
which creates:

- The GitHub OIDC identity provider (idempotent)
- The `github-actions-deploy` IAM role with a trust policy scoped to
  `repo:SeanShubin/vote:*`
- AWS-managed policies attached for CloudFormation, S3, CloudFront, ACM,
  and Route 53 (broad for v1; scope down later)
- An `iam:PassRole` permission scoped to roles named `pairwisevote-*`
  (needed once Milestone B's Lambda execution role is created by CFN)

If you have the `gh` CLI installed, the script also sets the
`AWS_ACCOUNT_ID` GitHub secret. Otherwise it prints the command to run
manually (or you can set it in the web UI under
Settings → Secrets and variables → Actions).

The Route 53 hosted zone already exists per project setup. The workflow
looks the zone ID up by name at deploy time, so nothing to record in CI.

## First deploy

After the bootstrap above is done, push a frontend change to `master`.
The workflow creates the stack, validates the ACM cert (DNS validation
auto-completes via the hosted zone reference in the template), uploads
the bundle, and invalidates CloudFront. First run takes ~5-10 minutes
(mostly cert validation and CloudFront propagation). Subsequent
deploys are ~2 minutes.

## Local sanity check

Render the template locally with the same parameters CI uses:

```bash
aws cloudformation validate-template --template-body file://deploy/template.yaml
```

To test the frontend production bundle without deploying:

```bash
./gradlew :frontend:jsBrowserProductionWebpack -Papi.base.url=https://api.pairwisevote.com
ls frontend/build/dist/js/productionExecutable/
```
