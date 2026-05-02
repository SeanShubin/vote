$ErrorActionPreference = 'Stop'

git push
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sha = (git rev-parse HEAD).Trim()
$runId = $null
for ($i = 0; $i -lt 30; $i++) {
    $runs = gh run list --limit 20 --json databaseId,headSha 2>$null | ConvertFrom-Json
    $runId = ($runs | Where-Object { $_.headSha -eq $sha } | Select-Object -First 1).databaseId
    if ($runId) { break }
    Start-Sleep -Seconds 2
}
if (-not $runId) {
    Write-Host "No workflow run found for $sha after 60s"
    exit 1
}

gh run watch $runId --exit-status
$exit = $LASTEXITCODE

Add-Type -AssemblyName System.Speech
$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer
if ($exit -eq 0) {
    $speak.Speak('deployed to production')
} else {
    $speak.Speak('deploy failed')
}
exit $exit
