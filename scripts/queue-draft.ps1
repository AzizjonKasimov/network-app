#Requires -Version 7.4
<#
.SYNOPSIS
    Queue a draft for Network App's Assistant, encrypted so that only the phone can read it.

.DESCRIPTION
    Sessions on this PC use this to hand an update to the phone without copy and
    paste. The draft is encrypted here to the public key the phone published in
    the private data repository, uploaded beside the backup, and taken by the
    phone the next time Network App opens. The phone shows it on the Assistant
    tab and sends nothing until the user taps Send.

    The draft comes from the pipeline or from -Path, never from a command-line
    argument, and it is never printed, logged, or written anywhere else. No
    secret is kept on this PC: only the fingerprint of the phone's key, pinned
    the first time so that a swapped key is refused. The backup passphrase is
    never involved.

.EXAMPLE
    @'
    On 2026-10-21 at the Daejeon Global Tech Coffee meetup I talked with Jane Doe (product designer at Brightline). ...
    '@ | .\scripts\queue-draft.ps1

.EXAMPLE
    .\scripts\queue-draft.ps1 -Path draft.txt

.EXAMPLE
    .\scripts\queue-draft.ps1 -TrustNewKey

    For the user, in a terminal, after the phone's key changed (drafts from the
    PC were turned off and on, or the app was reinstalled): shows the new key to
    compare with the phone's Settings and pins it on a typed yes.
#>
[CmdletBinding(DefaultParameterSetName = 'Queue')]
param(
    # One draft, piped in. Several piped lines are joined into one draft.
    [Parameter(ParameterSetName = 'Queue', ValueFromPipeline = $true)]
    [AllowEmptyString()]
    [string]$Draft,

    # A UTF-8 text file holding one draft.
    [Parameter(ParameterSetName = 'Queue')]
    [string]$Path,

    [Parameter(ParameterSetName = 'Trust', Mandatory = $true)]
    [switch]$TrustNewKey,

    [string]$Owner = 'AzizjonKasimov',
    [string]$Repo = 'network-app-data',
    [string]$Branch = 'main',
    # Settings' Backup path on the phone, which names the inbox folder.
    [string]$BackupPath = 'network-backup.enc.json',

    # Offline, for tests: encrypt to a local key.json and write the envelope to
    # -OutFile, touching nothing on GitHub.
    [Parameter(ParameterSetName = 'Queue')]
    [string]$PublicKeyFile,
    [Parameter(ParameterSetName = 'Queue')]
    [string]$OutFile
)

