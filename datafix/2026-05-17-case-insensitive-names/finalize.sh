#!/usr/bin/env bash
# Post-deploy step: resume the event log.
#
# Run after the new backend code is deployed and verified. This is just a
# thin reminder around `scripts/dev resume-event-log` — you can call that
# directly if you prefer.
#
# Usage: datafix/2026-05-17-case-insensitive-names/finalize.sh [--prod] [--yes]

set -euo pipefail

PROD=0
YES=0
for arg in "$@"; do
    case "$arg" in
        --prod) PROD=1 ;;
        --yes)  YES=1 ;;
        *) echo "Unknown flag: $arg" >&2; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEV_SCRIPT="$PROJECT_DIR/scripts/dev"

args=(resume-event-log)
[ "$PROD" = "1" ] && args+=(--prod)
[ "$YES" = "1" ] && args+=(--yes)

exec "$DEV_SCRIPT" "${args[@]}"
