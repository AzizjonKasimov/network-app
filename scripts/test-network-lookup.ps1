#Requires -Version 7
<#
.SYNOPSIS
    Check the network lookup against a made-up encrypted backup.

.DESCRIPTION
    Encrypts a backup of obviously synthetic people with a made-up passphrase, in
    the envelope format the app writes, then decrypts it with lib\NetworkBackup.ps1
    and runs lib\NetworkLookup.ps1 against it: name, other-name, and word-order
    matches, searches with and without archived people, the owner's profile, the
    names-only list for a broad name, the notes limit, a wrong passphrase, and
    that no contact value or assistant report is ever printed.

    It needs no GitHub access or real data, opens no window, and deletes its
    temporary envelope at the end. Exits 1 when a check fails.

.EXAMPLE
    .\scripts\test-network-lookup.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\NetworkBackup.ps1')
. (Join-Path $PSScriptRoot 'lib\NetworkLookup.ps1')

function New-TestEnvelope([string]$PayloadJson, [string]$Passphrase) {
    # The envelope EncryptedBackupCodec writes: PBKDF2-HMAC-SHA256, then AES-256-GCM with its tag appended.
    $salt = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $iv = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
    $iterations = 210000
    $kdf = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
        $Passphrase, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    try { $key = $kdf.GetBytes(32) } finally { $kdf.Dispose() }
    $plain = [Text.Encoding]::UTF8.GetBytes($PayloadJson)
    $cipher = [byte[]]::new($plain.Length)
    $tag = [byte[]]::new(16)
    $aes = [System.Security.Cryptography.AesGcm]::new($key, 16)
    try {
        $aes.Encrypt($iv, $plain, $cipher, $tag, [Text.Encoding]::UTF8.GetBytes('network-app-encrypted-backup:1'))
    } finally {
        $aes.Dispose()
    }
    $sealed = [byte[]]::new($cipher.Length + $tag.Length)
    [Array]::Copy($cipher, $sealed, $cipher.Length)
    [Array]::Copy($tag, 0, $sealed, $cipher.Length, $tag.Length)
    [ordered]@{
        format = 'network-app-encrypted-backup'; version = 1; kdf = 'PBKDF2WithHmacSHA256'; iterations = $iterations
        salt = [Convert]::ToBase64String($salt); iv = [Convert]::ToBase64String($iv); ciphertext = [Convert]::ToBase64String($sealed)
    } | ConvertTo-Json
}

$day = 86400000
$start = [DateTimeOffset]::new(2026, 9, 1, 9, 0, 0, [TimeSpan]::Zero).ToUnixTimeMilliseconds()
$email = 'synthetic-contact@example.invalid'
$phone = '+00 0000 0000'
$reportText = 'Synthetic report text that must never be printed'

function New-TestPerson([long]$Id, [string]$Name, [hashtable]$More = @{}) {
    $person = [ordered]@{
        id = $Id; name = $Name; location = ''; contact = ''; relationship = ''; tags = ''; notes = ''
        isSelf = $false; archived = $false; createdAt = $start; updatedAt = $start + $Id * $day; aliases = ''
    }
    foreach ($key in $More.Keys) { $person[$key] = $More[$key] }
    $person
}

$people = @(
    New-TestPerson 1 'Synthetic Me' @{ isSelf = $true; contact = $email }
    New-TestPerson 2 'Synthetic Sara' @{ aliases = "Sara S.`n합성 사라"; location = 'Synthetic City'; contact = $phone; tags = 'design'; notes = "First synthetic profile line.`nSecond synthetic profile line." }
    New-TestPerson 3 'Synthetic Archived' @{ archived = $true }
)
$people += foreach ($letter in 'A', 'B', 'C', 'D', 'E', 'F') { New-TestPerson (10 + [int][char]$letter - 64) "Synthetic Kim $letter" }

$interactions = foreach ($n in 1..12) {
    $text = if ($n -eq 12) { 'Synthetic Sara asked about robotics hiring in Synthetic City.' } else { "Synthetic note $n about Sara." }
    [ordered]@{ id = 100 + $n; personId = 2; note = $text; occurredAt = $start + $n * $day; createdAt = $start + $n * $day; origin = ($n -eq 12 ? 'assistant' : 'manual') }
}
$interactions += [ordered]@{ id = 200; personId = 3; note = 'The archived person once mentioned robotics.'; occurredAt = $start; createdAt = $start; origin = 'manual' }
$longNote = ('Synthetic filler sentence. ' * 15) + 'The synthetic team discussed robotics budgets. ' + ('More synthetic filler. ' * 20)
$interactions += [ordered]@{ id = 300; personId = 12; note = $longNote; occurredAt = $start; createdAt = $start; origin = 'manual' }

