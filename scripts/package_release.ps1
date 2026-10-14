# =============================================================================
# QuakeCraft — Package Clean GitHub Release
# =============================================================================
# Assembles a distribution zip containing ONLY the modification binaries:
#   - mc-bridge: quakecraft-bridge-0.1.0.jar
#   - q3-bridge: quake3-bridge.exe, baseq3/*.dll, portable start_quake3.bat
# EXCLUDES:
#   - Retail Quake 3 files (pak0.pk3, etc.)
#   - Build tools (w64devkit, gradle, SDL2 SDK)
#   - Intermediate object files, caches, logs, source code bloat
# =============================================================================

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$Version = "0.1.0"
$ReleaseDir = "$QUAKECRAFT_BUILD\release"
$StageDir = "$ReleaseDir\QuakeCraft-v$Version"
$ZipFile = "$ReleaseDir\QuakeCraft-v$Version-windows-x64.zip"

Write-Host "=== Packaging QuakeCraft v$Version for GitHub Release ===" -ForegroundColor Cyan

# 1. Verify / Build necessary binaries
$Q3Exe = "$QUAKECRAFT_BUILD\q3\quake3-bridge.exe"
$QaGameDll = "$QUAKECRAFT_BUILD\q3\baseq3\qagamex86.dll"
$CGameDll = "$QUAKECRAFT_BUILD\q3\baseq3\cgamex86.dll"
$UiDll = "$QUAKECRAFT_BUILD\q3\baseq3\uix86.dll"
$McJar = "$QUAKECRAFT_BUILD\mc\quakecraft-bridge-0.1.0.jar"

if (-not (Test-Path $Q3Exe) -or -not (Test-Path $QaGameDll) -or -not (Test-Path $CGameDll) -or -not (Test-Path $UiDll)) {
    Write-Host "Building Quake 3 engine & native DLLs..." -ForegroundColor Yellow
    & "$QUAKECRAFT_ROOT\scripts\build_q3.ps1"
}

if (-not (Test-Path $McJar)) {
    Write-Host "Building Minecraft Fabric mod jar..." -ForegroundColor Yellow
    & "$QUAKECRAFT_ROOT\scripts\build_mc.ps1"
}

# 2. Reset clean staging directory
if (Test-Path $StageDir) {
    Remove-Item -Recurse -Force $StageDir
}
if (Test-Path $ZipFile) {
    Remove-Item -Force $ZipFile
}

New-Item -ItemType Directory -Force -Path "$StageDir\mods", "$StageDir\quake3\baseq3" | Out-Null

# 3. Copy Minecraft Fabric Mod
Copy-Item -Force $McJar "$StageDir\mods\quakecraft-bridge-$Version.jar"
Write-Host "[OK] Staged Fabric Mod: mods\quakecraft-bridge-$Version.jar" -ForegroundColor Green

# 4. Copy Quake 3 Engine and Native DLLs
Copy-Item -Force $Q3Exe "$StageDir\quake3\quake3-bridge.exe"
Copy-Item -Force $QaGameDll "$StageDir\quake3\baseq3\qagamex86.dll"
Copy-Item -Force $CGameDll "$StageDir\quake3\baseq3\cgamex86.dll"
Copy-Item -Force $UiDll "$StageDir\quake3\baseq3\uix86.dll"
Write-Host "[OK] Staged Quake 3 Bridge Binaries: quake3\quake3-bridge.exe + baseq3\*.dll" -ForegroundColor Green

# 5. Create helper start_quake3.bat
$BatContent = @"
@echo off
setlocal
cd /d "%~dp0"

echo =======================================================================
echo QuakeCraft Engine Bridge Launcher
echo =======================================================================

if not exist "baseq3\pak0.pk3" (
    echo.
    echo [ERROR] Retail Quake III Arena assets not found!
    echo.
    echo Please copy 'pak0.pk3' from your legally owned Quake III Arena install
    echo (Steam, GOG, or CD-ROM) into this folder:
    echo    %~dp0baseq3\pak0.pk3
    echo.
    pause
    exit /b 1
)

echo Starting quake3-bridge.exe (125 FPS, Tournament Physics, Shared Memory Bridge)...
start "" "%~dp0quake3-bridge.exe" +set fs_basepath "%~dp0" +set fs_homepath "%~dp0q3home" +set vm_game 0 +set vm_cgame 0 +set vm_ui 0 +set sv_pure 0 +set r_fullscreen 0 +set r_mode -1 +set r_customwidth 854 +set r_customheight 480 +set in_mouse 0 +set bot_enable 0 +set com_maxfps 125 +set pmove_fixed 0 +set pmove_msec 8 +devmap q3dm1

