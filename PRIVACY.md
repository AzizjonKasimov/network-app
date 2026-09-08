# Network App Privacy

Network App stores private information about people, conversations, needs, and capabilities in the app's local Room database. Android cloud backup, analytics, telemetry, advertising, accounts, contact scraping, and automatic address-book import are disabled or absent.

## Local operation

Manual person and record editing, local evidence-backed matching, browsing, archive/delete controls, and encrypted backup status work without the gateway. Stored records leave the phone only through a user-requested AI operation or the separately configured encrypted GitHub backup.

## AI capture and updates

Capture, correction, and search share one chat thread on the **Assistant** tab. When the user sends a message, the app sends that message, the bounded conversation history described below, current time, time zone, and locale to decide whether the message is a capture or a search and, for a capture, to identify one target person. No stored network records are included in that first request. When the message plainly names one saved person and does not read as a question, the phone resolves the target offline and skips this request entirely.

After the target is resolved or selected, the proposal request sends the message and only that person's name, positions (organization, role, whether current, and whether study), background records, location, relationship context, tags, profile notes, interactions, needs, capabilities, record IDs, statuses, and dates. The existing contact value, other people, archived state, self marker, backup secrets, and access token are not placed in the prompt. A newly typed contact value is part of the message and therefore is sent if the user explicitly includes it.

The gateway returns a structured proposal. The app validates its schema, lengths, counts, duplicate changes, and record IDs and shows an editable proposal card in the thread. The proposal separately lists explicit facts kept only in the original verbatim interaction rather than mapped into structured fields or records. Nothing is written before confirmation. Applying the proposal stores the original message verbatim as an AI-reviewed interaction and applies the selected changes in one Room transaction.

## Follow-up corrections

While a proposal is open, the next message refines it instead of starting a new capture. That request sends the same single-person context plus the current proposal, with items the user has already unchecked omitted so they are not proposed again. A refinement stays on one person, is validated identically, and still requires confirmation. Discarding a proposal writes nothing.

## Conversation history

One thread mixes two different disclosure scopes: a capture sends one person's records, a search sends the whole active network. Replayed history is therefore scoped to the narrower of the two.

A capture or refinement request replays only the user's own turns and the assistant's capture replies. It never replays a search answer, because a search answer is built from the whole network and would widen a request the user approved as single-person. A search request may replay any earlier turn, because the same request already carries every active person.

Replayed history is capped at six turns and 2,000 characters, oldest dropped first. History lives only in memory for the life of the thread; it is not written to Room or to backups, and starting a new thread discards it.

## Voice input

Voice input is available only for the unsent chat message. The app requests Android's `RECORD_AUDIO` permission only after the microphone control is tapped. It prefers an on-device speech recognizer when Android reports one is available.

When on-device recognition is unavailable, the app does not silently switch providers. It explains that Android's configured speech provider may transmit audio to remote servers and asks the user to allow that fallback for the current app session. This consent is held only in process memory, is not included in Room or backups, and resets when the app process restarts.

Network App does not create or retain audio files, write speech or transcripts to logs, or add them to backups. Partial recognition is displayed only while listening. The final transcript is appended to the editable field and can be changed or discarded. A transcript is sent to the gateway only if the user later sends the message; voice input itself never submits or saves anything.

## AI search

The first message that routes to search requires acknowledgement that each search sends the full active searchable network to the gateway operator and, through them, to Anthropic. The request includes:

- names and the self marker;
- positions with their organizations, roles, current/past state, and whether they are study;
- background records;
- locations, relationship context, tags, and profile notes;
- interactions and their dates;
- active needs and active capabilities with IDs and dates;
- the user's search question and the replayed turns described under **Conversation history**.

It excludes contact values, archived people, closed needs, inactive capabilities, gateway and GitHub credentials, and the backup passphrase. Declining the disclosure leaves local matching on the **People** tab fully usable. The app refuses to send a corpus larger than 1 MiB rather than silently truncating it. Consent is stored locally and can be revoked in Settings.

The assistant must return existing person and evidence IDs. The app rejects unknown or mismatched IDs and always displays exact stored evidence and dates. AI results are suggestions, not facts or proof of willingness or availability. Network App never contacts or introduces anyone automatically.

## Credentials and provider processing

The access token is entered after installation and stored in Android encrypted preferences. It is excluded from Room and encrypted GitHub backups. A secret stored on a mobile device may still be extracted from a rooted, compromised, or reverse-engineered device; issue one token per device and revoke it on the gateway if a device is lost.

The development-only live test uses the same encrypted preferences: the token is saved once through the app's Settings screen and read from there at runtime. No copy is written to `.env`, staged on the device filesystem, or passed as a Gradle or command-line argument, and the test exercises only synthetic records. The token must never appear in source, Gradle arguments, command text, test reports, screenshots, or diagnostic UI dumps. `.env` and `.env.*` remain gitignored.

Request content is processed by the gateway operator and then by Anthropic under their respective terms and retention policies. The gateway is a small self-hosted service rather than a published vendor, so its logging and retention are the operator's responsibility. Do not submit network information that the user is unwilling or unauthorized to send to those parties.

## Failure and deletion

Missing keys, rejected requests, invalid responses, unknown record IDs, quota limits, timeouts, and offline failures do not trigger fallback writes. Speech permission denial, unavailable recognition, no-match results, and speech-provider failures also leave existing text unchanged. The draft remains editable, and local search remains available. Removing the access token or revoking search consent stops later AI requests but does not delete requests already processed by the gateway operator or Anthropic.

Deleting a person locally cascades to linked interactions, needs, and capabilities. Deleting an AI-reviewed source interaction clears provenance links without deleting the derived records. Encrypted GitHub backups retain data until replaced or deleted from the configured private repository.