$payload = [ordered]@{
    schemaVersion = 9
    exportedAt    = $start + 40 * $day
    people        = $people
    interactions  = $interactions
    needs         = @(
        [ordered]@{ id = 1; personId = 2; text = 'Looking for a robotics mentor'; status = 'active'; lastConfirmedAt = $start + 12 * $day; createdAt = $start; sourceInteractionId = 112; helpedAt = $start + 14 * $day }
        [ordered]@{ id = 2; personId = 1; text = 'Find speakers for a synthetic meetup'; status = 'active'; lastConfirmedAt = $start; createdAt = $start; sourceInteractionId = $null; helpedAt = $null }
    )
    capabilities  = @(
        [ordered]@{ id = 1; personId = 11; text = 'Runs a synthetic robotics lab'; lastConfirmedAt = $start; createdAt = $start; active = $true; sourceInteractionId = $null }
    )
    affiliations  = @(
        [ordered]@{ id = 1; personId = 2; organization = 'Example Labs'; role = 'Synthetic designer'; current = $true; kind = 'work'; lastConfirmedAt = $start; createdAt = $start; sourceInteractionId = $null }
        [ordered]@{ id = 2; personId = 2; organization = 'Example University'; role = 'MSc'; current = $false; kind = 'education'; lastConfirmedAt = $start; createdAt = $start; sourceInteractionId = $null }
    )
    facts         = @(
        [ordered]@{ id = 1; personId = 2; text = 'Moving to Synthetic Town in December.'; lastConfirmedAt = $start + 12 * $day; createdAt = $start; sourceInteractionId = 112 }
    )
    feedback      = @(
        [ordered]@{ id = 1; stage = 'agent'; label = 'other'; note = $reportText; userMessage = $reportText; assistantMessage = $reportText; assistantDetail = ''; appVersion = '0.0.0'; createdAt = $start }
    )
}

$failures = [System.Collections.Generic.List[string]]::new()
$everything = [System.Text.StringBuilder]::new()
function Check([string]$What, [bool]$Passed) {
    if ($Passed) { Write-Host "  ok    $What" -ForegroundColor Green } else { Write-Host "  FAIL  $What" -ForegroundColor Red; $failures.Add($What) }
}
function Lookup([hashtable]$Options) {
    $text = @(Invoke-NetworkLookup -Payload $script:decrypted @Options) -join "`n"
    [void]$everything.AppendLine($text)
    $text
}

