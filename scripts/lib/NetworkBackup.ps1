# Reading the app's encrypted GitHub backup on this machine, shared by
# read-feedback.ps1 and network-lookup.ps1. queue-draft.ps1 uses only its check
# of the data repository. Dot-source it.
#
# The backup is decrypted in memory only. The passphrase is typed by the owner
# every time and is never written to a file, passed as an argument, echoed, or
# stored in shell history.

function Assert-NetworkDataRepository {
    <# Checks that gh is signed in as the owner and that the data repository is private. #>
    param(
        [Parameter(Mandatory)][string]$Owner,
        [Parameter(Mandatory)][string]$Repo,
        # What the caller does with network data, for the refusal.
        [string]$Action = 'read network data from'
    )
    $account = (gh api user --jq '.login' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated. Run: gh auth login' }
    if ($account.Trim() -ne $Owner) { throw "GitHub CLI is authenticated as $account, not $Owner." }

    $visibility = (gh repo view "$Owner/$Repo" --json visibility --jq '.visibility' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Backup repository $Owner/$Repo was not found." }
    if ($visibility.Trim() -ne 'PRIVATE') {
        throw "$Owner/$Repo is $visibility. Refusing to $Action a repository that is not private."
    }
}

function Get-NetworkBackupEnvelope {
    <# Fetches the encrypted backup, or reads -EnvelopeFile, and checks its format. #>
    param(
        [Parameter(Mandatory)][string]$Owner,
        [Parameter(Mandatory)][string]$Repo,
        [Parameter(Mandatory)][string]$Branch,
        [Parameter(Mandatory)][string]$Path,
        # Decrypt an envelope already on this machine instead of fetching one.
        [string]$EnvelopeFile
    )
    if ($EnvelopeFile) {
        if (-not (Test-Path -LiteralPath $EnvelopeFile)) { throw "$EnvelopeFile was not found." }
        Write-Host "==> Reading $EnvelopeFile" -ForegroundColor Cyan
        $text = Get-Content -LiteralPath $EnvelopeFile -Raw
    } else {
        Assert-NetworkDataRepository -Owner $Owner -Repo $Repo

        Write-Host "==> Fetching $Path from $Owner/$Repo@$Branch" -ForegroundColor Cyan
        # Must be requested raw. Decoding the base64 .content through a PowerShell
        # pipeline splits it per line and decodes each chunk separately, which
        # fabricates newlines inside every value.
        $text = (gh api "repos/$Owner/$Repo/contents/${Path}?ref=$Branch" -H 'Accept: application/vnd.github.raw') -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Could not download $Path. Has the phone backed up yet?" }
    }
    $envelope = $text | ConvertFrom-Json
    if ($envelope.format -ne 'network-app-encrypted-backup' -or $envelope.version -ne 1) {
        throw 'Unrecognised backup envelope. This script is older or newer than the app that wrote it.'
    }
    $envelope
}

function Read-NetworkBackupPassphrase {
    <#
    Asks the owner for the backup passphrase and returns it as a SecureString.
    $Purpose says what it will unlock. A console asks at its prompt. Without one,
    as when an agent's shell runs the script, a small window asks instead, so the
    owner sees each request and can cancel it.
    #>
    param([Parameter(Mandatory)][string]$Purpose)
    $nonInteractive = [Environment]::GetCommandLineArgs() -match '^-noni'
    if ([Console]::IsInputRedirected -or $nonInteractive) { return Read-PassphraseInWindow $Purpose }
    Write-Host $Purpose
    Read-Host -AsSecureString -Prompt 'Backup passphrase'
}

function Read-PassphraseInWindow([string]$Purpose) {
    $window = New-PassphraseWindow $Purpose
    try {
        if ($window.Form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { throw 'The passphrase window was cancelled.' }
        $secure = [securestring]::new()
        foreach ($character in $window.Box.Text.ToCharArray()) { $secure.AppendChar($character) }
        $secure.MakeReadOnly()
        $secure
    } finally {
        $window.Form.Dispose()
    }
}

function New-PassphraseWindow([string]$Purpose) {
    Add-Type -AssemblyName System.Windows.Forms, System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $width = 460
    $form = [System.Windows.Forms.Form]@{
        Text            = 'Network App backup'
        StartPosition   = 'CenterScreen'
        TopMost         = $true
        FormBorderStyle = 'FixedDialog'
        MaximizeBox     = $false
        MinimizeBox     = $false
        AutoSize        = $true
        AutoSizeMode    = 'GrowAndShrink'
        Padding         = [System.Windows.Forms.Padding]::new(12)
    }
    $layout = [System.Windows.Forms.FlowLayoutPanel]@{ FlowDirection = 'TopDown'; WrapContents = $false; AutoSize = $true; AutoSizeMode = 'GrowAndShrink' }
    $message = [System.Windows.Forms.Label]@{
        Text        = "$Purpose`n`nType the backup passphrase to allow it, or Cancel."
        AutoSize    = $true
        MaximumSize = [System.Drawing.Size]::new($width, 0)
        Margin      = [System.Windows.Forms.Padding]::new(0, 0, 0, 8)
    }
    $box = [System.Windows.Forms.TextBox]@{ UseSystemPasswordChar = $true; Width = $width }
    $buttons = [System.Windows.Forms.FlowLayoutPanel]@{ FlowDirection = 'RightToLeft'; WrapContents = $false; AutoSize = $true; AutoSizeMode = 'GrowAndShrink'; Width = $width; Anchor = 'Right' }
    $cancel = [System.Windows.Forms.Button]@{ Text = 'Cancel'; DialogResult = 'Cancel'; AutoSize = $true }
    $allow = [System.Windows.Forms.Button]@{ Text = 'Allow'; DialogResult = 'OK'; AutoSize = $true }
    $buttons.Controls.AddRange(@($cancel, $allow))
    $layout.Controls.AddRange(@($message, $box, $buttons))
    $form.Controls.Add($layout)
    $form.AcceptButton = $allow
    $form.CancelButton = $cancel
    $form.Add_Shown({ $form.Activate(); $box.Focus() }.GetNewClosure())
    [pscustomobject]@{ Form = $form; Box = $box }
}

function Unprotect-NetworkBackup {
    <# Decrypts the envelope in memory and returns the payload. Writes nothing. #>
    param(
        [Parameter(Mandatory)]$Envelope,
        [Parameter(Mandatory)][securestring]$Passphrase
    )
    $passphraseText = [System.Net.NetworkCredential]::new('', $Passphrase).Password
    if ([string]::IsNullOrWhiteSpace($passphraseText)) { throw 'No passphrase entered.' }
    try {
        $salt = [Convert]::FromBase64String($Envelope.salt)
        $iv = [Convert]::FromBase64String($Envelope.iv)
        $sealed = [Convert]::FromBase64String($Envelope.ciphertext)
        $iterations = [int]$Envelope.iterations
        if ($iterations -lt 100000 -or $iterations -gt 1000000) { throw 'Invalid backup key settings.' }

        # Java's AES/GCM appends the 16-byte tag to the ciphertext; .NET wants them apart.
        $tagLength = 16
        $cipherText = New-Object byte[] ($sealed.Length - $tagLength)
        $tag = New-Object byte[] $tagLength
        [Array]::Copy($sealed, 0, $cipherText, 0, $cipherText.Length)
        [Array]::Copy($sealed, $cipherText.Length, $tag, 0, $tagLength)

        $kdf = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
            $passphraseText, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
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
        $payload
    } finally {
        $passphraseText = $null
        [GC]::Collect()
    }
}
