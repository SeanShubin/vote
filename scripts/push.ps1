$ErrorActionPreference = 'Stop'

git push
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sha = (git rev-parse HEAD).Trim()
$runId = $null
for ($i = 0; $i -lt 30; $i++) {
    try {
        $raw = gh run list --limit 20 --json databaseId,headSha 2>$null
        if ($raw) {
            $runs = $raw | ConvertFrom-Json
            $runId = ($runs | Where-Object { $_.headSha -eq $sha } | Select-Object -First 1).databaseId
            if ($runId) { break }
        }
    } catch {
        # transient gh / API error — keep polling
    }
    Start-Sleep -Seconds 2
}
if (-not $runId) {
    Write-Host "No workflow run found for $sha after 60s"
    exit 1
}

# Stream live progress. Its exit code is deliberately NOT used as the verdict:
# `gh run watch` also exits non-zero on its own transient failures (a GitHub
# API blip, a dropped connection) while the run is still going — which is
# exactly how a still-running deploy got announced as "deploy failed".
gh run watch $runId

# The verdict comes only from the run's real conclusion. Poll until the run
# is genuinely "completed" — covering the case where `gh run watch` bailed
# out early — and read success/failure from there.
$conclusion = $null
for ($i = 0; $i -lt 60; $i++) {
    try {
        $raw = gh run view $runId --json status,conclusion 2>$null
        if ($raw) {
            $run = $raw | ConvertFrom-Json
            if ($run.status -eq 'completed') {
                $conclusion = $run.conclusion
                break
            }
        }
    } catch {
        # transient gh / API error — keep polling
    }
    Start-Sleep -Seconds 10
}

Add-Type -AssemblyName System.Speech
$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer
if ($conclusion -eq 'success') {
    $speak.Speak('deployed to production')
    exit 0
} else {
    Write-Host "Deploy did not succeed (conclusion: $conclusion)"
    $speak.Speak('deploy failed')
    exit 1
}
