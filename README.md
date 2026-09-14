# Network App

Network App is a private Android memory aid for a personal network. It records who people are, what they are trying to achieve, what they may be able to help with, and the context from dated conversations. Recording, correcting, and asking all happen in one conversational **Assistant** thread, where an agent reads and updates the saved records through tools that run on the phone.

## Current milestone

The first native Android version includes:

- create, edit, archive, and delete people;
- record several concurrent positions per person, each with its own organization, role, and current/past state, with study listed separately under Education;
- keep background facts that are not a need, a capability, a position, or education as their own dated, editable records;
- mark a profile as the user's own profile for reciprocal matching;
- record dated interactions, needs/goals, and capabilities/resources;
- private on-device matching across profile fields and linked records;
- a single chat thread where an agent answers any question about the network and records what the user tells it, across several people in one message;
- changes the assistant saves land immediately and can be undone together from its reply;
- deletes and merges the assistant asks for wait on a confirmation card;
- moving a note, and every record it created, onto another person or a brand-new one when it lands on the wrong contact, by asking or from the person screen;
- turning a record into the right kind - a need that is really a capability, a position that is really education - without retyping it;
- on-device-first voice input in the chat composer;
- labelling a wrong assistant answer in the thread, with the reports carried in the encrypted backup for later diagnosis;
- adaptive connected-N launcher artwork, including round and monochrome variants;
- Room persistence with cascading deletion;
- client-side encrypted GitHub backup and destructive restore confirmation;
- persistent backup-needed, last-attempt, last-success, and failure status;
- automatic and manual backup controls;
- launch-time and manual signed APK update checks;
- a PowerShell release workflow matching the existing expense tracker pattern.

