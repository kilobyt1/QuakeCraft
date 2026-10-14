# =============================================================================
# QuakeCraft -- Upload to GitHub (scripts/upload_to_github.ps1)
# =============================================================================
$ErrorActionPreference = "Stop"

$Owner = "kilobyt1"
$Repo = "QuakeCraft"
$ReleaseTag = "v0.1.0"
$ReleaseName = "QuakeCraft v0.1.0 - True Two-Process Passthrough"
$ZipPath = "C:\QuakeCraft\build\release\QuakeCraft-v0.1.0-windows-x64.zip"
$NotesPath = "C:\QuakeCraft\RELEASE_NOTES.md"

if (-not (Test-Path $ZipPath)) {
    Write-Host "Release zip not found, packaging first..." -ForegroundColor Yellow
    & "C:\QuakeCraft\scripts\package_release.ps1"
}

Write-Host "Getting GitHub credentials from Git Credential Manager..." -ForegroundColor Cyan
$pinfo = New-Object System.Diagnostics.ProcessStartInfo
$pinfo.FileName = "git"
$pinfo.Arguments = "credential fill"
$pinfo.RedirectStandardInput = $true
$pinfo.RedirectStandardOutput = $true
$pinfo.UseShellExecute = $false
$pinfo.CreateNoWindow = $true

$proc = [System.Diagnostics.Process]::Start($pinfo)
$proc.StandardInput.WriteLine("protocol=https")
$proc.StandardInput.WriteLine("host=github.com")
$proc.StandardInput.WriteLine("")
$proc.StandardInput.Close()

$credOutput = $proc.StandardOutput.ReadToEnd()
$proc.WaitForExit()

$Token = ""
foreach ($line in ($credOutput -split "`r?`n")) {
    if ($line -match "^password=(.*)$") {
        $Token = $Matches[1].Trim()
        break
    }
}

if (-not $Token) {
    throw "Failed to obtain GitHub token from git credential manager!"
}

$Headers = @{
    "Authorization" = "token $Token"
    "User-Agent"    = "QuakeCraft-Publisher"
    "Accept"        = "application/vnd.github.v3+json"
}

# 1. Check or create repository
Write-Host "Checking repository $Owner/$Repo..." -ForegroundColor Cyan
try {
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo" -Headers $Headers -Method Get
    Write-Host "[OK] Repository exists: $($existing.html_url)" -ForegroundColor Green
} catch {
    Write-Host "Repository $Owner/$Repo does not exist yet. Creating public repository..." -ForegroundColor Yellow
    $createBody = @{
        name        = $Repo
        description = "True Two-Process Passthrough between Quake III Arena and Minecraft Java 26.3 (Fabric)"
        private     = $false
        has_issues  = $true
        has_wiki    = $false
    } | ConvertTo-Json

    $newRepo = Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Headers $Headers -Method Post -Body $createBody -ContentType "application/json"
    Write-Host "[OK] Created repository: $($newRepo.html_url)" -ForegroundColor Green
}

# 2. Configure git remote and push
Write-Host "Configuring git remote and pushing branch..." -ForegroundColor Cyan
$RemoteUrl = "https://github.com/$Owner/$Repo.git"

$currentRemotes = & git remote
if ($currentRemotes -notcontains "origin") {
    & git remote add origin $RemoteUrl
    Write-Host "[OK] Added remote origin: $RemoteUrl" -ForegroundColor Green
} else {
    & git remote set-url origin $RemoteUrl
    Write-Host "[OK] Updated remote origin: $RemoteUrl" -ForegroundColor Green
}

Write-Host "Pushing code to origin..." -ForegroundColor Yellow
& git push -u origin master
if ($LASTEXITCODE -ne 0) {
    throw "git push failed with code $LASTEXITCODE"
}
Write-Host "[OK] Code successfully pushed to GitHub!" -ForegroundColor Green

# 3. Create GitHub Release
Write-Host "Creating GitHub Release $ReleaseTag..." -ForegroundColor Cyan
$BodyText = [System.IO.File]::ReadAllText($NotesPath, [System.Text.Encoding]::UTF8)

$ReleaseBody = @{
    tag_name         = $ReleaseTag
    target_commitish = "master"
    name             = $ReleaseName
    body             = $BodyText
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json

$ReleaseId = $null
$UploadUrl = $null
try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/tags/$ReleaseTag" -Headers $Headers -Method Get
    $ReleaseId = $rel.id
    $UploadUrl = $rel.upload_url
    Write-Host "[OK] Release $ReleaseTag already exists (ID: $ReleaseId)." -ForegroundColor Yellow
} catch {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Headers $Headers -Method Post -Body $ReleaseBody -ContentType "application/json; charset=utf-8"
    $ReleaseId = $rel.id
    $UploadUrl = $rel.upload_url
    Write-Host "[OK] Created release $ReleaseTag (ID: $ReleaseId)" -ForegroundColor Green
}

# 4. Upload Release Asset (QuakeCraft-v0.1.0-windows-x64.zip)
$AssetName = [System.IO.Path]::GetFileName($ZipPath)
Write-Host "Uploading asset $AssetName to release..." -ForegroundColor Cyan

$RawUploadUrl = $UploadUrl -replace '\{\?name,label\}', ''
$AssetUploadUri = "$RawUploadUrl" + "?name=$AssetName"

try {
    $assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets" -Headers $Headers -Method Get
    foreach ($asset in $assets) {
        if ($asset.name -eq $AssetName) {
            Write-Host "Deleting previous asset (ID: $($asset.id))..." -ForegroundColor Yellow
            Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($asset.id)" -Headers $Headers -Method Delete
            break
        }
    }
} catch {
    # Ignore if error
}

$ZipBytes = [System.IO.File]::ReadAllBytes($ZipPath)
$AssetHeaders = @{
    "Authorization" = "token $Token"
    "User-Agent"    = "QuakeCraft-Publisher"
    "Content-Type"  = "application/zip"
}

$uploaded = Invoke-RestMethod -Uri $AssetUploadUri -Headers $AssetHeaders -Method Post -Body $ZipBytes
$mb = [math]::Round($ZipBytes.Length / 1MB, 2)
Write-Host "[SUCCESS] Uploaded $AssetName ($mb MB) to GitHub Release!" -ForegroundColor Green
Write-Host "Release URL: https://github.com/$Owner/$Repo/releases/tag/$ReleaseTag" -ForegroundColor Green
