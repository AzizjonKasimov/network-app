package com.azizjon.network.backup

import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class EncryptedBackupCodecTest {
    @Test
    fun encryptedBackupRoundTripsAllRecordTypes() {
        val original = sampleSnapshot()

        val encrypted = EncryptedBackupCodec.encode(original, "correct horse battery staple")
        val restored = EncryptedBackupCodec.decode(encrypted, "correct horse battery staple")

        assertEquals(original, restored)
        assertTrue("Sample Person" !in encrypted)
        assertTrue("Private conversation" !in encrypted)
    }

    @Test
    fun wrongPassphraseCannotDecryptBackup() {
        val encrypted = EncryptedBackupCodec.encode(sampleSnapshot(), "correct horse battery staple")

        assertThrows(BackupCodecException::class.java) {
            EncryptedBackupCodec.decode(encrypted, "this is the wrong passphrase")
        }
    }

    @Test
    fun versionOneSnapshotDefaultsNewLifecycleAndProvenanceFields() {
        val v1 = JSONObject()
            .put("schemaVersion", 1)
            .put("people", JSONArray().put(JSONObject()
                .put("id", 1).put("name", "Legacy Person").put("createdAt", 1).put("updatedAt", 2)))
            .put("interactions", JSONArray().put(JSONObject()
                .put("id", 2).put("personId", 1).put("note", "Legacy note").put("occurredAt", 3).put("createdAt", 4)))
            .put("needs", JSONArray().put(JSONObject()
                .put("id", 3).put("personId", 1).put("text", "Legacy need").put("status", "active")
                .put("lastConfirmedAt", 5).put("createdAt", 6)))
            .put("capabilities", JSONArray().put(JSONObject()
                .put("id", 4).put("personId", 1).put("text", "Legacy skill")
                .put("lastConfirmedAt", 7).put("createdAt", 8)))

        val restored = EncryptedBackupCodec.snapshotFromJson(v1.toString())

        assertEquals(InteractionEntity.ORIGIN_MANUAL, restored.interactions.single().origin)
        assertEquals(null, restored.needs.single().sourceInteractionId)
        assertTrue(restored.capabilities.single().active)
        assertEquals(null, restored.capabilities.single().sourceInteractionId)
    }

    private fun sampleSnapshot(): NetworkSnapshot {
        val person = PersonEntity(id = 1, name = "Sample Person", createdAt = 1, updatedAt = 2)
        return NetworkSnapshot(
            people = listOf(person),
            interactions = listOf(
                InteractionEntity(2, 1, "Private conversation", 3, 4, InteractionEntity.ORIGIN_AI_REVIEWED),
            ),
            needs = listOf(NeedEntity(3, 1, "Find a designer", "active", 5, 6, sourceInteractionId = 2)),
            capabilities = listOf(CapabilityEntity(4, 1, "Kotlin mentoring", 7, 8, active = false, sourceInteractionId = 2)),
        )
    }
}
