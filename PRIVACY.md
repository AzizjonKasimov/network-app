# Network App Privacy

Network App stores private information about people, conversations, needs, and capabilities in the app's local Room database. Android cloud backup, analytics, telemetry, advertising, accounts, contact scraping, and automatic address-book import are disabled or absent. The check-in reads contact names and chat senders only after the user turns it on, to ask about them, and never adds anyone by itself (see Check-in).

## Local operation

Manual person and record editing, local evidence-backed matching, browsing, archive/delete controls, and encrypted backup status work without the gateway. Stored records leave the phone only through a user-requested AI operation or the separately configured encrypted GitHub backup.

## The assistant

Recording, correcting, and asking share one chat thread on the **Assistant** tab. The first message asks, once, for permission for the assistant to read the network and to look people up on the web when asked; the permission is stored locally and can be revoked in Settings, and declining leaves manual editing and the **People** tab's local matching fully usable. Installs that granted the older permission, which did not cover the web, are asked again.

Each message starts an agent turn on the gateway. The request carries the message, the bounded conversation history described below, the current time, time zone, and locale, the assistant's instructions, and descriptions of the tools it may use. No stored records are included in that request.

The assistant then works through tool calls. The gateway hands each call back to the phone, the phone runs it against Room, and the result of that one call is sent back. Depending on what the assistant looks up, a result can contain names, the self marker, archived state, positions (organization, role, whether current, and whether study), education, background records, locations, relationship context, tags, profile notes, notes and their dates, needs, capabilities, record IDs, statuses, and dates. A result never contains a contact value: a person's profile says only whether one is saved. A contact value the user types into a message is part of that message and is sent, and the assistant may save it.

Tool results reach the gateway operator and, through them, Anthropic, in the same way as the message itself. The database is never uploaded as a whole, the gateway does not keep tool results after the turn, and backup secrets and the access token are never part of any tool result.

## Web lookups

When a message asks the assistant to look someone up, or includes a link, the assistant can also search the web and read pages. These two tools run on the gateway rather than the phone. Every turn offers them, and the assistant's instructions limit their use to lookups the user asks for.

- **Searches** go through Anthropic's web search. A query carries the person's name and the few details that tell them apart, such as their organization, role, or city. The instructions forbid putting anything else from the saved records into a query or a page address.
- **Pages** are fetched by the gateway, so a site sees the gateway's address and the page requested, not the phone. What a page says is summarised through Anthropic in the same way as the rest of the turn.
- **Which pages can be opened** is enforced by the gateway rather than left to the model. A page opens only when its address appeared in the user's message, or in a search result or page read earlier in the same turn. The model can never open an address it composed, so a planted page cannot get it to send records out inside a link. Raw IP addresses and private-network hosts are refused outright, and a turn allows at most ten lookups.

The card under the reply lists every search query and page address, including pages that could not be opened. The gateway's logs record only whether a turn allowed the web and how many lookups it made, never the queries or addresses.

What a lookup finds is saved on the phone like any other change and can be undone from the reply. It is saved as a note marked as found on the web, ending with the addresses of the pages used, and the records drawn from it are labelled as from the web. The instructions allow only work, study, location, and professional or public background to be saved. Contact details, family, health, beliefs, and other private details found online must not be saved. When a result could be a different person with the same name, the assistant must save nothing and ask. What the user said outranks what a page says.

LinkedIn refuses automated page reads, so a LinkedIn link is looked up through search results instead of the profile page.

## Changes the assistant makes

Adding people, notes, and records, changing profile fields and records, converting a record to another kind, archiving, and moving a note are written to Room as soon as the assistant makes them, each tool call in its own transaction. Every such write is listed under the reply that made it, and **Undo** puts all of them back together. Undo refuses rather than overwrite anything edited after the reply, and refuses to remove a person or note the reply created once other records depend on it. The record of what to undo lives only in memory with the thread.

Deleting a person, note, or record and merging two people are never written by the assistant. They are shown on a card under the reply and run only when the user confirms them there. They cannot be undone afterwards; restoring an earlier encrypted backup is the only way back.

Notes the assistant saves are marked as saved by the assistant, or as found on the web by it. Missing configuration, rejected requests, timeouts, quota limits, and offline failures stop the turn and are shown in the thread; anything already saved before the failure stays listed with its undo.

## Conversation history

Each message is sent with up to six earlier turns of the thread, capped at 3,000 characters, oldest dropped first. Only the user's messages and the assistant's replies are replayed. A reply is replayed together with a short list of what it changed, including record IDs, so a follow-up correction can reach the right record. Lines the app wrote itself and failed turns are not replayed.

History lives only in memory for the life of the thread; it is not written to Room or to backups, and starting a new thread discards it.

## Photos

The user can attach up to three photos to a message: chosen with Android's photo picker, taken with the camera app, or shared into Network App from another app. None of these needs a storage or camera permission: the picker and the share hand over only the photos the user chose, and the camera app writes into one file the app offers it.

A photo is sent only with the message it is attached to, when the user taps send. Before that, the phone decodes it at reduced size (at most 1568 pixels on the long side), turns it upright, and writes a new JPEG of at most about 350 KB from the pixels, so the original file's metadata, including any location, is not sent. The photo goes to the gateway and to Anthropic with that one turn, like the message text. The gateway logs only how many photos a turn had, and does not keep them.

