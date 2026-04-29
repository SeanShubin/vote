#!/usr/bin/env bash
# One-time bootstrap of the AWS resources GitHub Actions needs to deploy
# pairwisevote.com. Idempotent — re-running is safe.
#
# Usage: deploy/bootstrap.sh
#
# Requirements:
#   - AWS CLI configured with admin (or sufficient IAM) credentials
#   - Optional: gh CLI installed and authenticated, to set the GitHub secret

set -euo pipefail

REGION="us-east-1"
STACK_NAME="pairwisevote-bootstrap"
TEMPLATE="$(dirname "$0")/bootstrap.yaml"

echo "Verifying AWS credentials..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "  Account: $ACCOUNT_ID"

# The GitHub OIDC provider is a single per-account resource. If it already
# exists (e.g., from a prior project's bootstrap), we adopt it instead of
# trying to create a duplicate.
OIDC_ARN="arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
    echo "  OIDC provider already exists — adopting"
    CREATE_OIDC="false"
else
    echo "  OIDC provider not found — will create"
    CREATE_OIDC="true"
fi

echo "Deploying $STACK_NAME stack..."
aws cloudformation deploy \
    --region "$REGION" \
    --stack-name "$STACK_NAME" \
    --template-file "$TEMPLATE" \
    --parameter-overrides "CreateOidcProvider=$CREATE_OIDC" \
    --capabilities CAPABILITY_NAMED_IAM \
    --no-fail-on-empty-changeset

ROLE_ARN=$(aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name "$STACK_NAME" \
    --query 'Stacks[0].Outputs[?OutputKey==`DeployRoleArn`].OutputValue' \
    --output text)

GH_CMD=$(aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name "$STACK_NAME" \
    --query 'Stacks[0].Outputs[?OutputKey==`GitHubSecretCommand`].OutputValue' \
    --output text)

echo
echo "Deploy role ARN: $ROLE_ARN"
echo

if command -v gh >/dev/null 2>&1; then
    echo "Setting AWS_ACCOUNT_ID GitHub secret via gh..."
    eval "$GH_CMD"
    echo "  Done."
else
    echo "gh CLI not found. Set the GitHub secret manually:"
    echo "  $GH_CMD"
    echo "Or via the web: Settings -> Secrets and variables -> Actions ->"
    echo "                 New repository secret -> AWS_ACCOUNT_ID = $ACCOUNT_ID"
fi

echo
echo "Bootstrap complete. Push to master to trigger the first frontend deploy."
