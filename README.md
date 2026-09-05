# Network App

Network App is a private Android memory aid for a personal network. It records who people are, what they are trying to achieve, what they may be able to help with, and the context from dated conversations. Local matching answers questions such as “Who could help with Android?” and always shows the stored evidence and date behind a suggestion.

## Current milestone

The first native Android version includes:

- create, edit, archive, and delete people;
- mark a profile as the user's own profile for reciprocal matching;
- record dated interactions, needs/goals, and capabilities/resources;
- private on-device matching across profile fields and linked records;
- AI-powered natural-language capture and update proposals with editable review before saving;
- capture coverage that flags explicit facts kept only in the original interaction;
- optional full-active-network AI search with evidence IDs, exact stored sources, and one-time consent;
- on-device-first voice input for capture notes and network questions;
- adaptive connected-N launcher artwork, including round and monochrome variants;
- evidence and dates on every search result;
- Room persistence with cascading deletion;
- client-side encrypted GitHub backup and destructive restore confirmation;
- persistent backup-needed, last-attempt, last-success, and failure status;
- automatic and manual backup controls;
- launch-time and manual signed APK update checks;
- a PowerShell release workflow matching the existing expense tracker pattern.

The current signed release is [`v0.3.0`](https://github.com/AzizjonKasimov/network-app-releases/releases/tag/v0.3.0) (version code `3`). Its GitHub asset and updater manifest have been verified against the package version, byte size, SHA-256 digest, and pinned signing certificate.

Manual capture and local matching remain fully available without the gateway or network access. The assistant never writes directly: create/update requests become editable proposals, and Room is changed only after explicit confirmation.

## AI gateway

AI features are optional and route through a **self-hosted gateway** rather than a public AI vendor. The gateway address is compiled into the build; the access token is not. The gateway picks the model, so it can change without an app release. Configure it on the phone:

1. Obtain an access token for the gateway, issued for this device.
2. Open **Settings → AI gateway**, paste the token, and tap **Save**.
3. On **People**, type or dictate one natural-language note or update about one person and tap **Interpret**.
4. Review the target, every field change, record edit, lifecycle change, date, and any facts labeled **Kept only in the original interaction**. Uncheck or edit anything incorrect before tapping **Apply reviewed changes**.

The original applied text is stored verbatim as an `AI-reviewed capture` interaction. Before returning a proposal, the assistant is instructed to account for each explicit fact as a structured change or an interaction-only fact; the app validates counts, lengths, duplicate profile fields, and duplicate record edits before showing the review. New extracted needs and capabilities retain a provenance link to the source interaction. The assistant cannot archive or delete people, delete records, change the self marker, or modify more than one person in one request.

The **Match** screen continues to show local matches while typing. **Search with AI** is a separate explicit action. On first use, the app explains that the request sends all active searchable network text: names, self marker, organizations, roles, locations, relationship context, tags, profile notes, interactions, active needs, active capabilities, IDs, and dates. Contact values, archived people, closed needs, inactive capabilities, backup credentials, and the access token are excluded. Consent can be revoked in Settings.

If the active search corpus exceeds 1 MiB, the app refuses to truncate or send it and keeps showing local results. Missing configuration, invalid model output, unknown IDs, timeouts, quota errors, and offline failures remain visible and do not silently save or invent data.

## Voice input

The microphone control on **People** capture and **Match** requests `RECORD_AUDIO` only after it is tapped. On Android 12 or newer, the app prefers an available on-device recognizer. If on-device recognition is unavailable, the app explains that the phone's speech provider may process audio remotely and asks before enabling that fallback for the current app session.

Network App never saves audio files, logs recognized speech, or includes audio in Room or encrypted backups. Only the final transcript is appended to the editable field. Partial results are shown only while listening, transcripts that would exceed 4,000 characters are rejected without changing the existing text, and voice input never automatically submits an AI request, search, or database write.

## Privacy and backup

Network data is sensitive third-party personal information. Android automatic cloud backup is disabled, and real records must never enter source control, tests, screenshots, logs, development prompts, or release artifacts.

Gateway requests are an explicit exception chosen by the user. Natural-language capture sends the current draft and, after target selection, only that person's non-contact profile and linked records. AI search sends the full active searchable corpus described above after consent. See [PRIVACY.md](PRIVACY.md) for the exact boundary. A token stored by a mobile app can be recovered from a rooted or otherwise compromised device, so issue one token per device and revoke it on the gateway if that device is lost.

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

For an explicitly authorized live gateway check, put only `GATEWAY_TOKEN=...` in the repo-root `.env`, start exactly one emulator or connected test device, and run:

```powershell
.\scripts\test-gateway-live.ps1
```

`.env` and `.env.*` are gitignored (except an optional `.env.example`). The live script never passes the token through Gradle arguments or test reports: it stages the single-variable file at a shell-only temporary device path, runs synthetic capture and search through the production `GatewayClient`, and removes that file in a `finally` block. Ordinary offline test runs skip this opt-in provider test.

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
- Repository boundary and state-flow presentation with simple application-owned dependency wiring.
- Local deterministic matching in `NetworkMatcher`.
- Bounded gateway REST client with validated structured capture coverage and evidence-ID search results.
- Lifecycle-managed Android speech recognition with on-device preference and a disclosed session-only fallback.
- Encrypted backup codec separated from the GitHub transport.
- No backend, account, analytics, telemetry, contact scraping, automatic address-book import, autonomous outreach, or AI deletion.
