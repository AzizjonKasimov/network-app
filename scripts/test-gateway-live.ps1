[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot ".env"
$localPropertiesPath = Join-Path $projectRoot "local.properties"
$remoteEnvPath = "/data/local/tmp/network-app-gateway-live.env"

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw "Create $envPath with GATEWAY_TOKEN before running the live test."
}

$assignments = @(Get-Content -LiteralPath $envPath | ForEach-Object {
    if ($_ -match "^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$") {
        [pscustomobject]@{ Name = $Matches[1]; Value = $Matches[2].Trim().Trim('"').Trim("'") }
    }
})
if ($assignments.Count -ne 1 -or $assignments[0].Name -ne "GATEWAY_TOKEN") {
    throw ".env must contain only GATEWAY_TOKEN for this temporary on-device test."
}
$key = $assignments[0].Value
if ($key.Length -lt 20 -or $key -notmatch "^[A-Za-z0-9_.-]+$") {
    throw "GATEWAY_TOKEN is missing or malformed."
}
$key = $null
$assignments = $null

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

try {
    & $adb -s $serial push $envPath $remoteEnvPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not stage the temporary live-test key file." }
    & $adb -s $serial shell chmod 600 $remoteEnvPath
    if ($LASTEXITCODE -ne 0) { throw "Could not protect the temporary live-test key file." }

    Push-Location $projectRoot
    try {
        & .\gradlew.bat connectedDebugAndroidTest `
            "-Pandroid.testInstrumentationRunnerArguments.class=com.azizjon.network.ai.GatewayLiveApiInstrumentedTest" `
            "-Pandroid.testInstrumentationRunnerArguments.liveGateway=true" `
            --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Live gateway instrumentation test failed." }
    } finally {
        Pop-Location
    }
} finally {
    & $adb -s $serial shell rm -f $remoteEnvPath | Out-Null
}

Write-Host "Live gateway capture and search test passed; the temporary device token file was removed."
