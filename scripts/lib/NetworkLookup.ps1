# Finding and printing people in a decrypted backup payload, for
# network-lookup.ps1. Dot-source it.
#
# A profile shows what the app's assistant may read through its tools and no
# more: a contact value never appears, only whether one is saved. Assistant
# reports in the same backup are never read here.

function Invoke-NetworkLookup {
    <#
    Returns the lines to print for one lookup: -Name (each person whose name or
    other name matches each text), -Search (people with a record that holds
    every word), or -Self (the owner's own profile).
    #>
    param(
        [Parameter(Mandatory)]$Payload,
        [string[]]$Name,
        [string]$Search,
        [switch]$Self,
        [switch]$AllNotes,
        [switch]$IncludeArchived,
        [int]$MaxPeople = 15,
        # A name that matches more people than this lists their names only.
        [int]$FullProfilesAtMost = 5,
        [int]$NotesShown = 10
    )
    $schema = [int]$Payload.schemaVersion
    if ($schema -lt 3) { throw "This backup's data schema ($schema) is too old for this script." }
    $index = New-NetworkIndex $Payload
    $made = if ($Payload.exportedAt) { [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Payload.exportedAt).ToLocalTime().ToString('yyyy-MM-dd HH:mm') } else { 'at an unknown time' }
    "Backup made $made; anything changed on the phone since then is not in it."
    # Schema 10's formerAliases is not shown: it only marks some of the other names, and the assistant's tools do not read it.
    if ($schema -gt 10) { "This backup's data schema ($schema) is newer than this script knows (10), so fields added since are not shown." }
    ''

    if ($Self) {
        $owners = @($index.People | Where-Object { $_.isSelf })
        if (-not $owners) { 'No profile is marked as the owner''s own.' }
        foreach ($person in $owners) { Format-PersonProfile $index $person -NotesShown $NotesShown -AllNotes:$AllNotes; '' }
        return
    }

    if ($Search) {
        $words = @((Get-LookupKey $Search) -split ' ' | Where-Object { $_ })
        $results = @(Search-NetworkRecords $index $words -IncludeArchived:$IncludeArchived)
        if (-not $results) {
            "Nothing saved matches every word of '$Search'."
        } else {
            $heading = "$($results.Count) people have a record with every word of '$Search'"
            if ($results.Count -gt $MaxPeople) { $heading += "; the first $MaxPeople are shown" }
            "$heading. Look one up by name for the whole profile."
            ''
            foreach ($result in $results | Select-Object -First $MaxPeople) {
                "== $(Format-PersonBrief $index $result.Person)"
                foreach ($hit in $result.Hits) { "   - $($hit.Kind): $($hit.Prefix)$(Get-Excerpt $hit.Text $words)$($hit.Detail)" }
                ''
            }
        }
        if (-not $IncludeArchived) { 'Archived people are left out; add -IncludeArchived to include them.' }
        return
    }

    if (-not $Name) { throw 'Give -Name, -Search, or -Self.' }
    $shown = @{}
    foreach ($query in $Name) {
        $found = @($index.People | Where-Object { Test-NameMatch $_ $query } | Sort-Object { $_.name })
        if (-not $found) {
            "No one is saved under a name like '$query'."
        } elseif ($found.Count -gt $FullProfilesAtMost) {
            "$($found.Count) people match '$query', so only their names are shown. Look one up by a fuller name for the whole profile."
            foreach ($person in $found) { "   - $(Format-PersonBrief $index $person)" }
        } else {
            foreach ($person in $found) {
                if ($shown.ContainsKey([string]$person.id)) { continue }
                $shown[[string]$person.id] = $true
                Format-PersonProfile $index $person -NotesShown $NotesShown -AllNotes:$AllNotes
            }
        }
        ''
    }
}

function New-NetworkIndex($Payload) {
    # Groups every record by person once, so a lookup only reads dictionaries.
    $noteById = @{}
    foreach ($note in @($Payload.interactions)) { if ($null -ne $note) { $noteById[[string]$note.id] = $note } }
    [pscustomobject]@{
        People       = @($Payload.people | Where-Object { $null -ne $_ })
        Notes        = Group-ByPerson $Payload.interactions
        Needs        = Group-ByPerson $Payload.needs
        Capabilities = Group-ByPerson $Payload.capabilities
        Affiliations = Group-ByPerson $Payload.affiliations
        Facts        = Group-ByPerson $Payload.facts
        NoteById     = $noteById
    }
}

function Group-ByPerson($Rows) {
    $map = @{}
    foreach ($row in @($Rows)) {
        if ($null -eq $row) { continue }
        $key = [string]$row.personId
        if (-not $map.ContainsKey($key)) { $map[$key] = [System.Collections.Generic.List[object]]::new() }
        $map[$key].Add($row)
    }
    $map
}

function Get-PersonRows($Map, $Person) {
    $key = [string]$Person.id
    if ($Map.ContainsKey($key)) { $Map[$key] }
}

function Get-LookupKey([string]$Text) {
    ($Text -replace '\s+', ' ').Trim().ToLowerInvariant()
}

function Get-OtherNames($Person) {
    "$($Person.aliases)" -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

function Test-NameMatch($Person, [string]$Query) {
    # The text inside a name or other name, or every word of it in any order.
    $key = Get-LookupKey $Query
    if (-not $key) { return $false }
    $words = $key -split ' '
    foreach ($candidate in @($Person.name; Get-OtherNames $Person)) {
        $text = Get-LookupKey $candidate
        if ($text.Contains($key)) { return $true }
        $parts = $text -split '[^\p{L}\p{N}]+'
        if (-not @($words | Where-Object { $_ -notin $parts })) { return $true }
    }
    $false
}

function Search-NetworkRecords($Index, [string[]]$Words, [switch]$IncludeArchived) {
    # People with a record holding every word, most matching records first.
    if (-not $Words) { throw 'Give -Search one or more words.' }
    $found = foreach ($person in $Index.People) {
        if ($person.archived -and -not $IncludeArchived) { continue }
        $hits = @(Get-PersonRecords $Index $person | Where-Object { Test-AllWords $_.Text $Words })
        if ($hits) {
            [pscustomobject]@{ Person = $person; Hits = $hits; Latest = ($hits | Measure-Object -Property Date -Maximum).Maximum }
        }
    }
    @($found) | Sort-Object @{ Expression = { $_.Hits.Count }; Descending = $true }, @{ Expression = { $_.Latest }; Descending = $true }
}

function Test-AllWords([string]$Text, [string[]]$Words) {
    $key = Get-LookupKey $Text
    foreach ($word in $Words) { if (-not $key.Contains($word)) { return $false } }
    $true
}

function Get-PersonRecords($Index, $Person) {
    # Everything saved about one person, newest and current first within each kind.
    $fields = @(@('Location', $Person.location), @('Relationship', $Person.relationship), @('Tags', $Person.tags), @('Profile notes', $Person.notes))
    foreach ($field in $fields) {
        $value = "$($field[1])".Trim()
        if ($value) { New-LookupRecord 'Profile' $field[0] $value -Date $Person.updatedAt }
    }

    $affiliations = @(Get-PersonRows $Index.Affiliations $Person) |
        Sort-Object @{ Expression = { [bool]$_.current }; Descending = $true }, @{ Expression = { [long]$_.lastConfirmedAt }; Descending = $true }
    foreach ($affiliation in $affiliations) {
        $education = $affiliation.kind -eq 'education'
        $state = if ($affiliation.current) { 'current' } else { 'past' }
        New-LookupRecord ($education ? 'Education' : 'Positions') ($education ? 'Education' : 'Position') (Format-AffiliationLabel $affiliation) `
            -Detail " ($state, confirmed $(Format-Day $affiliation.lastConfirmedAt)$(Format-Source $Index $affiliation))" -Date $affiliation.lastConfirmedAt
    }

    $needs = @(Get-PersonRows $Index.Needs $Person) |
        Sort-Object @{ Expression = { $_.status -eq 'active' }; Descending = $true }, @{ Expression = { [long]$_.lastConfirmedAt }; Descending = $true }
    foreach ($need in $needs) {
        $state = if ($need.status -eq 'active') { 'open' } else { 'closed' }
        $helped = if ($null -ne $need.helpedAt) { "; helped $(Format-Day $need.helpedAt)" } else { '' }
        New-LookupRecord 'Needs and goals' 'Need or goal' $need.text -Prefix "[$state] " `
            -Detail " (confirmed $(Format-Day $need.lastConfirmedAt)$helped$(Format-Source $Index $need))" -Date $need.lastConfirmedAt
    }

    $capabilities = @(Get-PersonRows $Index.Capabilities $Person) |
        Sort-Object @{ Expression = { $_.active -ne $false }; Descending = $true }, @{ Expression = { [long]$_.lastConfirmedAt }; Descending = $true }
    foreach ($capability in $capabilities) {
        $state = if ($capability.active -ne $false) { 'active' } else { 'inactive' }
        New-LookupRecord 'Capabilities and resources' 'Capability or resource' $capability.text -Prefix "[$state] " `
            -Detail " (confirmed $(Format-Day $capability.lastConfirmedAt)$(Format-Source $Index $capability))" -Date $capability.lastConfirmedAt
    }

    $facts = @(Get-PersonRows $Index.Facts $Person) | Sort-Object { [long]$_.lastConfirmedAt } -Descending
    foreach ($fact in $facts) {
        New-LookupRecord 'Background' 'Background' $fact.text `
            -Detail " (confirmed $(Format-Day $fact.lastConfirmedAt)$(Format-Source $Index $fact))" -Date $fact.lastConfirmedAt
    }

    $notes = @(Get-PersonRows $Index.Notes $Person) |
        Sort-Object @{ Expression = { [long]$_.occurredAt }; Descending = $true }, @{ Expression = { [long]$_.id }; Descending = $true }
    foreach ($note in $notes) {
        New-LookupRecord 'Notes' 'Note' $note.note -Prefix "$(Format-Day $note.occurredAt)$(Format-Origin $note.origin): " -Date $note.occurredAt
    }
}

function New-LookupRecord([string]$Section, [string]$Kind, [string]$Text, [string]$Prefix = '', [string]$Detail = '', $Date = 0) {
    [pscustomobject]@{ Section = $Section; Kind = $Kind; Text = $Text; Prefix = $Prefix; Detail = $Detail; Date = [long]$Date }
}

function Format-PersonProfile {
    param($Index, $Person, [int]$NotesShown = 10, [switch]$AllNotes)
    "== $($Person.name)$(Get-PersonFlags $Person)"
    $otherNames = @(Get-OtherNames $Person)
    if ($otherNames) { "   Other names: $($otherNames -join '; ')" }
    $records = @(Get-PersonRecords $Index $Person)
    foreach ($field in $records | Where-Object Section -EQ 'Profile') { "   $($field.Kind): $(Format-Block $field.Text)" }
    "   Contact saved: $(if ("$($Person.contact)".Trim()) { 'yes' } else { 'no' })"
    foreach ($section in 'Positions', 'Education', 'Needs and goals', 'Capabilities and resources', 'Background') {
        $rows = @($records | Where-Object Section -EQ $section)
        if (-not $rows) { continue }
        "   ${section}:"
        foreach ($row in $rows) { "     - $($row.Prefix)$(Format-Block $row.Text)$($row.Detail)" }
    }
    $notes = @($records | Where-Object Section -EQ 'Notes')
    if ($notes) {
        $shown = if ($AllNotes) { $notes } else { @($notes | Select-Object -First $NotesShown) }
        $heading = '   Notes, newest first'
        if ($shown.Count -lt $notes.Count) { $heading += " ($($shown.Count) of $($notes.Count); add -AllNotes for the rest)" }
        "${heading}:"
        foreach ($row in $shown) { "     - $($row.Prefix)$(Format-Block $row.Text)" }
    }
}

function Format-PersonBrief($Index, $Person) {
    $current = @(Get-PersonRows $Index.Affiliations $Person |
        Where-Object { $_.current -and $_.kind -ne 'education' } | ForEach-Object { Format-AffiliationLabel $_ })
    $brief = "$($Person.name)$(Get-PersonFlags $Person)"
    if ($current) { $brief += " - $($current -join '; ')" }
    $brief
}

function Get-PersonFlags($Person) {
    $flags = @(if ($Person.isSelf) { 'you' }; if ($Person.archived) { 'archived' })
    if ($flags) { " ($($flags -join ', '))" } else { '' }
}

function Format-AffiliationLabel($Affiliation) {
    $organization = "$($Affiliation.organization)".Trim()
    $role = "$($Affiliation.role)".Trim()
    if (-not $organization) { $role } elseif (-not $role) { $organization } else { "$role at $organization" }
}

function Format-Source($Index, $Record) {
    # The note a record was saved from, by its date.
    if ($null -eq $Record.sourceInteractionId) { return '' }
    $note = $Index.NoteById[[string]$Record.sourceInteractionId]
    if ($note) { "; from the note of $(Format-Day $note.occurredAt)" } else { '' }
}

function Format-Origin([string]$Origin) {
    switch ($Origin) {
        'assistant' { ', saved by the Assistant' }
        'web' { ', found on the web by the Assistant' }
        'ai_reviewed' { ', saved from an AI suggestion' }
        default { '' }
    }
}

function Format-Day($Millis) {
    if ($null -eq $Millis) { return 'unknown date' }
    [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Millis).ToLocalTime().ToString('yyyy-MM-dd')
}

function Format-Block([string]$Text) {
    # Keeps a multi-line value under its bullet.
    ($Text.Trim() -split '\r?\n') -join "`n       "
}

function Get-Excerpt([string]$Text, [string[]]$Words, [int]$Length = 400) {
    # A long text cut to the part around its first match, on one line.
    $flat = ($Text -replace '\s+', ' ').Trim()
    if ($flat.Length -le $Length) { return $flat }
    $at = $flat.ToLowerInvariant().IndexOf($Words[0])
    $start = [Math]::Max(0, [Math]::Min($at - 100, $flat.Length - $Length))
    $(if ($start -gt 0) { '...' }) + $flat.Substring($start, $Length) + $(if ($start + $Length -lt $flat.Length) { '...' })
}
