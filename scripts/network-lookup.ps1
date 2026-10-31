#Requires -Version 7
<#
.SYNOPSIS
    Look people up in the encrypted network backup from this PC.

.DESCRIPTION
    Downloads the latest encrypted backup from the private data repository and,
    once the owner types the passphrase, prints only what was asked for:

        -Name     each person whose name or other name matches the text
        -Search   people with a saved record that holds every word
        -Self     the owner's own profile

    A profile shows what the app's assistant may read through its tools and no
    more: a contact value never appears, only whether one is saved. A name that
    matches more than five people lists their names only. Assistant reports in
    the same backup are not read. The script writes nothing to disk and leaves
    the backup untouched.

    The owner types the passphrase for every lookup: at the prompt in a console,
    or, when the script runs without one, as it does from an agent's shell, in
    a small window that names what is being looked up. Cancel refuses the
    lookup. The passphrase is never stored, passed as an argument, or echoed.

.EXAMPLE
    .\scripts\network-lookup.ps1 -Name 'Synthetic Person', 'Synthetic Other'

.EXAMPLE
    .\scripts\network-lookup.ps1 -Search 'robotics hiring'

.EXAMPLE
    .\scripts\network-lookup.ps1 -Self
#>
[CmdletBinding(DefaultParameterSetName = 'Name')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Name', Position = 0)]
    [string[]]$Name,
    [Parameter(Mandatory, ParameterSetName = 'Search')]
    [string]$Search,
    [Parameter(Mandatory, ParameterSetName = 'Self')]
    [switch]$Self,
    # Every note of each person shown, not only the latest ten.
    [switch]$AllNotes,
    # Let -Search find archived people too. Name lookups always include them, marked.
    [switch]$IncludeArchived,
    # The most people a search shows.
    [int]$MaxPeople = 15,
    [string]$Owner = 'AzizjonKasimov',
    [string]$Repo = 'network-app-data',
    [string]$Branch = 'main',
    [string]$Path = 'network-backup.enc.json',
    # Read an envelope already on this machine instead of fetching one.
    [string]$EnvelopeFile
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\NetworkBackup.ps1')
. (Join-Path $PSScriptRoot 'lib\NetworkLookup.ps1')

$purpose = switch ($PSCmdlet.ParameterSetName) {
    'Self' { 'Look up your own profile in your network backup.' }
    'Search' { "Search your network backup for: $Search" }
    default { "Look up in your network backup: $($Name -join ', ')" }
}

$envelope = Get-NetworkBackupEnvelope -Owner $Owner -Repo $Repo -Branch $Branch -Path $Path -EnvelopeFile $EnvelopeFile
$secure = Read-NetworkBackupPassphrase -Purpose $purpose
try {
    $payload = Unprotect-NetworkBackup -Envelope $envelope -Passphrase $secure
} finally {
    $secure.Dispose()
}
Invoke-NetworkLookup -Payload $payload -Name $Name -Search $Search -Self:$Self -AllNotes:$AllNotes -IncludeArchived:$IncludeArchived -MaxPeople $MaxPeople
