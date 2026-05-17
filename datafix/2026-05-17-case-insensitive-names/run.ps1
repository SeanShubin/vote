# Driver for the case-insensitive names datafix.
#
# Sequence: pause event log → backup → normalize-case → nuke → restore.
# Stops short of the deploy step, which you run manually. After the
# deploy lands, run finalize.ps1 (or `scripts\dev resume-event-log --prod`).
#
# Usage: datafix\2026-05-17-case-insensitive-names\run.ps1 [--prod] [--yes]
#
# Each destructive step prompts for a confirmation phrase. Pass --yes to
# skip the prompts (intended for re-runs after a successful dry run).

$ErrorActionPreference = 'Stop'

$Prod = $false
$Yes = $false
foreach ($arg in $args) {
    switch ($arg) {
        '--prod' { $Prod = $true }
        '--yes'  { $Yes = $true }
        default  { Write-Host "Unknown flag: $arg" -ForegroundColor Red; exit 2 }
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = (Get-Item $ScriptDir).Parent.Parent.FullName
$DevScript = Join-Path $ProjectDir 'scripts\dev.ps1'
$BackupDir = Join-Path $ScriptDir 'backup'
if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

$Timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$BackupFile = Join-Path $BackupDir "backup-$Timestamp.jsonl"
$TransformedFile = Join-Path $BackupDir "transformed-$Timestamp.jsonl"

$ProdFlag = if ($Prod) { '--prod' } else { @() }
$YesFlag = if ($Yes) { '--yes' } else { @() }

function Invoke-VoteDev {
    param([string[]]$VoteArgs)
    & $DevScript @VoteArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "STOPPED. The pipeline halted at step '$($VoteArgs[0])'." -ForegroundColor Red
        Write-Host "Earlier steps that already ran are NOT rolled back. See README for recovery." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

Write-Host ""
Write-Host "=== Case-insensitive names datafix ===" -ForegroundColor Cyan
Write-Host "Target:      $(if ($Prod) { 'PRODUCTION' } else { 'local DynamoDB' })"
Write-Host "Backup:      $BackupFile"
Write-Host "Transformed: $TransformedFile"
Write-Host ""

# Step 1: pause the event log so no writes land during the window.
Write-Host "[1/4] Pausing event log..." -ForegroundColor Cyan
Invoke-VoteDev (@('pause-event-log') + $ProdFlag + $YesFlag)

# Step 2: take a full backup of the current event log. This is the
# untouched original — keep it; rollback restores from this file.
Write-Host ""
Write-Host "[2/4] Backing up event log to $BackupFile..." -ForegroundColor Cyan
Invoke-VoteDev (@('backup-dynamodb', $BackupFile) + $ProdFlag)

# Step 3: transform. If hard collisions are present, this exits non-zero
# with a report and the pipeline halts. See README "Resolving collisions"
# for what to do next.
Write-Host ""
Write-Host "[3/4] Normalizing case..." -ForegroundColor Cyan
Invoke-VoteDev @('normalize-case', $BackupFile, $TransformedFile)

# Step 4: wipe both tables, then restore from the transformed file.
# This is the destructive step — restore rebuilds the projection from
# the new event log on the fly, so vote_data ends up correct without a
# separate sync call.
Write-Host ""
Write-Host "[4/4] Nuking and restoring with transformed events..." -ForegroundColor Cyan
Invoke-VoteDev (@('nuke-dynamodb') + $ProdFlag + $YesFlag)
Invoke-VoteDev (@('restore-dynamodb', $TransformedFile) + $ProdFlag + $YesFlag)

# Stop here. The event log stays paused — the next step is your deploy,
# which we don't automate.
Write-Host ""
Write-Host "=== Data transform complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "NEXT STEPS (you do these manually):" -ForegroundColor Yellow
Write-Host "  1. Deploy the new backend code via your normal process."
Write-Host "  2. Verify the deploy is healthy."
Write-Host "  3. Resume the event log:"
Write-Host ""
Write-Host "       datafix\2026-05-17-case-insensitive-names\finalize.ps1$(if ($Prod) { ' --prod' })" -ForegroundColor Cyan
Write-Host ""
Write-Host "     or equivalently:"
Write-Host ""
Write-Host "       scripts\dev resume-event-log$(if ($Prod) { ' --prod' })" -ForegroundColor Cyan
Write-Host ""
Write-Host "The event log is PAUSED. New writes will be rejected until you resume." -ForegroundColor Yellow