echo.
echo [OK] Quake 3 Bridge is running!
echo Now launch Minecraft 26.3 Fabric with JVM flag:
echo    --enable-native-access=ALL-UNNAMED
echo.
"@
[System.IO.File]::WriteAllText("$StageDir\quake3\start_quake3.bat", $BatContent, [System.Text.Encoding]::ASCII)

# 6. Create PAK0 placeholder reminder
$Pak0Reminder = @"
=== PLACE YOUR RETAIL PAK0.PK3 HERE ===

QuakeCraft DOES NOT ship any proprietary Quake III Arena game assets.
To play, you must copy 'pak0.pk3' (and optionally 'pak1.pk3'..'pak8.pk3')
from your legally owned Quake III Arena copy (Steam, GOG, or retail CD)
into this folder.

Expected path:
  quake3\baseq3\pak0.pk3
"@
[System.IO.File]::WriteAllText("$StageDir\quake3\baseq3\PUT_YOUR_PAK0_PK3_HERE.txt", $Pak0Reminder, [System.Text.Encoding]::UTF8)

# 7. Create README.txt inside release
$ReadmeContent = @"
===============================================================================
QuakeCraft v$Version — Quick Installation Guide
===============================================================================

QuakeCraft connects genuine Quake III Arena (125 FPS physics, weapons, combat)
with Minecraft Java Edition 26.3 using Win32 Named Shared Memory and depth compositing.

Showcase Video: https://www.youtube.com/watch?v=l7rIfBAF9Cc

-------------------------------------------------------------------------------
Requirements:
-------------------------------------------------------------------------------
1. Windows 10/11 x64
2. Java 25 (e.g. Eclipse Temurin 25 or Oracle JDK 25)
3. Minecraft Java 26.3 with Fabric Loader (>= 0.19.5) and Fabric API (0.162.0+26.3)
4. Legally owned Quake III Arena 'pak0.pk3' (Steam, GOG, or CD)

-------------------------------------------------------------------------------
Installation:
-------------------------------------------------------------------------------
Step 1: Install Fabric Mod
- Copy 'mods\quakecraft-bridge-$Version.jar' into your Minecraft 'mods' folder
  (e.g., .minecraft\mods or PrismLauncher\instances\<instance>\minecraft\mods).

Step 2: Add Mandatory JVM Argument
- In your Minecraft launcher (PrismLauncher, Modrinth, CurseForge, or Official),
  open Java Settings / JVM Arguments and add:
    --enable-native-access=ALL-UNNAMED
  (Required by Java 25 Foreign Function & Memory API for shared memory).

Step 3: Setup Quake 3 Assets
- Place your retail 'pak0.pk3' into:
    quake3\baseq3\pak0.pk3

Step 4: Launch
1. Double click 'quake3\start_quake3.bat'
2. Launch Minecraft 26.3 Fabric
3. Enter any world (Singleplayer or LAN)!

-------------------------------------------------------------------------------
In-Game Controls:
-------------------------------------------------------------------------------
- 1 .. 9 : Select Quake 3 Weapon (Gauntlet .. BFG10K)
- Space  : Jump / Auto-Hop Bunny Jump (adapted 34-unit step height)
- F5     : Toggle 1st-person weapon / 3rd-person Sarge MD3 model
- F6     : Toggle Quake 3 Combat Mode <-> Minecraft Build/GUI Interact
- F7     : Toggle QuakeCraft Passthrough ON / OFF
- F8     : Cheat Code: Quad Damage + MegaHealth + Red Armor + Battle Suit + Full Ammo
- E      : Open Minecraft Inventory (Quake yields mouse/keyboard control)
- Esc    : Pause menu (pauses sound and input)
===============================================================================
"@
[System.IO.File]::WriteAllText("$StageDir\README.txt", $ReadmeContent, [System.Text.Encoding]::UTF8)

# 8. Create Distribution ZIP
Write-Host "Compressing release archive: $ZipFile..." -ForegroundColor Yellow
if (Get-Command Compress-Archive -ErrorAction SilentlyContinue) {
    Compress-Archive -Path "$StageDir\*" -DestinationPath $ZipFile -CompressionLevel Optimal
} else {
    & tar -a -c -f $ZipFile -C $StageDir .
}

$ZipSize = (Get-Item $ZipFile).Length / 1MB
Write-Host ("[SUCCESS] Created Release ZIP: {0} ({1:N2} MB)" -f $ZipFile, $ZipSize) -ForegroundColor Green

# 9. Run Universal-Modder Pre-Release Check
Write-Host "Running universal-modder publish check on staging directory..." -ForegroundColor Cyan
Invoke-Um publish check "$StageDir"
Write-Host "=== Packaging Complete & Verified! ===" -ForegroundColor Green
