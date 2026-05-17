#!/usr/bin/env bash
# Driver for the case-insensitive names datafix.
#
# Sequence: pause event log → backup → normalize-case → nuke → restore.
# Stops short of the deploy step, which you run manually. After the
# deploy lands, run finalize.sh (or `scripts/dev resume-event-log --prod`).
#
# Usage: datafix/2026-05-17-case-insensitive-names/run.sh [--prod] [--yes]

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
BACKUP_DIR="$SCRIPT_DIR/backup"
mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date +'%Y%m%d-%H%M%S')"
BACKUP_FILE="$BACKUP_DIR/backup-$TIMESTAMP.jsonl"
TRANSFORMED_FILE="$BACKUP_DIR/transformed-$TIMESTAMP.jsonl"

prod_flag=()
yes_flag=()
[ "$PROD" = "1" ] && prod_flag=(--prod)
[ "$YES" = "1" ] && yes_flag=(--yes)

run_step() {
    local label="$1"; shift
    if ! "$DEV_SCRIPT" "$@"; then
        local code=$?
        echo
        echo "STOPPED. The pipeline halted at step '$label'." >&2
        echo "Earlier steps that already ran are NOT rolled back. See README for recovery." >&2
        exit "$code"
    fi
}

echo
echo "=== Case-insensitive names datafix ==="
if [ "$PROD" = "1" ]; then echo "Target:      PRODUCTION"; else echo "Target:      local DynamoDB"; fi
echo "Backup:      $BACKUP_FILE"
echo "Transformed: $TRANSFORMED_FILE"
echo

# Step 1: pause the event log so no writes land during the window.
echo "[1/4] Pausing event log..."
run_step "pause-event-log" pause-event-log "${prod_flag[@]}" "${yes_flag[@]}"

# Step 2: take a full backup of the current event log. This is the
# untouched original — keep it; rollback restores from this file.
echo
echo "[2/4] Backing up event log to $BACKUP_FILE..."
run_step "backup-dynamodb" backup-dynamodb "$BACKUP_FILE" "${prod_flag[@]}"

# Step 3: transform. If hard collisions are present, this exits non-zero
# with a report and the pipeline halts. See README "Resolving collisions"
# for what to do next.
echo
echo "[3/4] Normalizing case..."
run_step "normalize-case" normalize-case "$BACKUP_FILE" "$TRANSFORMED_FILE"

# Step 4: wipe both tables, then restore from the transformed file.
# This is the destructive step — restore rebuilds the projection from
# the new event log on the fly, so vote_data ends up correct without a
# separate sync call.
echo
echo "[4/4] Nuking and restoring with transformed events..."
run_step "nuke-dynamodb" nuke-dynamodb "${prod_flag[@]}" "${yes_flag[@]}"
run_step "restore-dynamodb" restore-dynamodb "$TRANSFORMED_FILE" "${prod_flag[@]}" "${yes_flag[@]}"

# Stop here. The event log stays paused — the next step is your deploy,
# which we don't automate.
suffix=""
[ "$PROD" = "1" ] && suffix=" --prod"
echo
echo "=== Data transform complete ==="
echo
echo "NEXT STEPS (you do these manually):"
echo "  1. Deploy the new backend code via your normal process."
echo "  2. Verify the deploy is healthy."
echo "  3. Resume the event log:"
echo
echo "       datafix/2026-05-17-case-insensitive-names/finalize.sh$suffix"
echo
echo "     or equivalently:"
echo
echo "       scripts/dev resume-event-log$suffix"
echo
echo "The event log is PAUSED. New writes will be rejected until you resume."
