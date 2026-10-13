$ErrorActionPreference = "Stop"

$Root = "C:\QuakeCraft"
$JavaHome = "C:\Program Files\Java\jdk-25.0.4"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$Java = Join-Path $JavaHome "bin\java.exe"
$Gcc = "C:\QuakeCraft\Tools\w64devkit\bin\gcc.exe"
$TestBuildDir = Join-Path $Root "build\test-classes"
New-Item -ItemType Directory -Force -Path $TestBuildDir | Out-Null

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " [1/5] Verifying C11 Shared Memory Layout (_Static_assert)  " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
$TestLayoutExe = Join-Path $TestBuildDir "test_protocol_layout.exe"
& $Gcc -std=c11 -O2 "$Root\protocol\test_protocol_layout.c" -o $TestLayoutExe
if ($LASTEXITCODE -ne 0) { throw "test_protocol_layout.c compilation failed" }
& $TestLayoutExe
if ($LASTEXITCODE -ne 0) { throw "test_protocol_layout.exe failed" }

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " [2/5] Compiling Java 25 FFM Mechanical Oracles             " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
& $Javac -d $TestBuildDir `
    "$Root\mc-bridge\src\main\java\dev\quakecraft\bridge\SharedMemoryBridge.java" `
    "$Root\protocol\TestCrossProcessShm.java" `
    "$Root\protocol\TestSlice1Physics.java" `
    "$Root\protocol\TestSlice2FrameCapture.java" `
    "$Root\protocol\TestSlice3Combat.java"
if ($LASTEXITCODE -ne 0) { throw "Java oracle compilation failed" }

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " [3/5] Running Slice 1 Oracle (Voxel Collision & PMove)     " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
& $Java --enable-native-access=ALL-UNNAMED -cp $TestBuildDir dev.quakecraft.bridge.TestSlice1Physics
if ($LASTEXITCODE -ne 0) { throw "TestSlice1Physics failed" }

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " [4/5] Running Slice 2 Oracle (RGBA8 + R32F Depth + HUD)    " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
& $Java --enable-native-access=ALL-UNNAMED -cp $TestBuildDir dev.quakecraft.bridge.TestSlice2FrameCapture
if ($LASTEXITCODE -ne 0) { throw "TestSlice2FrameCapture failed" }

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " [5/5] Running Slice 3 Oracle (Two-Way Combat & Explosions) " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
& $Java --enable-native-access=ALL-UNNAMED -cp $TestBuildDir dev.quakecraft.bridge.TestSlice3Combat
if ($LASTEXITCODE -ne 0) { throw "TestSlice3Combat failed" }

Write-Host "`n[ALL ORACLES PASSED] QuakeCraft Passthrough Bridge verified!" -ForegroundColor Green
