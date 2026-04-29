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
| Frontend CFN stack progress | [CloudFormation → pairwisevote-frontend](https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks) → Events tab |
| ACM cert issuance | [Certificate Manager (us-east-1)](https://us-east-1.console.aws.amazon.com/acm/home?region=us-east-1#/certificates/list) — `Pending validation` → `Issued` automatically |
| Route 53 records | [Route 53 → pairwisevote.com](https://us-east-1.console.aws.amazon.com/route53/v2/hostedzones) — cert validation CNAMEs + alias A/AAAA records appear |
| S3 bucket | [S3 → pairwisevote.com-frontend](https://us-east-1.console.aws.amazon.com/s3/buckets) |
| CloudFront distribution | [CloudFront → Distributions](https://us-east-1.console.aws.amazon.com/cloudfront/v4/home#/distributions) — `Deploying` → `Enabled` (propagation is the long pole) |

**The CFN Events tab is the single best "is it working" view** — it
ticks through each resource as CFN creates it, and any failure shows
the exact reason inline.

## Phase 3 — live site

| What | Where |
|---|---|
| The site | https://pairwisevote.com |
| DNS check | `nslookup pairwisevote.com` (PowerShell) — should return CloudFront IPs |
| Cert check | browser lock icon, or `openssl s_client -connect pairwisevote.com:443 -servername pairwisevote.com` |

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
| `gh run list --workflow=deploy-frontend.yml` | list recent deploy runs |
| `gh run view <run-id> --log-failed` | print only the failing step's log |
