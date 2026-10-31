package com.azizjon.network.inbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InboxCodecTest {
    /** A synthetic key, and a draft sealed to it by `scripts/queue-draft.ps1 -PublicKeyFile` on Windows. */
    private val scriptKey = InboxKey(
        publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMuT+6GeshwVOnbYQ6QesFJfmJHxf4a2j8emKClQgFlHs0Avjz1zhyV5joM8/ZQUKsuHTp7rxcCMCte0xcJGbnA==",
        privateKey = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgWnZImMagzJHduY3LemqoDjuEonfpZRnq7+GD7J2FntWhRANCAAQy5P7oZ6yHBU6dthDpB6wUl+YkfF/hraPx6YoKVCAWUezQC+PPXOHJXmOgzz9lBQqy4dOnuvFwIwK17TFwkZuc",
        folder = "network-backup-inbox",
        createdAt = 1_761_868_800_000,
    )
    private val scriptEnvelope = """{"format":"network-app-inbox-draft","version":1,"keyId":"10ad30724bd42974","ephemeralKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyOuJeDQ16Kfll6FMX7IqxaP9Xw2rE7jYYm0jNaPgjsQmuYX6TNHzt/6TNIVIlG5i36l1oTY2/1o2muST51WUNQ==","iv":"cpmFBj6c4c1vD1dx","ciphertext":"rH4ApSHMiEB8GAuvKVF0oa50EWSjb4tEV1fo8JXm36wPB1/SJeStA/jy8XMXOIYuZyMyn7vNoO7NaC4yWevJlLHOb8tkAmtoTLSCFdshri61IuQeiEcppk4ce3uKltvpuVfkIBh60OR35LZP7z5ykLoe/SdDui4TjOgsN884O180GnD0wEaXwh96F2Xdr4Rk6XavxPQzFKBq6A8l7JEbfV2qtcRG8EyUEw8jDmgzpAZWJuTUutHzvtCN4xPOjXKsEClthEtR6uNBkpnJ6r0W1slj85XZY60FGwr8LaOk0ClT61hQ1ncUovEghScZBIBm/Ewx5qJsFDzEFm2xCGBdLWmBgl0x"}"""

    @Test
    fun opensADraftSealedByThePcScript() {
        val draft = InboxCodec.open(scriptEnvelope, scriptKey)

        // The script turned the CRLF into a newline and trimmed the trailing one.
        assertEquals(
            "On 2026-10-21 at a meetup I talked with Jane Doe (synthetic) about her move to Seoul.\n" +
                "She said \"call me Jay\" and wrote back in Korean: 서울에서 만나요 ✓ 🙂",
            draft.text,
        )
        assertTrue(Regex("^[0-9a-f]{32}$").matches(draft.id))
        assertTrue(draft.queuedAt > 1_761_868_800_000)
    }

    @Test
    fun keyIdAndFingerprintMatchWhatTheScriptComputes() {
        assertEquals("10ad30724bd42974", scriptKey.keyId)
        assertEquals("10AD 3072 4BD4 2974", scriptKey.fingerprint)
    }

    @Test
    fun roundTripsADraftSealedOnTheJvm() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)
        val id = draftId(7)

        val draft = InboxCodec.open(seal("Met Dana Whitfield at Lumen Labs.", key.publicKey, id, 42), key)

        assertEquals(InboxDraft(id, "Met Dana Whitfield at Lumen Labs.", 42), draft)
    }

    @Test
    fun theEnvelopeCarriesNoReadableText() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)

        val envelope = seal("Priya Raman needs a bookkeeper", key.publicKey)

        assertFalse("Priya" in envelope)
        assertFalse("bookkeeper" in envelope)
    }

    @Test
    fun aDraftForAnEarlierKeyIsRefusedByName() {
        val earlier = InboxCodec.newKey("network-backup-inbox", createdAt = 1)
        val current = InboxCodec.newKey("network-backup-inbox", createdAt = 2)

        val error = assertThrows(InboxCodecException::class.java) {
            InboxCodec.open(seal("Hello", earlier.publicKey), current)
        }
        assertEquals("it was made for an earlier key of this phone", error.message)
    }

    @Test
    fun aDraftClaimingThisKeyButSealedToAnotherIsRefused() {
        val other = InboxCodec.newKey("network-backup-inbox", createdAt = 1)
        val mine = InboxCodec.newKey("network-backup-inbox", createdAt = 2)
        val forged = JSONObject(seal("Hello", other.publicKey)).put("keyId", mine.keyId).toString()

        val error = assertThrows(InboxCodecException::class.java) { InboxCodec.open(forged, mine) }
        assertEquals("it is damaged or was not made for this phone", error.message)
    }

    @Test
    fun aTamperedCiphertextIsRefused() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)
        val json = JSONObject(seal("Hello", key.publicKey))
        val bytes = Base64.getDecoder().decode(json.getString("ciphertext"))
        bytes[3] = (bytes[3].toInt() xor 1).toByte()
        json.put("ciphertext", Base64.getEncoder().encodeToString(bytes))

        assertThrows(InboxCodecException::class.java) { InboxCodec.open(json.toString(), key) }
    }

    @Test
    fun unknownFormatsAndDamagedFilesAreRefused() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)
        val newer = JSONObject(seal("Hello", key.publicKey)).put("version", 2).toString()

        assertEquals(
            "it was made by a newer or unknown version of the PC script",
            assertThrows(InboxCodecException::class.java) { InboxCodec.open(newer, key) }.message,
        )
        assertEquals("it is damaged", assertThrows(InboxCodecException::class.java) { InboxCodec.open("not json", key) }.message)
    }

    @Test
    fun anOversizeEnvelopeIsRefusedBeforeAnyWork() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)

        val error = assertThrows(InboxCodecException::class.java) {
            InboxCodec.open("x".repeat(InboxCodec.MAX_ENVELOPE_BYTES + 1), key)
        }
        assertEquals("it is too large", error.message)
    }

    @Test
    fun aDraftMustFitInOneMessageAndSaySomething() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 1)

        assertThrows(InboxCodecException::class.java) { InboxCodec.open(seal("x".repeat(4_001), key.publicKey), key) }
        assertThrows(InboxCodecException::class.java) { InboxCodec.open(seal("   ", key.publicKey), key) }
        assertEquals(4_000, InboxCodec.open(seal("x".repeat(4_000), key.publicKey), key).text.length)
    }

    @Test
    fun theKeyDocumentPublishesOnlyThePublicHalf() {
        val key = InboxCodec.newKey("network-backup-inbox", createdAt = 5)

        val document = InboxCodec.keyDocument(key)

        assertFalse(key.privateKey in document)
        assertEquals(key.publicKey, InboxCodec.publishedKey(document))
        assertEquals(key.keyId, JSONObject(document).getString("keyId"))
        assertNull(InboxCodec.publishedKey("""{"format":"something-else","version":1,"publicKey":"x"}"""))
        assertNull(InboxCodec.publishedKey("not json"))
    }

    @Test
    fun theFolderFollowsTheBackupPath() {
        assertEquals("network-backup-inbox", PcInbox.folder("network-backup.enc.json"))
        assertEquals("emulator-test-backup-inbox", PcInbox.folder("emulator-test-backup.enc.json"))
        assertEquals("backups/phone-inbox", PcInbox.folder("/backups/phone.json"))
        assertEquals("plain-inbox", PcInbox.folder(" plain "))
    }

    @Test
    fun stateSurvivesItsJson() {
        val state = InboxState(
            key = InboxCodec.newKey("network-backup-inbox", createdAt = 3).copy(remoteSha = "abc"),
            pending = listOf(InboxDraft(draftId(1), "Line one\nLine \"two\" 서울", 9)),
            seen = listOf(draftId(1), draftId(2)),
            lastCheckAt = 11,
            lastError = "GitHub HTTP 503",
        )

        assertEquals(state, InboxState.fromJson(state.toJson()))
        assertEquals(InboxState(), InboxState.fromJson(InboxState().toJson()))
    }
}
