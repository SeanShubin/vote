# Post-deploy step: resume the event log.
#
# Run after the new backend code is deployed and verified. This is just a
# thin reminder around `scripts\dev resume-event-log` — you can call that
# directly if you prefer.
#
# Usage: datafix\2026-05-17-case-insensitive-names\finalize.ps1 [--prod] [--yes]

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

$resumeArgs = @('resume-event-log')
if ($Prod) { $resumeArgs += '--prod' }
if ($Yes)  { $resumeArgs += '--yes'  }

& $DevScript @resumeArgs
exit $LASTEXITCODE
