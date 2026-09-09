# Network App

Network App is a private Android memory aid for a personal network. It records who people are, what they are trying to achieve, what they may be able to help with, and the context from dated conversations. Capture, correction, and search all happen in one conversational **Assistant** thread, and every suggestion shows the stored evidence and date behind it.

## Current milestone

The first native Android version includes:

- create, edit, archive, and delete people;
- record several concurrent positions per person, each with its own organization, role, and current/past state, marked as work or study;
- keep background facts that are not a need, a capability, or a position as their own dated, editable records;
- mark a profile as the user's own profile for reciprocal matching;
- record dated interactions, needs/goals, and capabilities/resources;
- private on-device matching across profile fields and linked records;
- a single chat thread that routes each message to capture or search, so there is no separate capture screen and search screen;
- AI-powered natural-language capture and update proposals with editable review before saving;
- follow-up corrections that revise the open proposal instead of restarting the capture;
- capture coverage that flags explicit facts kept only in the original interaction;
- optional full-active-network AI search with evidence IDs, exact stored sources, and one-time consent;
- on-device-first voice input in the chat composer;
- labelling a wrong assistant answer in the thread, and exporting the collected reports as one redacted file for diagnosis;
- adaptive connected-N launcher artwork, including round and monochrome variants;
- evidence and dates on every search result;
- Room persistence with cascading deletion;
- client-side encrypted GitHub backup and destructive restore confirmation;
- persistent backup-needed, last-attempt, last-success, and failure status;
- automatic and manual backup controls;
- launch-time and manual signed APK update checks;
- a PowerShell release workflow matching the existing expense tracker pattern.

