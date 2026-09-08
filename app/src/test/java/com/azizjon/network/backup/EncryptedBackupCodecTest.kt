package com.azizjon.network.backup

import com.azizjon.network.data.AffiliationEntity
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

    @Test
    fun olderBackupsRebuildTheStoredJobAsAPosition() {
        // Backups written before positions existed kept one organization and role
        // on the person. Restoring one must not lose that.
        val v2 = JSONObject()
            .put("schemaVersion", 2)
            .put("people", JSONArray().put(JSONObject()
                .put("id", 1).put("name", "Legacy Person")
                .put("organization", "Legacy Corp").put("role", "Legacy role")
                .put("createdAt", 1).put("updatedAt", 2)))
            .put("interactions", JSONArray())
            .put("needs", JSONArray())
            .put("capabilities", JSONArray())

        val restored = EncryptedBackupCodec.snapshotFromJson(v2.toString())

        val affiliation = restored.affiliations.single()
        assertEquals(1L, affiliation.personId)
        assertEquals("Legacy Corp", affiliation.organization)
        assertEquals("Legacy role", affiliation.role)
        assertTrue(affiliation.current)
    }

    @Test
    fun severalConcurrentPositionsSurviveABackupRoundTrip() {
        val snapshot = sampleSnapshot()

        val restored = EncryptedBackupCodec.snapshotFromJson(EncryptedBackupCodec.snapshotToJson(snapshot))

        assertEquals(2, restored.affiliations.size)
        assertEquals(
            listOf("Northwind Labs" to true, "Former Co" to false),
            restored.affiliations.map { it.organization to it.current },
        )
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
            affiliations = listOf(
                AffiliationEntity(5, 1, "Northwind Labs", "CEO", current = true, lastConfirmedAt = 9, createdAt = 10),
                AffiliationEntity(6, 1, "Former Co", "Engineer", current = false, lastConfirmedAt = 11, createdAt = 12),
            ),
        )
    }
}
