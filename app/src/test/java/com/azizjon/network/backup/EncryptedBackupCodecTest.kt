package com.azizjon.network.backup

import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.FactEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class EncryptedBackupCodecTest {
    @Test
    fun encryptedBackupRoundTripsAllRecordTypes() {
        val original = BackupPayload(sampleSnapshot())

        val encrypted = EncryptedBackupCodec.encode(original, "correct horse battery staple")
        val restored = EncryptedBackupCodec.decode(encrypted, "correct horse battery staple")

        assertEquals(original, restored)
        assertTrue("Sample Person" !in encrypted)
        assertTrue("Private conversation" !in encrypted)
    }

    @Test
    fun wrongPassphraseCannotDecryptBackup() {
        val encrypted = EncryptedBackupCodec.encode(BackupPayload(sampleSnapshot()), "correct horse battery staple")

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

        val restored = EncryptedBackupCodec.payloadFromJson(v1.toString()).snapshot

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

        val restored = EncryptedBackupCodec.payloadFromJson(v2.toString()).snapshot

        val affiliation = restored.affiliations.single()
        assertEquals(1L, affiliation.personId)
        assertEquals("Legacy Corp", affiliation.organization)
        assertEquals("Legacy role", affiliation.role)
        assertTrue(affiliation.current)
    }

    @Test
    fun severalConcurrentPositionsSurviveABackupRoundTrip() {
        val snapshot = sampleSnapshot()

        val restored = EncryptedBackupCodec.payloadFromJson(EncryptedBackupCodec.payloadToJson(BackupPayload(snapshot))).snapshot

        assertEquals(3, restored.affiliations.size)
        assertEquals(
            listOf("Northwind Labs" to true, "Former Co" to false, "Sample University" to false),
            restored.affiliations.map { it.organization to it.current },
        )
        assertTrue(restored.affiliations.single { it.organization == "Sample University" }.isEducation)
        assertEquals("Built a widely used sample app", restored.facts.single().text)
    }

    @Test
    fun backupsWrittenBeforeBackgroundFactsRestoreWithNone() {
        // Nothing to rebuild: earlier versions had no way to record one.
        val v3 = JSONObject()
            .put("schemaVersion", 3)
            .put("people", JSONArray().put(JSONObject()
                .put("id", 1).put("name", "Legacy Person").put("createdAt", 1).put("updatedAt", 2)))
            .put("interactions", JSONArray())
            .put("needs", JSONArray())
            .put("capabilities", JSONArray())
            .put("affiliations", JSONArray().put(JSONObject()
                .put("id", 5).put("personId", 1).put("organization", "Legacy Corp").put("role", "Engineer")
                .put("current", true).put("lastConfirmedAt", 3).put("createdAt", 4)))

        val restored = EncryptedBackupCodec.payloadFromJson(v3.toString()).snapshot

        assertTrue(restored.facts.isEmpty())
        // A position stored before the work/education split is work.
        assertFalse(restored.affiliations.single().isEducation)
    }

    /**
     * The whole point of moving reports into the backup: they have to survive
     * the trip, and they must not be readable in the stored envelope.
     */
    @Test
    fun reportedAnswersTravelInsideTheEncryptedBackup() {
        val payload = BackupPayload(
            snapshot = sampleSnapshot(),
            feedback = listOf(
                AiFeedbackEntity(
                    id = 1,
                    stage = AiFeedbackEntity.Stage.PROPOSAL,
                    label = "wrong_target",
                    note = "That belonged to somebody else",
                    userMessage = "Private conversation about Sample Person",
                    assistantMessage = "I prepared changes",
                    assistantDetail = "Proposed changes for: Sample Person",
                    appVersion = "0.11.0 (12)",
                    createdAt = 20,
                ),
            ),
        )

        val encrypted = EncryptedBackupCodec.encode(payload, "correct horse battery staple")
        val restored = EncryptedBackupCodec.decode(encrypted, "correct horse battery staple")

        assertEquals(payload, restored)
        assertEquals("wrong_target", restored.feedback.single().label)
        assertTrue("wrong_target" !in encrypted)
        assertTrue("belonged to somebody else" !in encrypted)
    }

    @Test
    fun backupsWrittenBeforeReportsRestoreWithNone() {
        val v4 = JSONObject()
            .put("schemaVersion", 4)
            .put("people", JSONArray().put(JSONObject()
                .put("id", 1).put("name", "Legacy Person").put("createdAt", 1).put("updatedAt", 2)))
            .put("interactions", JSONArray())
            .put("needs", JSONArray())
            .put("capabilities", JSONArray())
            .put("affiliations", JSONArray())
            .put("facts", JSONArray())

        val restored = EncryptedBackupCodec.payloadFromJson(v4.toString())

        assertTrue(restored.feedback.isEmpty())
        assertEquals("Legacy Person", restored.snapshot.people.single().name)
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
                AffiliationEntity(
                    7, 1, "Sample University", "PhD", current = false, lastConfirmedAt = 13, createdAt = 14,
                    kind = AffiliationEntity.KIND_EDUCATION,
                ),
            ),
            facts = listOf(FactEntity(8, 1, "Built a widely used sample app", 15, 16, sourceInteractionId = 2)),
        )
    }
}