Photos are held in memory only. They are not written to Room, not included in the encrypted backup, not replayed with later messages, and not copied into a reported answer; replayed history and reports say only that a photo was sent. A photo taken with the camera is deleted from the app's cache as soon as it has been read, and an abandoned capture is cleared by the next one.

A photo may show other people, such as a chat screenshot. The assistant's instructions limit what it saves to what concerns the people the message is about, forbid recognizing anyone by face or appearance, and treat text in a photo as data, never as instructions. A contact detail printed on a business card the user sent may be saved as that person's contact value, which then stays on the phone like any other. An address visible only in a photo cannot be opened by a web lookup.

The keyboard's own voice typing works in the message box; that is the keyboard's feature, governed by its own settings, and Network App never receives audio.

## Check-in

The **Check-in** tab asks about people the network may be missing and about saved details that may be stale. Each of its sources is off until the user turns it on there:

- **Phone contacts**, with Android's contacts permission. Only each contact's display name and Android's lookup key are read; numbers, emails, and all other fields are not.
- **Chat apps**, with Android's notification access. Android then shows the app every notification on the phone. The listener returns at once for every app except Telegram, WhatsApp, KakaoTalk, and LinkedIn. From those, it keeps only the sender's name, the app, and the time, for one-to-one conversations; group chats are skipped. Message text is never stored or logged. LinkedIn's notification text is read in memory only to tell a new connection apart from likes and job alerts.
- **Evening reminder**, with Android's notification permission. It shows how many questions are waiting, computed on the phone.

Questions about stale needs and positions and about recently added people without work or study are worked out from the saved records on the phone.

What the check-in collects (names, where and when they turned up, and the user's answers) is kept in a separate Room table on the phone. It is not included in the encrypted backup, the assistant's tools cannot read it, and nothing about it is sent to the gateway. A name leaves the phone only if the user taps **Add** or **Add a note** and then sends the message that starts, exactly like typing it. Turning a source off stops new collection; revoking contacts permission or notification access in Android settings does the same.

## Answers about the network

To answer a question, the assistant reads records through the same tool calls described above: a directory of people, records across the network (closed needs, inactive capabilities, past positions, and archived people only when it asks for them), keyword matches, notes in a date range, or one person in full. Tool results are paged, so a large network is read in parts rather than all at once. Answers are suggestions based on saved records, not facts or proof of willingness or availability. Network App never contacts or introduces anyone.

## Reported assistant answers

Reporting a wrong answer stores a copy of your message, the assistant's answer, what the reply saved or queued, and the tool calls it made, including its web searches and page addresses, in a local database table. Nothing is transmitted when a report is saved. Contact values are never copied into a report: a tool call that set a contact value is recorded without the value.

Reports are included in the encrypted GitHub backup, alongside people and their records and under the same AES-256-GCM encryption and passphrase. They leave the phone only when a backup runs, and only to the private repository you configured; the app still refuses to back up to a public repository. Nothing about a report is sent to the AI gateway.

Reading a report on another machine means decrypting that backup, which requires the backup passphrase and therefore also exposes the rest of its contents to whoever holds it. `scripts/read-feedback.ps1` writes only the reports to disk and keeps the rest in memory, but the passphrase itself remains the thing to protect.

Deleting a report, or all of them, removes it from the database and marks a backup as needed so the next backup no longer carries it. People and records are unaffected.

## Credentials and provider processing

The access token is entered after installation and stored in Android encrypted preferences. It is excluded from Room and encrypted GitHub backups. A secret stored on a mobile device may still be extracted from a rooted, compromised, or reverse-engineered device; issue one token per device and revoke it on the gateway if a device is lost.

The development-only live test uses the same encrypted preferences: the token is saved once through the app's Settings screen and read from there at runtime. No copy is written to `.env`, staged on the device filesystem, or passed as a Gradle or command-line argument, and the test exercises only synthetic records. The token must never appear in source, Gradle arguments, command text, test reports, screenshots, or diagnostic UI dumps. `.env` and `.env.*` remain gitignored.

Request content is processed by the gateway operator and then by Anthropic under their respective terms and retention policies; web search queries are also handled by the search provider behind Anthropic's web search, and the sites whose pages are read receive those requests from the gateway. The gateway is a small self-hosted service rather than a published vendor, so its logging and retention are the operator's responsibility. Do not submit network information that the user is unwilling or unauthorized to send to those parties.

## Failure and deletion

Missing keys, rejected requests, invalid responses, unknown record IDs, quota limits, timeouts, and offline failures do not trigger fallback writes. A photo that cannot be read is left out with a message. The draft remains editable, and local search remains available. Removing the access token or revoking the assistant's permission stops later AI requests but does not delete requests already processed by the gateway operator or Anthropic.

Deleting a person locally cascades to linked interactions, needs, capabilities, positions, education, and background records. Deleting a source note clears provenance links without deleting the records derived from it. Encrypted GitHub backups retain data until replaced or deleted from the configured private repository.
