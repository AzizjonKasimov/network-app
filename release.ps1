#Requires -Version 5
<#
.SYNOPSIS
    Build, sign, and publish a Network App release with its updater manifest.

.EXAMPLE
    .\release.ps1 -VersionName 0.1.0 -VersionCode 1 -Notes "Initial release"
#>
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$Notes = "Bug fixes and improvements."
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }

$githubOwner = 'AzizjonKasimov'
$releasesRepo = "$githubOwner/network-app-releases"
$releasesDir = Join-Path (Split-Path $projectRoot -Parent) 'network-app-releases'
$apkName = "NetworkApp-$VersionName.apk"
$apkUrl = "https://github.com/$releasesRepo/releases/download/v$VersionName/$apkName"
$signingFingerprintPath = Join-Path $projectRoot 'release-signing-cert.sha256'

if ($VersionName -notmatch '^\d+\.\d+\.\d+([-.][A-Za-z0-9.-]+)?$') {
    throw 'VersionName must look like 1.2.3 or 1.2.3-beta.1.'
}

$activeAccount = gh api user --jq '.login'
if ($LASTEXITCODE -ne 0 -or $activeAccount.Trim() -ne $githubOwner) {
    throw "GitHub CLI must be authenticated as $githubOwner."
}

foreach ($required in @('release.keystore', 'keystore.properties')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $required))) {
        throw "$required is missing. Configure and securely back up release signing before publishing."
    }
}

$repoVisibility = gh repo view $releasesRepo --json visibility --jq '.visibility'
if ($LASTEXITCODE -ne 0) { throw "GitHub releases repository $releasesRepo was not found." }
if ($repoVisibility.Trim() -ne 'PUBLIC') { throw "$releasesRepo must remain public for in-app update checks." }
gh release view "v$VersionName" --repo $releasesRepo *> $null
if ($LASTEXITCODE -eq 0) { throw "Release v$VersionName already exists." }

if (-not (Test-Path -LiteralPath (Join-Path $releasesDir '.git'))) {
    if ((Test-Path -LiteralPath $releasesDir) -and (Get-ChildItem -LiteralPath $releasesDir -Force)) {
        throw "$releasesDir exists but is not a Git checkout. Move it aside before publishing."
    }
    gh repo clone $releasesRepo $releasesDir
    if ($LASTEXITCODE -ne 0) { throw "Could not clone $releasesRepo." }
} else {
    git -C $releasesDir pull --ff-only --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Could not update the local releases checkout.' }
}

$manifestPath = Join-Path $releasesDir 'version.json'
if (Test-Path -LiteralPath $manifestPath) {
    try {
        $publishedManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $publishedCode = [int]$publishedManifest.versionCode
    } catch {
        throw 'The published version.json is invalid. Repair it before creating another release.'
    }
    if ($VersionCode -le $publishedCode) {
        throw "VersionCode must be greater than the published code $publishedCode."
    }
}

$gradleFile = Join-Path $projectRoot 'app\build.gradle.kts'
$content = Get-Content -LiteralPath $gradleFile -Raw
$currentCodeMatch = [regex]::Match($content, 'versionCode = (\d+)')
if (-not $currentCodeMatch.Success) { throw 'Could not find the current versionCode.' }
$currentCode = [int]$currentCodeMatch.Groups[1].Value
if ($VersionCode -lt $currentCode) { throw "VersionCode must not go backwards (current: $currentCode)." }

Write-Host "==> Setting version $VersionName (code $VersionCode)" -ForegroundColor Cyan
$content = [regex]::Replace($content, 'versionCode = \d+', "versionCode = $VersionCode")
$content = [regex]::Replace($content, 'versionName = "[^"]*"', "versionName = `"$VersionName`"")
Set-Content -LiteralPath $gradleFile -Value $content -NoNewline

Write-Host '==> Building signed release APK' -ForegroundColor Cyan
& (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Gradle release build failed.' }
$built = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $built)) { throw "Signed APK was not produced at $built." }
$named = Join-Path $projectRoot "app\build\outputs\apk\release\$apkName"
Copy-Item -LiteralPath $built -Destination $named -Force
Copy-Item -LiteralPath $named -Destination (Join-Path $projectRoot $apkName) -Force
$apkSizeBytes = (Get-Item -LiteralPath $named).Length
$apkSha256 = (Get-FileHash -LiteralPath $named -Algorithm SHA256).Hash.ToLowerInvariant()

$apksigner = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter 'apksigner.bat' |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1
if (-not $apksigner) { throw 'Android apksigner.bat was not found.' }
$signatureOutput = & $apksigner.FullName verify --verbose --print-certs $named
if ($LASTEXITCODE -ne 0) { throw 'The release APK signature could not be verified.' }
$fingerprintMatch = [regex]::Match(($signatureOutput -join "`n"), 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
if (-not $fingerprintMatch.Success) { throw 'Could not read the release signing certificate fingerprint.' }
$signingCertificateSha256 = $fingerprintMatch.Groups[1].Value.ToLowerInvariant()
if (Test-Path -LiteralPath $signingFingerprintPath) {
    $expectedFingerprint = (Get-Content -LiteralPath $signingFingerprintPath -Raw).Trim().ToLowerInvariant()
    if ($signingCertificateSha256 -ne $expectedFingerprint) {
        throw 'Release signing certificate changed. Refusing to publish an incompatible update.'
    }
} else {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($signingFingerprintPath, "$signingCertificateSha256`n", $utf8NoBom)
}

Write-Host "==> Publishing v$VersionName" -ForegroundColor Cyan
gh release create "v$VersionName" $named --repo $releasesRepo --title "v$VersionName" --notes $Notes --latest
if ($LASTEXITCODE -ne 0) { throw 'GitHub release creation failed.' }

Write-Host '==> Updating version.json' -ForegroundColor Cyan
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl = $apkUrl
    apkSizeBytes = $apkSizeBytes
    sha256 = $apkSha256
    signingCertificateSha256 = $signingCertificateSha256
    notes = $Notes
} | ConvertTo-Json
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, $manifest, $utf8NoBom)
if (git -C $releasesDir status --short version.json) {
    git -C $releasesDir add version.json
    git -C $releasesDir commit -m "Release v$VersionName (code $VersionCode)" | Out-Null
    git -C $releasesDir push --quiet
}

Write-Host "Done. v$VersionName is available to the in-app updater." -ForegroundColor Green
