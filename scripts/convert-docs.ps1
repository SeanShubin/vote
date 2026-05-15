# PowerShell variant of scripts/convert-docs. Same behavior:
# regenerate frontend/src/jsMain/resources/methodology.html from
# frontend/src/jsMain/resources/content/spoiler-free-voting.md.
#
# See scripts/convert-docs for the design rationale.
#
# Usage: scripts\convert-docs.ps1

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$SourceFile = Join-Path $ProjectDir 'docs\spoiler-free-voting.md'
$OutputFile = Join-Path $ProjectDir 'frontend\src\jsMain\resources\methodology.html'

if (-not (Test-Path $SourceFile)) {
    Write-Error "Source file not found: $SourceFile"
    exit 1
}

if (-not (Get-Command npx -ErrorAction SilentlyContinue)) {
    Write-Error "npx not found. Install Node.js (mise install node@lts) and re-run."
    exit 1
}

Write-Host "Converting $SourceFile -> $OutputFile"

$Header = @'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Spoiler-Free Voting Method</title>
    <link rel="icon" type="image/svg+xml" href="/favicon.svg">
    <style>
        /*
          Self-contained styles. This page is a static HTML file served
          alongside the SPA's frontend.js — it intentionally doesn't
          pull in the SPA's styles.css because the layout it needs
          (long-form reading column, generous line height, large
          headings) doesn't match the SPA's app-shell containers.
        */
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI",
                Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            line-height: 1.6;
            max-width: 800px;
            margin: 0 auto;
            padding: 0 1rem 3rem 1rem;
            color: #333;
            background: #f5f5f5;
        }
        .nav-header {
            position: sticky;
            top: 0;
            background: #f5f5f5;
            padding: 0.75rem 0;
            border-bottom: 2px solid #2c7be5;
            margin-bottom: 1.5rem;
            z-index: 100;
        }
        .back-link {
            display: inline-block;
            padding: 0.4rem 0.9rem;
            background: #2c7be5;
            color: white;
            text-decoration: none;
            border-radius: 4px;
            font-weight: 500;
            font-size: 0.9rem;
        }
        .back-link:hover {
            background: #1c5fb8;
        }
        h1, h2, h3 {
            color: #1f3956;
            margin-top: 2rem;
        }
        h1 {
            border-bottom: 2px solid #2c7be5;
            padding-bottom: 0.3em;
            margin-top: 0;
        }
        h2 {
            border-bottom: 1px solid #aab8ca;
            padding-bottom: 0.2em;
        }
        h3 {
            color: #34495e;
        }
        p, ul, ol {
            margin: 0.75rem 0;
        }
        ul, ol {
            padding-left: 1.8rem;
        }
        li {
            margin: 0.25rem 0;
        }
        code {
            background: #ecf2fb;
            padding: 0.1rem 0.35rem;
            border-radius: 3px;
            font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
            font-size: 0.9em;
        }
        pre {
            background: white;
            border: 1px solid #aab8ca;
            padding: 0.9rem 1rem;
            border-radius: 5px;
            overflow-x: auto;
            font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
            font-size: 0.88rem;
            line-height: 1.5;
        }
        pre code {
            background: transparent;
            padding: 0;
        }
        blockquote {
            border-left: 4px solid #2c7be5;
            padding: 0.4rem 0 0.4rem 1rem;
            margin: 1rem 0;
            color: #555;
            background: #ecf2fb;
            border-radius: 0 4px 4px 0;
        }
        strong {
            color: #1f3956;
        }
    </style>
</head>
<body>

<div class="nav-header">
    <a href="/" class="back-link">← Back to Vote</a>
</div>

'@

$Footer = @'

</body>
</html>
'@

# Run marked-cli with explicit -i (input file) rather than stdin
# redirection — under PowerShell the binary string pipe through npx
# doesn't always survive cleanly, so marked ends up converting its own
# binary source instead of our input. -i is robust across shells.
$convertedHtml = & npx --quiet marked -i $SourceFile

# Write the assembled page in UTF-8 (no BOM) so browsers parse it cleanly.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutputFile, $Header + ($convertedHtml -join "`n") + $Footer, $utf8NoBom)

Write-Host "OK Wrote $OutputFile"
