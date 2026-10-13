param(
    [switch]$DeployMod = $true,
    [switch]$LaunchQuake = $true
)
$ErrorActionPreference = "Stop"

$Root = "C:\QuakeCraft"
$Q3Dir = Join-Path $Root "build\q3"
$Q3Exe = Join-Path $Q3Dir "quake3-bridge.exe"
$BasePath = Join-Path $Root "Sources\QUAKE3"
$HomePath = Join-Path $Root "lab\q3home"
$JarSource = Join-Path $Root "build\mc\quakecraft-bridge-0.1.0.jar"
$InstanceMods = "C:\Users\victor\AppData\Roaming\PrismLauncher\instances\26.3\minecraft\mods"

if (-not (Test-Path $Q3Exe)) {
    Write-Host "Building Quake 3 engine & native DLLs..." -ForegroundColor Yellow
    & "$Root\scripts\build_q3.ps1"
}
if (-not (Test-Path $JarSource)) {
    Write-Host "Building Minecraft 26.3 Fabric transceiver mod..." -ForegroundColor Yellow
    & "$Root\scripts\build_mc.ps1"
}

if ($DeployMod) {
    if (Test-Path $InstanceMods) {
        Copy-Item -Path $JarSource -Destination (Join-Path $InstanceMods "quakecraft-bridge-0.1.0.jar") -Force
        Write-Host "[OK] Deployed quakecraft-bridge-0.1.0.jar to $InstanceMods" -ForegroundColor Green
    } else {
        Write-Warning "PrismLauncher 26.3 mods directory not found at $InstanceMods"
    }
}

if ($LaunchQuake) {
    New-Item -ItemType Directory -Force -Path $HomePath | Out-Null
    $Q3Args = @(
        "+set", "fs_basepath", "`"$BasePath`"",
        "+set", "fs_homepath", "`"$HomePath`"",
        "+set", "vm_game", "0",
        "+set", "vm_cgame", "0",
        "+set", "vm_ui", "0",
        "+set", "sv_pure", "0",
        "+set", "r_fullscreen", "0",
        "+set", "r_mode", "-1",
        "+set", "r_customwidth", "854",
        "+set", "r_customheight", "480",
        "+set", "in_mouse", "0",
        "+set", "bot_enable", "0",
        "+set", "com_maxfps", "60",
        "+set", "pmove_fixed", "1",
        "+set", "pmove_msec", "8",
        "+devmap", "q3dm1"
    )
    $Proc = Start-Process -FilePath $Q3Exe -ArgumentList $Q3Args -WorkingDirectory $Q3Dir -PassThru
    Write-Host "[OK] Launched quake3-bridge.exe (PID=$($Proc.Id)) attached to Local\QuakeCraftBridgeV1" -ForegroundColor Green
    Write-Host "Now launch Minecraft 26.3 from PrismLauncher (make sure JVM arguments include --enable-native-access=ALL-UNNAMED)." -ForegroundColor Cyan
    Write-Host "Hotkeys in Minecraft:" -ForegroundColor Cyan
    Write-Host "  1..9 : Select Quake III Weapon (Gauntlet .. BFG10K)"
    Write-Host "  F5   : Toggle 1st-person weapon / 3rd-person Sarge MD3 model"
    Write-Host "  F6   : Toggle Quake III Combat <-> Minecraft Block/GUI Interact"
    Write-Host "  F7   : Toggle QuakeCraft Passthrough ON / OFF"
    Write-Host "  F8   : Grant Quad Damage + MegaHealth + Red Armor + Full Ammo"
}