The current signed release is [`v0.9.0`](https://github.com/AzizjonKasimov/network-app-releases/releases/tag/v0.9.0) (version code `10`). Its GitHub asset and updater manifest have been verified against the package version, byte size, SHA-256 digest, and pinned signing certificate.

Manual capture and local matching remain fully available without the gateway or network access. The assistant never writes directly: create/update requests become editable proposals, and Room is changed only after explicit confirmation.

## AI gateway

AI features are optional and route through a **self-hosted gateway** rather than a public AI vendor. The gateway address is compiled into the build; the access token is not. The gateway picks the model, so it can change without an app release. Configure it on the phone:

1. Obtain an access token for the gateway, issued for this device.
2. Open **Settings → AI gateway**, paste the token, and tap **Save**.
3. On **Assistant**, type or dictate a message. A note about one person becomes a capture; a question about the network becomes a search. The assistant decides which, in the same round trip that identifies the person, so a capture costs no extra latency for the routing.
4. Review the proposal card: the target, every field change, record edit, lifecycle change, date, and any facts labeled **Kept only in the original interaction**. Uncheck or edit anything incorrect, then tap **Apply**.
5. To correct a proposal before saving, reply in the composer (“that was last Tuesday”, “drop the second need”). The assistant revises the open proposal rather than starting a new capture, and the original note stays verbatim. **Discard** closes it without writing anything.

The original applied text is stored verbatim as an `AI-reviewed capture` interaction. Before returning a proposal, the assistant is instructed to account for each explicit fact as a structured change or an interaction-only fact; the app validates counts, lengths, duplicate profile fields, and duplicate record edits before showing the review. New extracted needs and capabilities retain a provenance link to the source interaction. The assistant cannot archive or delete people, delete records, change the self marker, or modify more than one person in one request.

Positions are their own records rather than a single organization and role on the profile, so someone who is a CEO at one company and a CTO at another keeps both. The assistant proposes one entry per position, each editable and individually selectable before saving, and a position marked as study reads as education rather than a job.

Anything explicitly stated that is not a position, a need, or a capability becomes a **Background** record: a product's user count, a notable event someone took part in, a piece of history. The assistant chooses in that order, so **Kept only in the original interaction** is now reserved for facts that cannot be attributed to this person at all. Background records are dated, editable, searchable, and citable as evidence in their own right.

A refusal and an explanation are kept apart. The assistant refuses only what it cannot do safely - more than one person, a deletion, an unidentifiable target - and that discards the proposal. Anything it merely handled awkwardly is reported as a **How this was handled** note on the card, and the proposal stays complete and applicable.

When a message routes to search and full-network consent has not been given yet, the app asks first. The disclosure explains that the request sends all active searchable network text: names, self marker, organizations, roles, locations, relationship context, tags, profile notes, interactions, active needs, active capabilities, IDs, and dates. Contact values, archived people, closed needs, inactive capabilities, backup credentials, and the access token are excluded. Consent can be revoked in Settings, and declining leaves the **People** tab's local matching fully usable.

Because one thread mixes both request kinds, replayed history is scoped to the narrower of the two. A capture request never replays a previous search answer, which is built from the whole network; only the user's own turns and capture replies travel with it. Replayed history is capped at six turns and 2,000 characters so a long thread cannot crowd out the current message.

If the active search corpus exceeds 1 MiB, the app refuses to truncate or send it and keeps showing local results. Missing configuration, invalid model output, unknown IDs, timeouts, quota errors, and offline failures remain visible and do not silently save or invent data.

## Voice input

The microphone control in the **Assistant** composer requests `RECORD_AUDIO` only after it is tapped. On Android 12 or newer, the app prefers an available on-device recognizer. If on-device recognition is unavailable, the app explains that the phone's speech provider may process audio remotely and asks before enabling that fallback for the current app session.

Network App never saves audio files, logs recognized speech, or includes audio in Room or encrypted backups. Only the final transcript is appended to the editable field. Partial results are shown only while listening, transcripts that would exceed 4,000 characters are rejected without changing the existing text, and voice input never automatically submits an AI request, search, or database write.

## Assistant feedback

The assistant is wrong sometimes: it attaches a note to the wrong person, stores a job as a need, or misses something that was plainly stated. Every answer that came from the gateway carries a **Report a problem** action underneath it, which is where those faults get recorded while the evidence is still on screen.

1. Tap **Report a problem** under the answer.
2. Pick what went wrong. The labels are fixed - wrong person, missed something, invented something, wrong record type, wrong date, bad search results, misunderstood the request, something else - so reports can be counted and grouped later. Add a note if the label does not say enough.
3. The report is saved locally with a copy of the message, the answer, and the proposal or search results behind it. Nothing is sent anywhere.

A report copies the response into itself rather than pointing at it, because the chat thread is memory-only: the proposal card disappears the moment it is applied or a new chat starts. That makes each report readable long after the conversation is gone, and it survives a backup restore that replaces every person.

**Settings → Assistant feedback** lists what has been collected, says how many are new since the last export, and offers two actions:

- **Export reports** writes every stored report to one JSON file and opens the system share sheet, so the file goes wherever you choose. **Replace people with placeholders** is on by default: saved people become stable placeholders (`Person 1`, `Person 2`, `Me`), and emails, links, and phone-shaped numbers are removed. The placeholders are stable within a report, so a wrong-target fault still reads correctly as *the note about Person 2 was attached to Person 5*. Two limits are stated in the export dialog and are real: someone not yet saved in the app cannot be detected and may still be named in quoted text, and a first name shared by two saved people collapses to `<a saved person>` rather than naming the wrong one. Turning redaction off exports the real names and conversation text; that file must be kept out of version control, issue trackers, and anywhere it could become public.
- **Delete all** removes every stored report and every exported copy left in the app's cache. People and records are untouched.

The exported document is self-describing. It carries its own name and version, whether it was redacted, the installed app version, counts by label and by stage, the meaning of every label, and one entry per report with the message, the answer, and the flattened proposal or search results. Contact values never reach a report: a proposal that changed a contact field records that it happened, without the value.

The intended loop is: label the bad answers as they happen, export when a few have collected, and hand the file to whoever is fixing the prompts and schemas. Exported reports stay in the database and are marked as already exported, so the next export shows what is new without losing history. Files named `assistant-feedback-*.json` are gitignored so a report saved into the repository cannot be committed by accident.

## Privacy and backup

Network data is sensitive third-party personal information. Android automatic cloud backup is disabled, and real records must never enter source control, tests, screenshots, logs, development prompts, or release artifacts.

Gateway requests are an explicit exception chosen by the user. Natural-language capture sends the current message and, after target selection, only that person's non-contact profile and linked records, plus the bounded capture-scoped history described above. AI search sends the full active searchable corpus described above after consent. See [PRIVACY.md](PRIVACY.md) for the exact boundary. A token stored by a mobile app can be recovered from a rooted or otherwise compromised device, so issue one token per device and revoke it on the gateway if that device is lost.

Speech input is separate from the gateway. On-device recognition keeps audio with the device recognition service. If the user accepts the disclosed fallback, Android's configured speech provider may transmit audio under that provider's terms; the fallback consent lasts only until the Network App process restarts.

GitHub backup is optional and configured inside the app:

1. The initialized `AzizjonKasimov/network-app-data` repository must remain **private**.
2. Create a fine-grained GitHub token limited to that repository with Contents read/write access.
3. In Network App Settings, enter the repository, token, and a backup passphrase of at least 12 characters.
4. Save the settings and use **Back up now** once before relying on automatic backup.

The complete backup is serialized, encrypted on the phone with AES-256-GCM, and only then sent through the GitHub Contents API. The encryption key is derived from the passphrase with PBKDF2-HMAC-SHA256. The app verifies that the repository is private before upload or restore. The token and passphrase are stored with Android encrypted preferences and are never included in the backup.

Keep the passphrase somewhere secure outside the phone. A fresh installation cannot restore the data without it.

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

The script never handles the token. It is read at runtime from the app's own encrypted preferences, so it is never passed through `.env`, a file staged on the device, Gradle arguments, command text, or test reports. The run exercises routing, capture, refinement, and search against the production `GatewayClient` with synthetic records only.

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
- Room schema version 5 adds a standalone `ai_feedback` table for reported assistant answers. It has no foreign keys and is excluded from the encrypted backup: it is diagnostic data about the assistant rather than a network record, so it survives a restore that replaces every person.
- Repository boundary and state-flow presentation with simple application-owned dependency wiring.
- Local deterministic matching in `NetworkMatcher`, reachable without AI from the **People** tab.
- Chat routing and privacy-scoped history in `ChatRouter`; conversation state in `ChatModels`.
- Bounded gateway REST client with validated structured capture coverage and evidence-ID search results.
- Lifecycle-managed Android speech recognition with on-device preference and a disclosed session-only fallback.
- Encrypted backup codec separated from the GitHub transport.
- Feedback capture, placeholder redaction, and report building in `feedback`, kept free of Android types so all three are unit-tested.
- No backend, account, analytics, telemetry, contact scraping, automatic address-book import, autonomous outreach, or AI deletion.
