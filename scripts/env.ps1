# =============================================================================
# QuakeCraft — Strict Local Build Environment Setup (scripts/env.ps1)
# =============================================================================
# Source this file in every PowerShell build/test script:
#   . "$PSScriptRoot\env.ps1"
# Ensures ZERO system bloat outside C:\QuakeCraft.

$ErrorActionPreference = "Stop"

$Global:QUAKECRAFT_ROOT = "C:\QuakeCraft"
$Global:QUAKECRAFT_CACHE = "$QUAKECRAFT_ROOT\.build-cache"
$Global:QUAKECRAFT_TMP = "$QUAKECRAFT_CACHE\tmp"
$Global:QUAKECRAFT_GRADLE_HOME = "$QUAKECRAFT_CACHE\gradle"
$Global:QUAKECRAFT_BUILD = "$QUAKECRAFT_ROOT\build"
$Global:QUAKECRAFT_LAB = "$QUAKECRAFT_ROOT\lab"
$Global:QUAKECRAFT_BACKUPS = "$QUAKECRAFT_ROOT\backups"

# Create local directories if missing
foreach ($dir in @($QUAKECRAFT_CACHE, $QUAKECRAFT_TMP, $QUAKECRAFT_GRADLE_HOME, $QUAKECRAFT_BUILD, $QUAKECRAFT_LAB, "$QUAKECRAFT_LAB\q3home", $QUAKECRAFT_BACKUPS)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
}

# Redirect all temp and cache environment variables inside C:\QuakeCraft
$env:TEMP = $QUAKECRAFT_TMP
$env:TMP = $QUAKECRAFT_TMP
$env:TMPDIR = $QUAKECRAFT_TMP
$env:GRADLE_USER_HOME = $QUAKECRAFT_GRADLE_HOME
$env:UM_HOME = "$QUAKECRAFT_CACHE\um-home"
$env:UV_CACHE_DIR = "$QUAKECRAFT_CACHE\uv"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$QUAKECRAFT_TMP"

# Add local w64devkit, Gradle 9.8.0, SDL2, and JDK 25 to front of PATH
$w64bin = "$QUAKECRAFT_ROOT\Tools\w64devkit\bin"
$gradleBin = "$QUAKECRAFT_ROOT\Tools\gradle-9.8.0-bin\gradle-9.8.0\bin"
$sdlBin = "$QUAKECRAFT_ROOT\Tools\SDL2-2.32.10-win32-x64"
$jdkBin = "$env:JAVA_HOME\bin"

$env:PATH = "$w64bin;$gradleBin;$sdlBin;$jdkBin;$env:PATH"

function Invoke-Um {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ArgsList)
    uv run --directory "C:\Users\victor\.gemini\config\plugins\universal-modder" um @ArgsList
}
