# QuakeCraft — Build Minecraft 26.3 Fabric Transceiver (mc-bridge)
# Strictly isolated inside C:\QuakeCraft\.build-cache\gradle

$ErrorActionPreference = "Stop"
. "C:\QuakeCraft\scripts\env.ps1"

Push-Location "C:\QuakeCraft\mc-bridge"
try {
    & "C:\QuakeCraft\Tools\gradle-9.8.0-bin\gradle-9.8.0\bin\gradle.bat" `
        --no-daemon `
        --gradle-user-home "C:\QuakeCraft\.build-cache\gradle" `
        build
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
    $OutJarDir = "C:\QuakeCraft\build\mc"
    New-Item -ItemType Directory -Force -Path $OutJarDir | Out-Null
    Copy-Item -Force "C:\QuakeCraft\mc-bridge\build\libs\quakecraft-bridge-0.1.0.jar" "$OutJarDir\quakecraft-bridge-0.1.0.jar"
    Write-Host "Built $OutJarDir\quakecraft-bridge-0.1.0.jar ($((Get-Item "$OutJarDir\quakecraft-bridge-0.1.0.jar").Length) bytes)" -ForegroundColor Green
} finally {
    Pop-Location
}
