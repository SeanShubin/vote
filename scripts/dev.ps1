# Thin shim around the tools/ Kotlin CLI. Auto-rebuilds when sources changed.
#
# Usage: scripts\dev.ps1 <subcommand> [args...]
#        scripts\dev.ps1 --rebuild           Force a rebuild even if up to date
#        scripts\dev.ps1 --help              Show available subcommands
#
# JAVA_HOME resolution (honors .tool-versions per asdf/mise convention):
#   1. If JAVA_HOME already points at a JDK matching the major version in
#      .tool-versions, it's used as-is.
#   2. Otherwise the shim searches mise/asdf install dirs and standard system
#      paths for a matching JDK. mise (https://mise.jdx.dev) is recommended for
#      cross-platform version management.

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir

$LauncherDir = Join-Path $ProjectDir 'tools\build\install\vote-dev'
$Launcher = Join-Path $LauncherDir 'bin\vote-dev.bat'
$SourcePaths = @(
    (Join-Path $ProjectDir 'tools\src'),
    (Join-Path $ProjectDir 'tools\build.gradle.kts')
)

function Get-JavaMajorFromRelease {
    param([string]$JavaHome)
    $releaseFile = Join-Path $JavaHome 'release'
    if (-not (Test-Path $releaseFile)) { return $null }
    $line = Get-Content $releaseFile | Where-Object { $_ -match '^JAVA_VERSION="(\d+)' } | Select-Object -First 1
    if ($line -and ($line -match '^JAVA_VERSION="(\d+)')) { return $matches[1] }
    return $null
}

function Resolve-JavaHome {
    $toolVersions = Join-Path $ProjectDir '.tool-versions'
    if (-not (Test-Path $toolVersions)) { return }

    $javaLine = Get-Content $toolVersions | Where-Object { $_ -match '^\s*java\s+(\S+)' } | Select-Object -First 1
    if (-not $javaLine) { return }
    if (-not ($javaLine -match '^\s*java\s+(\S+)')) { return }
    $javaSpec = $matches[1]
    if (-not ($javaSpec -match '(\d+)')) { return }
    $major = $matches[1]

    if ($env:JAVA_HOME) {
        $current = Get-JavaMajorFromRelease $env:JAVA_HOME
        if ($current -eq $major) { return }
    }

    $userHome = $env:USERPROFILE
    $patterns = @(
        "$userHome\.local\share\mise\installs\java\$javaSpec",
        "$userHome\.local\share\mise\installs\java\$major*",
        "$userHome\.asdf\installs\java\$javaSpec",
        "$userHome\.asdf\installs\java\*-$major.*",
        "$userHome\.jdks\corretto-$major*",
        "$userHome\.jdks\openjdk-$major*",
        "C:\Program Files\Amazon Corretto\jdk$major*",
        "C:\Program Files\Eclipse Adoptium\jdk-$major*",
        "C:\Program Files\Java\jdk-$major*",
        "C:\Program Files\Java\jdk$major*"
    )

    foreach ($pattern in $patterns) {
        $matches_ = Get-Item -Path $pattern -ErrorAction SilentlyContinue
        if (-not $matches_) { continue }
        foreach ($match in @($matches_)) {
            $javaExe = Join-Path $match.FullName 'bin\java.exe'
            if (Test-Path $javaExe) {
                $env:JAVA_HOME = $match.FullName
                return
            }
        }
    }

    Write-Host "[scripts/dev] WARNING: Could not find a Java $major JDK." -ForegroundColor Yellow
    Write-Host "[scripts/dev]   Install mise (https://mise.jdx.dev) or set JAVA_HOME manually." -ForegroundColor Yellow
}

$ForceRebuild = $false
if ($args.Count -gt 0 -and $args[0] -eq '--rebuild') {
    $ForceRebuild = $true
    if ($args.Count -gt 1) { $args = $args[1..($args.Count - 1)] } else { $args = @() }
}

function Test-NeedsBuild {
    if ($ForceRebuild) { return $true }
    if (-not (Test-Path $Launcher)) { return $true }

    $launcherTime = (Get-Item $Launcher).LastWriteTime
    foreach ($path in $SourcePaths) {
        if (-not (Test-Path $path)) { continue }
        $stale = Get-ChildItem -Path $path -Recurse -File -ErrorAction SilentlyContinue |
                 Where-Object { $_.LastWriteTime -gt $launcherTime } |
                 Select-Object -First 1
        if ($stale) { return $true }
    }
    return $false
}

Resolve-JavaHome

if (Test-NeedsBuild) {
    Write-Host '[scripts/dev] Building tools (sources changed or first run)...'
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat ':tools:installDist' '-q'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

& $Launcher @args
exit $LASTEXITCODE