begin {
    $ErrorActionPreference = 'Stop'
    # For Assert-NetworkDataRepository, the check the lookup scripts make too.
    . (Join-Path $PSScriptRoot 'lib\NetworkBackup.ps1')
    # The Assistant's limit, AgentClient.MAX_INPUT_CHARACTERS in the app.
    $maxCharacters = 4000
    $label = 'network-app-inbox-draft:1'
    $pinFile = Join-Path $env:LOCALAPPDATA 'NetworkAppInbox\pinned-keys.json'
    $pieces = [System.Collections.Generic.List[string]]::new()

    # Mirrors PcInbox.folder in the app: an emulator pointed at a test backup
    # gets its own inbox.
    function Get-InboxFolder([string]$backupPath) {
        $path = $backupPath.Trim().TrimStart('/')
        if ($path.EndsWith('.enc.json')) {
            $path = $path.Substring(0, $path.Length - '.enc.json'.Length)
        } elseif ($path.EndsWith('.json')) {
            $path = $path.Substring(0, $path.Length - '.json'.Length)
        }
        "$path-inbox"
    }

    function Get-KeyFingerprint([byte[]]$spki) {
        $hex = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($spki)).ToLowerInvariant()
        $id = $hex.Substring(0, 16)
        [pscustomobject]@{
            Full    = $hex
            Id      = $id
            Display = (($id.ToUpperInvariant() -split '(.{4})' | Where-Object { $_ }) -join ' ')
        }
    }

    function Read-KeyDocument([string]$document) {
        $key = $document | ConvertFrom-Json
        if ($key.format -ne 'network-app-inbox-key' -or $key.version -ne 1) {
            throw 'The inbox key was written by a newer or unknown version of Network App. Update this checkout.'
        }
        [byte[]]$spki = [Convert]::FromBase64String($key.publicKey)
        if ((Get-KeyFingerprint $spki).Id -ne $key.keyId) {
            throw 'The inbox key file does not add up. Turn Drafts from your PC off and on in Network App.'
        }
        return , $spki
    }

    # Runs gh and keeps its error text apart, since a 404 and a 409 each mean something here.
    function Invoke-GitHub([string[]]$Arguments, [string]$Body) {
        $records = if ($PSBoundParameters.ContainsKey('Body')) { $Body | gh @Arguments 2>&1 } else { gh @Arguments 2>&1 }
        $code = $LASTEXITCODE
        $errors = @($records | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
        $lines = @($records | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
        [pscustomobject]@{ Code = $code; Text = $lines -join "`n"; Error = ($errors -join ' ').Trim() }
    }

    function Get-PhoneKey([string]$folder) {
        $result = Invoke-GitHub @('api', "repos/$Owner/$Repo/contents/$folder/key.json?ref=$Branch", '-H', 'Accept: application/vnd.github.raw')
        if ($result.Code -ne 0) {
            if ($result.Error -match 'HTTP 404') {
                throw "There is no inbox key in $Owner/$Repo/$folder. Turn on Settings > Drafts from your PC in Network App first."
            }
            throw "Could not read the phone's inbox key: $($result.Error)"
        }
        return , (Read-KeyDocument $result.Text)
    }

    function Read-Pins {
        if (-not (Test-Path -LiteralPath $pinFile)) { return @{} }
        $pins = Get-Content -LiteralPath $pinFile -Raw | ConvertFrom-Json -AsHashtable
        if ($pins) { $pins } else { @{} }
    }

    function Save-Pin([string]$name, [string]$hash) {
        $pins = Read-Pins
        $pins[$name] = $hash
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $pinFile) | Out-Null
        [System.IO.File]::WriteAllText($pinFile, ($pins | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    }

    # A one-time P-256 key per draft, ECDH with the phone's key, SHA-256 over the
    # label, the shared secret, and both public keys, then AES-256-GCM: the same
    # steps InboxCodec.open repeats on the phone.
    function Protect-Draft([string]$text, [byte[]]$recipientSpki) {
        $recipient = [System.Security.Cryptography.ECDiffieHellman]::Create()
        $ephemeral = [System.Security.Cryptography.ECDiffieHellman]::Create([System.Security.Cryptography.ECCurve+NamedCurves]::nistP256)
        $key = $null
        $plain = $null
        try {
            $read = 0
            $recipient.ImportSubjectPublicKeyInfo($recipientSpki, [ref]$read)
            if ($recipient.KeySize -ne 256) { throw 'The phone key is not a P-256 key.' }
            [byte[]]$ephemeralSpki = $ephemeral.ExportSubjectPublicKeyInfo()
            $labelBytes = [Text.Encoding]::UTF8.GetBytes($label)
            $key = $ephemeral.DeriveKeyFromHash(
                $recipient.PublicKey, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
                $labelBytes, [byte[]]($ephemeralSpki + $recipientSpki))
            $id = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
            $queuedAt = [DateTimeOffset]::UtcNow
            $plain = [Text.Encoding]::UTF8.GetBytes(
                ([ordered]@{ id = $id; text = $text; queuedAt = $queuedAt.ToUnixTimeMilliseconds() } | ConvertTo-Json -Compress))
            $iv = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
            $cipher = [byte[]]::new($plain.Length)
            $tag = [byte[]]::new(16)
            $aes = [System.Security.Cryptography.AesGcm]::new($key, 16)
            try { $aes.Encrypt($iv, $plain, $cipher, $tag, $labelBytes) } finally { $aes.Dispose() }
            [pscustomobject]@{
                Id       = $id
                QueuedAt = $queuedAt
                # Java appends the GCM tag to the ciphertext, so it travels that way.
                Envelope = [ordered]@{
                    format       = 'network-app-inbox-draft'
                    version      = 1
                    keyId        = (Get-KeyFingerprint $recipientSpki).Id
                    ephemeralKey = [Convert]::ToBase64String($ephemeralSpki)
                    iv           = [Convert]::ToBase64String($iv)
                    ciphertext   = [Convert]::ToBase64String([byte[]]($cipher + $tag))
                } | ConvertTo-Json
            }
        } finally {
            if ($key) { [Array]::Clear($key) }
            if ($plain) { [Array]::Clear($plain) }
            $recipient.Dispose()
            $ephemeral.Dispose()
        }
    }
}

process {
    # An unbound [string] is '', so only count what was really piped; blank piped lines are kept.
    if ($PSBoundParameters.ContainsKey('Draft')) { $pieces.Add($Draft) }
}

end {
    $folder = Get-InboxFolder $BackupPath
    $pinName = "$Owner/$Repo/$folder"

    if ($TrustNewKey) {
        # Read-Host would accept piped input, which would let a session confirm a key it cannot see.
        if ([Console]::IsInputRedirected) {
            throw 'Run -TrustNewKey yourself in a terminal: it asks you to compare the key with the phone.'
        }
        Assert-NetworkDataRepository -Owner $Owner -Repo $Repo -Action 'queue network data to'
        $fingerprint = Get-KeyFingerprint (Get-PhoneKey $folder)
        Write-Host "The phone's inbox key for $pinName is now:  $($fingerprint.Display)"
        Write-Host 'Open Network App > Settings > Drafts from your PC and check that it shows the same key.'
        $answer = Read-Host 'Type yes if it matches'
        if ($answer.Trim() -ne 'yes') {
            Write-Host 'Nothing was changed.'
            return
        }
        Save-Pin $pinName $fingerprint.Full
        Write-Host 'Pinned. Drafts will be encrypted to this key.' -ForegroundColor Green
        return
    }

    if ($Path -and $pieces.Count -gt 0) { throw 'Pipe the draft in or pass -Path, not both.' }
    $text = if ($Path) {
        [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).ProviderPath, [System.Text.UTF8Encoding]::new($false))
    } else {
        $pieces -join "`n"
    }
    $text = ($text -replace "`r`n", "`n" -replace "`r", "`n").Trim()
    if (-not $text) { throw 'The draft is empty. Pipe it in or pass -Path.' }
    if ($text.Length -gt $maxCharacters) {
        throw "The draft is $($text.Length) characters, and the Assistant takes at most $maxCharacters. Split it into several drafts."
    }

    if ($PublicKeyFile) {
        if (-not $OutFile) { throw '-PublicKeyFile needs -OutFile.' }
        $sealed = Protect-Draft $text (Read-KeyDocument (Get-Content -LiteralPath $PublicKeyFile -Raw))
        [System.IO.File]::WriteAllText(
            [System.IO.Path]::GetFullPath($OutFile, (Get-Location).ProviderPath), $sealed.Envelope, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Wrote $OutFile. Nothing was uploaded." -ForegroundColor Green
        return
    }

    Assert-NetworkDataRepository -Owner $Owner -Repo $Repo -Action 'queue network data to'
    [byte[]]$phoneKey = Get-PhoneKey $folder
    $fingerprint = Get-KeyFingerprint $phoneKey
    $pinned = (Read-Pins)[$pinName]
    if (-not $pinned) {
        Save-Pin $pinName $fingerprint.Full
        Write-Host "First draft for this inbox: pinned the phone's key $($fingerprint.Display). Network App > Settings > Drafts from your PC should show the same key." -ForegroundColor Yellow
    } elseif ($pinned -ne $fingerprint.Full) {
        throw ("The phone's inbox key has changed (it is now $($fingerprint.Display)), so nothing was queued. " +
            'If drafts from the PC were turned off and on, or the app reinstalled, run this in a terminal to compare the key with the phone: ' +
            '.\scripts\queue-draft.ps1 -TrustNewKey')
    }

    $sealed = Protect-Draft $text $phoneKey
    $name = '{0}-{1}.enc.json' -f $sealed.QueuedAt.UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'"), $sealed.Id.Substring(0, 8)
    $body = [ordered]@{
        message = 'inbox(pc): draft'
        branch  = $Branch
        content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sealed.Envelope))
    } | ConvertTo-Json -Compress
    for ($attempt = 1; ; $attempt++) {
        $upload = Invoke-GitHub @('api', '-X', 'PUT', "repos/$Owner/$Repo/contents/$folder/$name", '--input', '-', '--jq', '.content.path') $body
        if ($upload.Code -eq 0) { break }
        # The phone commits to the same branch when it backs up or takes a draft; a collision is worth another try.
        if ($attempt -lt 3 -and $upload.Error -match 'HTTP 409') {
            Start-Sleep -Seconds 2
            continue
        }
        throw "Could not upload the draft: $($upload.Error)"
    }
    Write-Host "Queued a $($text.Length)-character draft for the phone as $folder/$name. It waits on the Assistant tab the next time Network App opens." -ForegroundColor Green
}
