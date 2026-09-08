[CmdletBinding()]
param()

# Opt-in live gateway smoke test.
#
# The token is never handled by this script. It is read at runtime from the
# app's own encrypted preferences, so it must be saved once on the device via
# Settings -> AI gateway. Nothing is pushed to the device and nothing is passed
# through Gradle arguments, command text, or test reports.
#
# `connectedDebugAndroidTest` is deliberately NOT used: it uninstalls the app
# when it finishes, which erases the saved token and forces it to be entered
# again before every run. Installing and then driving `am instrument` directly
# leaves both APKs in place, so the token survives.

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$localPropertiesPath = Join-Path $projectRoot "local.properties"

$testClass = "com.azizjon.network.ai.GatewayLiveApiInstrumentedTest"
$testRunner = "com.azizjon.network.test/androidx.test.runner.AndroidJUnitRunner"

$sdkLine = Get-Content -LiteralPath $localPropertiesPath |
    Where-Object { $_ -match "^sdk\.dir=" } |
    Select-Object -First 1
if (-not $sdkLine) { throw "local.properties does not define sdk.dir." }
$sdkDir = ($sdkLine -replace "^sdk\.dir=", "") -replace "\\:", ":" -replace "\\\\", "\"
$adb = Join-Path $sdkDir "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb.exe was not found under sdk.dir." }

$devices = @(& $adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($devices.Count -ne 1) {
    throw "Start exactly one Android emulator or connected test device before running the live gateway test."
}
$serial = $devices[0]

Push-Location $projectRoot
try {
    & .\gradlew.bat installDebug installDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Could not install the app and instrumentation APKs." }

    & $adb -s $serial logcat -c

    Write-Host ""
    Write-Host "Running the live gateway test with synthetic records..."
    $output = & $adb -s $serial shell am instrument -w `
        -e class $testClass `
        -e liveGateway true `
        $testRunner 2>&1
    $report = ($output | Out-String)
    Write-Host $report

    if ($report -match "Save the gateway access token") {
        throw "No access token is saved on $serial. Open the app, go to Settings -> AI gateway, paste the token, tap Save, then run this script again."
    }
    if ($report -match "FAILURES!!!" -or $report -notmatch "OK \(") {
        throw "Live gateway instrumentation test failed."
    }

    Write-Host "Stage timings:"
    & $adb -s $serial logcat -d -s LiveGatewayTiming:I |
        Select-String "took|failed" |
        ForEach-Object { Write-Host "  $($_.Line)" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Live gateway routing, capture, refinement, and search passed."
Write-Host "The app is still installed and the saved token was not touched."
