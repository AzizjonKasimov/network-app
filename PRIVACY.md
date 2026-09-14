# Network App Privacy

Network App stores private information about people, conversations, needs, and capabilities in the app's local Room database. Android cloud backup, analytics, telemetry, advertising, accounts, contact scraping, and automatic address-book import are disabled or absent.

## Local operation

Manual person and record editing, local evidence-backed matching, browsing, archive/delete controls, and encrypted backup status work without the gateway. Stored records leave the phone only through a user-requested AI operation or the separately configured encrypted GitHub backup.

## The assistant

Recording, correcting, and asking share one chat thread on the **Assistant** tab. The first message asks, once, for permission for the assistant to read the network; the permission is stored locally and can be revoked in Settings, and declining leaves manual editing and the **People** tab's local matching fully usable.

Each message starts an agent turn on the gateway. The request carries the message, the bounded conversation history described below, the current time, time zone, and locale, the assistant's instructions, and descriptions of the tools it may use. No stored records are included in that request.

The assistant then works through tool calls. The gateway hands each call back to the phone, the phone runs it against Room, and the result of that one call is sent back. Depending on what the assistant looks up, a result can contain names, the self marker, archived state, positions (organization, role, whether current, and whether study), education, background records, locations, relationship context, tags, profile notes, notes and their dates, needs, capabilities, record IDs, statuses, and dates. A result never contains a contact value: a person's profile says only whether one is saved. A contact value the user types into a message is part of that message and is sent, and the assistant may save it.

Tool results reach the gateway operator and, through them, Anthropic, in the same way as the message itself. The database is never uploaded as a whole, the gateway does not keep tool results after the turn, and backup secrets and the access token are never part of any tool result.

## Changes the assistant makes

Adding people, notes, and records, changing profile fields and records, converting a record to another kind, archiving, and moving a note are written to Room as soon as the assistant makes them, each tool call in its own transaction. Every such write is listed under the reply that made it, and **Undo** puts all of them back together. Undo refuses rather than overwrite anything edited after the reply, and refuses to remove a person or note the reply created once other records depend on it. The record of what to undo lives only in memory with the thread.

Deleting a person, note, or record and merging two people are never written by the assistant. They are shown on a card under the reply and run only when the user confirms them there. They cannot be undone afterwards; restoring an earlier encrypted backup is the only way back.

Notes the assistant saves are marked as saved by the assistant. Missing configuration, rejected requests, timeouts, quota limits, and offline failures stop the turn and are shown in the thread; anything already saved before the failure stays listed with its undo.

## Conversation history

Each message is sent with up to six earlier turns of the thread, capped at 3,000 characters, oldest dropped first. Only the user's messages and the assistant's replies are replayed. A reply is replayed together with a short list of what it changed, including record IDs, so a follow-up correction can reach the right record. Lines the app wrote itself and failed turns are not replayed.

History lives only in memory for the life of the thread; it is not written to Room or to backups, and starting a new thread discards it.

## Voice input

Voice input is available only for the unsent chat message. The app requests Android's `RECORD_AUDIO` permission only after the microphone control is tapped. It prefers an on-device speech recognizer when Android reports one is available.

When on-device recognition is unavailable, the app does not silently switch providers. It explains that Android's configured speech provider may transmit audio to remote servers and asks the user to allow that fallback for the current app session. This consent is held only in process memory, is not included in Room or backups, and resets when the app process restarts.

Network App does not create or retain audio files, write speech or transcripts to logs, or add them to backups. Partial recognition is displayed only while listening. The final transcript is appended to the editable field and can be changed or discarded. A transcript is sent to the gateway only if the user later sends the message; voice input itself never submits or saves anything.

## Answers about the network

To answer a question, the assistant reads records through the same tool calls described above: a directory of people, records across the network (closed needs, inactive capabilities, past positions, and archived people only when it asks for them), keyword matches, notes in a date range, or one person in full. Tool results are paged, so a large network is read in parts rather than all at once. Answers are suggestions based on saved records, not facts or proof of willingness or availability. Network App never contacts or introduces anyone.

## Reported assistant answers

Reporting a wrong answer stores a copy of your message, the assistant's answer, what the reply saved or queued, and the tool calls it made, in a local database table. Nothing is transmitted when a report is saved. Contact values are never copied into a report: a tool call that set a contact value is recorded without the value.

Reports are included in the encrypted GitHub backup, alongside people and their records and under the same AES-256-GCM encryption and passphrase. They leave the phone only when a backup runs, and only to the private repository you configured; the app still refuses to back up to a public repository. Nothing about a report is sent to the AI gateway.

Reading a report on another machine means decrypting that backup, which requires the backup passphrase and therefore also exposes the rest of its contents to whoever holds it. `scripts/read-feedback.ps1` writes only the reports to disk and keeps the rest in memory, but the passphrase itself remains the thing to protect.

Deleting a report, or all of them, removes it from the database and marks a backup as needed so the next backup no longer carries it. People and records are unaffected.

## Credentials and provider processing

The access token is entered after installation and stored in Android encrypted preferences. It is excluded from Room and encrypted GitHub backups. A secret stored on a mobile device may still be extracted from a rooted, compromised, or reverse-engineered device; issue one token per device and revoke it on the gateway if a device is lost.

The development-only live test uses the same encrypted preferences: the token is saved once through the app's Settings screen and read from there at runtime. No copy is written to `.env`, staged on the device filesystem, or passed as a Gradle or command-line argument, and the test exercises only synthetic records. The token must never appear in source, Gradle arguments, command text, test reports, screenshots, or diagnostic UI dumps. `.env` and `.env.*` remain gitignored.

Request content is processed by the gateway operator and then by Anthropic under their respective terms and retention policies. The gateway is a small self-hosted service rather than a published vendor, so its logging and retention are the operator's responsibility. Do not submit network information that the user is unwilling or unauthorized to send to those parties.

## Failure and deletion

Missing keys, rejected requests, invalid responses, unknown record IDs, quota limits, timeouts, and offline failures do not trigger fallback writes. Speech permission denial, unavailable recognition, no-match results, and speech-provider failures also leave existing text unchanged. The draft remains editable, and local search remains available. Removing the access token or revoking the assistant's permission stops later AI requests but does not delete requests already processed by the gateway operator or Anthropic.

Deleting a person locally cascades to linked interactions, needs, capabilities, positions, education, and background records. Deleting a source note clears provenance links without deleting the records derived from it. Encrypted GitHub backups retain data until replaced or deleted from the configured private repository.
