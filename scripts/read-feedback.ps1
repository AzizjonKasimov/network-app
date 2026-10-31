#Requires -Version 7
<#
.SYNOPSIS
    Decrypt the assistant reports out of the private encrypted backup.

.DESCRIPTION
    Reports collected in the app travel inside the ordinary encrypted backup, so
    getting them onto this machine is just: back up on the phone, then run this.

    Only the feedback is written out. The people, interactions, needs,
    capabilities, positions, and background records in the same backup are
    decrypted in memory to reach it and are never saved to disk.

    The passphrase is typed at the prompt, or in a small window when the script
    runs without a console, and held only in memory. It is never written to a
    file, passed as an argument, echoed, or stored in shell history. Nothing is
    uploaded and the backup itself is left untouched.

.EXAMPLE
    .\scripts\read-feedback.ps1
#>
[CmdletBinding()]
param(
    [string]$Owner = 'AzizjonKasimov',
    [string]$Repo = 'network-app-data',
    [string]$Branch = 'main',
    [string]$Path = 'network-backup.enc.json',
    [string]$OutFile = 'assistant-feedback.json',
    # Decrypt an envelope already on this machine instead of fetching one.
    [string]$EnvelopeFile
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\NetworkBackup.ps1')

$envelope = Get-NetworkBackupEnvelope -Owner $Owner -Repo $Repo -Branch $Branch -Path $Path -EnvelopeFile $EnvelopeFile
$secure = Read-NetworkBackupPassphrase -Purpose 'Read the assistant reports out of the network backup.'
try {
    $payload = Unprotect-NetworkBackup -Envelope $envelope -Passphrase $secure
} finally {
    $secure.Dispose()
}

$reports = @($payload.feedback)
$outPath = Join-Path $projectRoot $OutFile
$document = [ordered]@{
    source        = if ($EnvelopeFile) { $EnvelopeFile } else { "$Owner/$Repo@$Branch/$Path" }
    decryptedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    schemaVersion = $payload.schemaVersion
    backedUpAt    = if ($payload.exportedAt) { [DateTimeOffset]::FromUnixTimeMilliseconds([long]$payload.exportedAt).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ') } else { $null }
    reportCount   = $reports.Count
    reports       = $reports
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($outPath, ($document | ConvertTo-Json -Depth 8), $utf8NoBom)

if ($reports.Count -eq 0) {
    Write-Host 'No reports in the latest backup.' -ForegroundColor Green
} else {
    Write-Host "==> $($reports.Count) report(s) by label" -ForegroundColor Cyan
    $reports | Group-Object label | Sort-Object Count -Descending |
        ForEach-Object { Write-Host ("    {0,-22} {1}" -f $_.Name, $_.Count) }
}
Write-Host "Wrote $outPath (gitignored; it quotes real conversations)." -ForegroundColor Green