$passphrase = 'synthetic-passphrase-for-tests'
$envelopePath = Join-Path ([IO.Path]::GetTempPath()) "network-lookup-test-$([guid]::NewGuid()).json"
try {
    New-TestEnvelope ($payload | ConvertTo-Json -Depth 6) $passphrase | Set-Content -LiteralPath $envelopePath -Encoding utf8NoBOM
    $envelope = Get-NetworkBackupEnvelope -Owner 'unused' -Repo 'unused' -Branch 'unused' -Path 'unused' -EnvelopeFile $envelopePath

    Write-Host '==> Decrypting' -ForegroundColor Cyan
    $script:decrypted = Unprotect-NetworkBackup -Envelope $envelope -Passphrase (ConvertTo-SecureString $passphrase -AsPlainText -Force)
    Check 'decrypts the envelope the app writes' (@($decrypted.people).Count -eq 9)
    $refused = try { Unprotect-NetworkBackup -Envelope $envelope -Passphrase (ConvertTo-SecureString 'not-the-passphrase' -AsPlainText -Force) | Out-Null; '' } catch { $_.Exception.Message }
    Check 'refuses a wrong passphrase' ($refused -like 'Backup passphrase is incorrect*')

    Write-Host '==> Name lookups' -ForegroundColor Cyan
    $sara = Lookup @{ Name = @('Synthetic Sara') }
    Check 'says when the backup was made' ($sara -match 'Backup made \d{4}-\d{2}-\d{2}')
    Check 'shows the profile with other names' ($sara.Contains('== Synthetic Sara') -and $sara.Contains('Other names: Sara S.; 합성 사라'))
    Check 'says a contact is saved without showing it' ($sara.Contains('Contact saved: yes'))
    Check 'shows positions and education' ($sara.Contains('Synthetic designer at Example Labs (current') -and $sara.Contains('MSc at Example University (past'))
    Check 'shows a need with its help date and source note' ($sara -match '\[open\] Looking for a robotics mentor \(confirmed [\d-]+; helped [\d-]+; from the note of [\d-]+\)')
    Check 'keeps a multi-line value under its label' ($sara -match "First synthetic profile line\.\n\s+Second synthetic profile line\.")
    Check 'shows the latest ten notes and says how many more' ($sara.Contains('Notes, newest first (10 of 12; add -AllNotes for the rest)'))
    Check 'labels a note the Assistant saved' ($sara.Contains(', saved by the Assistant: Synthetic Sara asked about robotics hiring'))
    $allNotes = Lookup @{ Name = @('Synthetic Sara'); AllNotes = $true }
    Check '-AllNotes shows every note' (([regex]::Matches($allNotes, 'Synthetic note \d+ about Sara')).Count -eq 11 -and -not $allNotes.Contains('of 12'))
    Check 'finds a person by an other name in Hangul' ((Lookup @{ Name = @('사라') }).Contains('== Synthetic Sara'))
    Check 'finds a person by name words in any order' ((Lookup @{ Name = @('sara synthetic') }).Contains('== Synthetic Sara'))
    $twice = Lookup @{ Name = @('Synthetic Sara', 'Sara S.') }
    Check 'shows a person found twice once' (([regex]::Matches($twice, '== Synthetic Sara')).Count -eq 1)
    $kims = Lookup @{ Name = @('Synthetic Kim') }
    Check 'lists only names when a name matches many people' ($kims.Contains('6 people match ''Synthetic Kim''') -and -not $kims.Contains('Contact saved'))
    Check 'says when nobody matches' ((Lookup @{ Name = @('Nobody Here') }).Contains('No one is saved under a name like ''Nobody Here''.'))
    Check 'marks an archived person' ((Lookup @{ Name = @('Synthetic Archived') }).Contains('== Synthetic Archived (archived)'))

    Write-Host '==> Searches and the owner' -ForegroundColor Cyan
    $search = Lookup @{ Search = 'robotics' }
    Check 'search finds records in needs, notes, and capabilities' ($search.Contains('== Synthetic Sara - Synthetic designer at Example Labs') -and $search.Contains('== Synthetic Kim A') -and $search.Contains('Need or goal: [open] Looking for a robotics mentor'))
    Check 'search ranks the person with most matches first' ($search.IndexOf('== Synthetic Sara') -lt $search.IndexOf('== Synthetic Kim A'))
    $excerpt = @($search -split "`n" | Where-Object { $_.Contains('robotics budgets') })
    Check 'search cuts a long note to the part that matched' ($excerpt.Count -eq 1 -and $excerpt[0] -match ': \.\.\..*robotics budgets.*\.\.\.$' -and $excerpt[0].Length -lt $longNote.Length)
    Check 'search leaves archived people out' (-not $search.Contains('Synthetic Archived') -and $search.Contains('Archived people are left out'))
    Check 'search with -IncludeArchived finds them' ((Lookup @{ Search = 'robotics'; IncludeArchived = $true }).Contains('== Synthetic Archived (archived)'))
    Check 'search needs every word in one record' ((Lookup @{ Search = 'robotics town' }).Contains('Nothing saved matches every word'))
    $me = Lookup @{ Self = $true }
    Check 'shows the owner''s own profile' ($me.Contains('== Synthetic Me (you)') -and $me.Contains('Find speakers for a synthetic meetup'))

    Write-Host '==> Never printed' -ForegroundColor Cyan
    $printed = $everything.ToString()
    Check 'no contact value in any output' (-not $printed.Contains($email) -and -not $printed.Contains($phone))
    Check 'no assistant report in any output' (-not $printed.Contains($reportText))
} finally {
    Remove-Item -LiteralPath $envelopePath -ErrorAction SilentlyContinue
}

if ($failures.Count) {
    Write-Host "$($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All checks passed.' -ForegroundColor Green