The current signed release is [`v0.14.0`](https://github.com/AzizjonKasimov/network-app-releases/releases/tag/v0.14.0) (version code `16`). Its GitHub asset and updater manifest have been verified against the package version, byte size, SHA-256 digest, and pinned signing certificate.

Manual editing and local matching remain fully available without the gateway or network access. What the assistant saves is listed under its reply with one **Undo**, and deleting or merging always waits for a tap.

## AI gateway and the assistant

AI features are optional and route through a **self-hosted gateway** rather than a public AI vendor. The gateway address is compiled into the build; the access token is not. The gateway picks the model, so it can change without an app release. Configure it on the phone:

1. Obtain an access token for the gateway, issued for this device.
2. Open **Settings → AI gateway**, paste the token, and tap **Save**.
3. On **Assistant**, type or dictate a message. The first one asks, once, for permission for the assistant to read your network.

The assistant is an agent. Claude plans on the gateway, and every lookup and change it makes is a tool call the gateway hands back to the phone, where it runs against Room and returns only what that call asked for. The database never leaves the phone, and no tool ever returns a contact value. One message can take several steps - finding the person, reading their records, saving a note and the records it supports - and the thread shows which step is running.

It can:

- answer questions about the network: who could help with something, what was last discussed with someone, who works where, who has not come up in a while;
- record what you tell it on every person a message concerns, keeping your words as a note and the facts it states as positions, education, needs, capabilities, or background records linked to that note;
- update, close, or re-date records, turn a record into the right kind, move a note to the person it belongs to, create people, change profile fields, and archive or restore someone.

Everything it saves lands immediately and is listed under its reply with one **Undo** for the whole reply. Undo refuses rather than overwrite anything edited since, including a person the reply created who has since gained notes or records of their own.

Deleting a person, a note, or a record, and merging two entries for the same person, never happen on the assistant's say-so. They appear on a card under the reply and run only when you tap **Delete** or **Merge**. Those cannot be undone afterwards.

Positions are their own records rather than a single organization and role on the profile, so someone who is a CEO at one company and a CTO at another keeps both.

A position is work only: a job, a business someone runs or founded, or freelance, contract, or advisory work. Study - a degree, a language school, a course - is **Education**. Volunteering, memberships, communities, clubs, and events someone took part in are neither, however formal they sound: they become **Background** records, along with anything else stated about a person that is not a position, education, a need, or a capability. Background records are dated, editable, and searchable in their own right.

A note sometimes lands on the wrong person, usually somebody mentioned beside the person actually being described. Ask the assistant to move it, or open the person holding the note, find it under **Interactions**, and tap **Move**. One field there both filters the people already saved and, when nothing matches, offers to create the person the note really belongs to. The note and every record that cites it travel together; anything the person gained some other way stays put. Profile fields stay behind and need checking by hand, since an overwritten column keeps no trace of where its value came from.

Replayed history is capped at six turns and 3,000 characters. A reply is replayed together with what it changed, record addresses included, so a follow-up like "that should be a capability" reaches the record it means.

Missing configuration, timeouts, quota errors, and offline failures are shown in the thread. When a turn fails partway, whatever it saved before failing is still listed with its **Undo**.

## Voice input

The microphone control in the **Assistant** composer requests `RECORD_AUDIO` only after it is tapped. On Android 12 or newer, the app prefers an available on-device recognizer. If on-device recognition is unavailable, the app explains that the phone's speech provider may process audio remotely and asks before enabling that fallback for the current app session.

Network App never saves audio files, logs recognized speech, or includes audio in Room or encrypted backups. Only the final transcript is appended to the editable field. Partial results are shown only while listening, transcripts that would exceed 4,000 characters are rejected without changing the existing text, and voice input never automatically submits an AI request, search, or database write.

## Assistant feedback

The assistant is wrong sometimes: it attaches a note to the wrong person, stores a job as a need, or misses something that was plainly stated. Every answer that came from the gateway carries a **Report a problem** action underneath it, which is where those faults get recorded while the evidence is still on screen.

1. Tap **Report a problem** under the answer.
2. Pick what went wrong. The labels are fixed - wrong person, missed something, invented something, wrong record type, wrong date, bad search results, misunderstood the request, something else - so reports can be counted and grouped later. Add a note if the label does not say enough.
3. The report is saved with a copy of the message, the answer, what the reply saved or queued, and the tool calls it made, in order.

A report copies the response into itself rather than pointing at it, because the chat thread is memory-only: the card disappears when a new chat starts. That makes each report readable long after the conversation is gone, and the tool calls show how a wrong answer came about - a note filed on the wrong person usually starts with the lookup that matched the wrong name.

Reports ride in the ordinary encrypted backup. There is no separate export step and nothing to attach to a message: back up, and they are on the machine where the fault gets fixed. Filing or deleting a report marks a backup as needed, exactly like editing a person, so automatic backup picks them up on its own.

**Settings → Assistant feedback** lists what has been collected and lets you delete one, or all of them, once its fault has been fixed. People and records are untouched either way.

To read the reports on a development machine, after the phone has backed up:

```bash
pwsh ./scripts/read-feedback.ps1
```

It fetches the encrypted backup from the private data repository, asks for the backup passphrase, and writes only the reports to `assistant-feedback.json` in the repository root, which is gitignored. The rest of the backup is decrypted in memory to reach them and never touches disk. The passphrase is typed at the prompt and is never written to a file, passed as an argument, echoed, or kept in shell history. Pass `-EnvelopeFile <path>` to decrypt a backup file you already have instead of fetching one.

The loop is: label bad answers as they happen, back up, fix the faults, delete the reports that are done, back up again. Contact values never reach a report at all: a tool call that set a contact value is recorded without the value.

## Privacy and backup

Network data is sensitive third-party personal information. Android automatic cloud backup is disabled, and real records must never enter source control, tests, screenshots, logs, development prompts, or release artifacts.

Gateway requests are an explicit exception chosen by the user. Each message sends the message, the bounded history described above, and then whatever records the assistant's tool calls read, never contact values. See [PRIVACY.md](PRIVACY.md) for the exact boundary. A token stored by a mobile app can be recovered from a rooted or otherwise compromised device, so issue one token per device and revoke it on the gateway if that device is lost.

Speech input is separate from the gateway. On-device recognition keeps audio with the device recognition service. If the user accepts the disclosed fallback, Android's configured speech provider may transmit audio under that provider's terms; the fallback consent lasts only until the Network App process restarts.

GitHub backup is optional and configured inside the app:

1. The initialized `AzizjonKasimov/network-app-data` repository must remain **private**.
2. Create a fine-grained GitHub token limited to that repository with Contents read/write access.
3. In Network App Settings, enter the repository, token, and a backup passphrase of at least 12 characters.
4. Save the settings and use **Back up now** once before relying on automatic backup.

The complete backup is serialized, encrypted on the phone with AES-256-GCM, and only then sent through the GitHub Contents API. The encryption key is derived from the passphrase with PBKDF2-HMAC-SHA256. The app verifies that the repository is private before upload or restore. The token and passphrase are stored with Android encrypted preferences and are never included in the backup.

Keep the passphrase somewhere secure outside the phone. A fresh installation cannot restore the data without it.

**Check the stored backup opens** proves that the passphrase saved on the phone still decrypts the backup sitting in the repository. It downloads the real file, tries the real saved passphrase, reports what it found, and restores nothing. A passphrase that has drifted out of sync — retyped into the pre-filled field, changed on one device and not another — produces backups that look successful and cannot be opened, and nothing reveals that until a restore, which is the one moment there is no second copy to fall back on. Run it after changing the passphrase, and occasionally otherwise.

## Build and install

Requirements: Windows, JDK 17, and Android SDK 35 or newer.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

For an explicitly authorized live gateway check, save the access token once on the device through **Settings → AI gateway**, start exactly one emulator or connected test device, and run:

```powershell
.\scripts\test-gateway-live.ps1
```

The script never handles the token. It is read at runtime from the app's own encrypted preferences, so it is never passed through `.env`, a file staged on the device, Gradle arguments, command text, or test reports. The run drives real agent turns against the production gateway - a capture mixing work, study, and volunteering, a two-person update, a question, a delete request, and an undo - with synthetic records in an in-memory database.

The script installs the app and instrumentation APKs and then drives `am instrument` directly, rather than using `connectedDebugAndroidTest`. That Gradle task uninstalls the app when it finishes, which erases the saved token and forces it to be entered again before every run. Ordinary offline test runs skip this opt-in provider test.

`assembleDebug` copies its development build to `NetworkApp-debug.apk`. The signed release workflow copies the phone-ready build to `NetworkApp-latest.apk`, so an ordinary debug build cannot accidentally replace the distributed APK. Generated APKs are gitignored.

## Signed updates

The updater reads:

```text
https://raw.githubusercontent.com/AzizjonKasimov/network-app-releases/main/version.json
```

The public `AzizjonKasimov/network-app-releases` repository contains the signed release APK and live updater manifest. Release signing uses one stable private key for the lifetime of installed copies.

The permanent signing key is already configured locally in the gitignored `release.keystore` and `keystore.properties` files. **Do not regenerate or replace them.** A second local copy is stored under the current Windows user's protected application-data directory, with its password protected by Windows DPAPI. Keep an additional secure off-machine copy for disaster recovery.

Publish a later version with a strictly increasing version code:

```powershell
.\release.ps1 -VersionName 0.4.0 -VersionCode 4 -Notes "Describe the update"
```

Do not run the release command for a local-only build. `assembleRelease` produces and signs `NetworkApp-latest.apk` without publishing a GitHub release or changing the public updater manifest.

The script verifies the active GitHub account and repository visibility, prevents version-code rollback, builds the signed APK, checks it against the pinned public certificate fingerprint in `release-signing-cert.sha256`, creates the GitHub release, and updates `version.json`. The manifest pins the release URL and includes the APK byte size and SHA-256 digest; the app deletes a download that fails either integrity check before opening Android's package installer. Losing or changing the signing key prevents already-installed phones from accepting later updates.

## Architecture

- Kotlin, Jetpack Compose, and Material 3.
- One `:app` module, package `com.azizjon.network`.
- Room database `network.db` with people, interactions, needs, and capabilities.
- Room schema version 2 adds reviewed-interaction origin, need/capability provenance, and capability lifecycle state while preserving version-1 installations and backups.
- Room schema version 3 moves organization and role off the person onto an `affiliations` table, so a person can hold several concurrent positions. The migration turns each stored pair into one current position, and backups written before version 3 are rebuilt the same way on restore.
- Room schema version 4 adds a `facts` table for background records and a work/education kind on each position. Existing positions migrate as work, and older backups restore with no background records because there was no way to write one.
- Room schema version 5 adds a standalone `ai_feedback` table for reported assistant answers, with no foreign keys so a report outlives the records it describes. Schema version 6 drops its exported marker, which only ever tracked a share-sheet export that no longer exists.
- Repository boundary and state-flow presentation with simple application-owned dependency wiring.
- Local deterministic matching in `NetworkMatcher`, reachable without AI from the **People** tab.
- Re-filing a misfiled note in `NetworkDao.moveInteraction`, a single transaction that re-points the interaction and every record carrying its id, creating the destination person when the note belongs to somebody not yet saved. The assistant's `move_note` tool uses the same transaction.
- The agent in `ai`: `AssistantAgent` runs a turn, `AgentClient` speaks the gateway's `/v1/agent/turns` protocol, `AssistantTools` executes each tool call against Room, and `AssistantPrompt` holds the instructions and replayed history. Conversation state is in `ChatModels`.
- `AssistantStore` writes through a `ChangeRecorder` that keeps every row's before and after image. That is what lets one reply be undone as a whole, and lets undo detect anything edited since instead of overwriting it. Deletes and merges are separate store methods that only a confirmation card calls.
- Lifecycle-managed Android speech recognition with on-device preference and a disclosed session-only fallback.
- Encrypted backup codec separated from the GitHub transport.
- Feedback capture and response flattening in `feedback`, kept free of Android types so both are unit-tested.
- `BackupPayload` carries reported answers beside the snapshot rather than inside it, so diagnostic rows never reach the screens or the assistant's tools. Backup schema 5 adds them; backups written earlier restore with none. Schema 6 adds the `assistant` note origin, so an older app reports a newer backup as unsupported rather than damaged.
- No backend, account, analytics, telemetry, contact scraping, automatic address-book import, autonomous outreach, or deletion without confirmation.
