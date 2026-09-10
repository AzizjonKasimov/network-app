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

    The passphrase is typed at the prompt and held only in memory. It is never
    written to a file, passed as an argument, echoed, or stored in shell
    history. Nothing is uploaded and the backup itself is left untouched.

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

if ($EnvelopeFile) {
    if (-not (Test-Path -LiteralPath $EnvelopeFile)) { throw "$EnvelopeFile was not found." }
    Write-Host "==> Reading $EnvelopeFile" -ForegroundColor Cyan
    $envelopeText = Get-Content -LiteralPath $EnvelopeFile -Raw
} else {

$account = (gh api user --jq '.login' 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated. Run: gh auth login' }
if ($account.Trim() -ne $Owner) { throw "GitHub CLI is authenticated as $account, not $Owner." }

$visibility = (gh repo view "$Owner/$Repo" --json visibility --jq '.visibility' 2>$null)
if ($LASTEXITCODE -ne 0) { throw "Backup repository $Owner/$Repo was not found." }
if ($visibility.Trim() -ne 'PRIVATE') {
    throw "$Owner/$Repo is $visibility. Refusing to read network data from a repository that is not private."
}

Write-Host "==> Fetching $Path from $Owner/$Repo@$Branch" -ForegroundColor Cyan
# Must be requested raw. Decoding the base64 .content through a PowerShell
# pipeline splits it per line and decodes each chunk separately, which
# fabricates newlines inside every value.
$envelopeText = gh api "repos/$Owner/$Repo/contents/${Path}?ref=$Branch" -H 'Accept: application/vnd.github.raw'
if ($LASTEXITCODE -ne 0) { throw "Could not download $Path. Has the phone backed up yet?" }

}

$envelope = $envelopeText | ConvertFrom-Json
if ($envelope.format -ne 'network-app-encrypted-backup' -or $envelope.version -ne 1) {
    throw 'Unrecognised backup envelope. This script is older or newer than the app that wrote it.'
}

$secure = Read-Host -AsSecureString -Prompt 'Backup passphrase'
$passphrase = [System.Net.NetworkCredential]::new('', $secure).Password
if ([string]::IsNullOrWhiteSpace($passphrase)) { throw 'No passphrase entered.' }

try {
    $salt = [Convert]::FromBase64String($envelope.salt)
    $iv = [Convert]::FromBase64String($envelope.iv)
    $sealed = [Convert]::FromBase64String($envelope.ciphertext)
    $iterations = [int]$envelope.iterations
    if ($iterations -lt 100000 -or $iterations -gt 1000000) { throw 'Invalid backup key settings.' }

    # Java's AES/GCM appends the 16-byte tag to the ciphertext; .NET wants them apart.
    $tagLength = 16
    $cipherText = New-Object byte[] ($sealed.Length - $tagLength)
    $tag = New-Object byte[] $tagLength
    [Array]::Copy($sealed, 0, $cipherText, 0, $cipherText.Length)
    [Array]::Copy($sealed, $cipherText.Length, $tag, 0, $tagLength)

    $kdf = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
        $passphrase, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    try { $key = $kdf.GetBytes(32) } finally { $kdf.Dispose() }

    try {
        $aes = [System.Security.Cryptography.AesGcm]::new($key, $tagLength)
    } catch [System.Management.Automation.MethodException] {
        $aes = [System.Security.Cryptography.AesGcm]::new($key)
    }
    $plainBytes = New-Object byte[] $cipherText.Length
    try {
        $aad = [Text.Encoding]::UTF8.GetBytes('network-app-encrypted-backup:1')
        $aes.Decrypt($iv, $cipherText, $tag, $plainBytes, $aad)
    } catch {
        throw 'Backup passphrase is incorrect, or the backup is damaged.'
    } finally {
        $aes.Dispose()
        [Array]::Clear($key, 0, $key.Length)
    }

    $payload = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
    [Array]::Clear($plainBytes, 0, $plainBytes.Length)
} finally {
    $passphrase = $null
    $secure.Dispose()
    [GC]::Collect()
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
