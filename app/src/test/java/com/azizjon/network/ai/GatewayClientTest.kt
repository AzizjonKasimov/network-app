package com.azizjon.network.ai

import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GatewayClientTest {
    @Test
    fun requestsUseTheGatewayEnvelopeWithBoundedBudgets() {
        val body = GatewayClient.requestBody(
            systemInstruction = "Synthetic instruction",
            userPayload = JSONObject().put("value", "Synthetic payload"),
            schema = JSONObject().put("type", "object"),
            maxOutputTokens = GatewayClient.PROPOSAL_MAX_OUTPUT_TOKENS,
        )

        assertEquals("Synthetic instruction", body.getString("system"))
        assertEquals("object", body.getJSONObject("schema").getString("type"))
        assertEquals(GatewayClient.PROPOSAL_MAX_OUTPUT_TOKENS, body.getInt("max_tokens"))
        // The payload travels as a JSON string in one user turn.
        assertEquals("Synthetic payload", JSONObject(body.getString("input")).getString("value"))
        // Nothing provider-specific should survive the migration.
        assertFalse(body.has("generationConfig"))
        assertFalse(body.has("contents"))
        assertFalse(body.has("systemInstruction"))
        assertEquals(1_024, GatewayClient.TARGET_MAX_OUTPUT_TOKENS)
        assertEquals(4_096, GatewayClient.PROPOSAL_MAX_OUTPUT_TOKENS)
        assertEquals(2_048, GatewayClient.SEARCH_MAX_OUTPUT_TOKENS)
        assertTrue(GatewayClient.TARGET_MAX_OUTPUT_TOKENS < GatewayClient.PROPOSAL_MAX_OUTPUT_TOKENS)
        assertTrue(GatewayClient.SEARCH_MAX_OUTPUT_TOKENS <= GatewayClient.PROPOSAL_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun proposalSchemaKeepsRequiredContractWithoutRedundantProviderConstraints() {
        val schema = GatewayClient.proposalSchema()
        val required = schema.getJSONArray("required")
        val requiredNames = (0 until required.length()).map(required::getString)
        val properties = schema.getJSONObject("properties")

        assertEquals(
            listOf(
                "occurredAt", "interactionOnlyFacts", "profilePatches", "newNeeds", "newCapabilities",
                "interactionEdits", "needEdits", "capabilityEdits", "warning",
            ),
            requiredNames,
        )
        assertFalse(schema.has("additionalProperties"))
        assertFalse(properties.getJSONObject("interactionOnlyFacts").has("maxItems"))
        assertFalse(
            properties.getJSONObject("profilePatches")
                .getJSONObject("items")
                .getJSONObject("properties")
                .getJSONObject("field")
                .has("enum"),
        )
        assertEquals(
            listOf("string", "null"),
            properties.getJSONObject("warning").getJSONArray("type").let { types ->
                (0 until types.length()).map(types::getString)
            },
        )
    }

    @Test
    fun searchCorpusExcludesContactsArchivedClosedAndInactiveRecords() {
        val active = person(1, "Active Person", contact = "secret@example.invalid")
        val archived = person(2, "Archived Secret", archived = true)
        val snapshot = NetworkSnapshot(
            people = listOf(active, archived),
            interactions = listOf(
                InteractionEntity(10, 1, "Useful discussion", 100, 100),
                InteractionEntity(11, 2, "Archived discussion", 100, 100),
            ),
            needs = listOf(
                NeedEntity(20, 1, "Active need", NeedEntity.STATUS_ACTIVE, 100, 100),
                NeedEntity(21, 1, "Closed secret", NeedEntity.STATUS_CLOSED, 100, 100),
            ),
            capabilities = listOf(
                CapabilityEntity(30, 1, "Active capability", 100, 100),
                CapabilityEntity(31, 1, "Inactive secret", 100, 100, active = false),
            ),
        )

        val corpus = GatewayClient.buildSearchCorpus(snapshot)

        assertTrue("Active Person" in corpus.json)
        assertTrue("Active need" in corpus.json)
        assertTrue("Active capability" in corpus.json)
        assertFalse("secret@example.invalid" in corpus.json)
        assertFalse("Archived Secret" in corpus.json)
        assertFalse("Closed secret" in corpus.json)
        assertFalse("Inactive secret" in corpus.json)
    }

    @Test
    fun searchResponseMustUseKnownPersonAndEvidenceIds() {
        val snapshot = NetworkSnapshot(people = listOf(person(1, "Sample Person")))
        val corpus = GatewayClient.buildSearchCorpus(snapshot)
        val validPayload = JSONObject().put(
            "results",
            JSONArray().put(JSONObject()
                .put("personId", 1)
                .put("evidenceIds", JSONArray().put("profile:1"))
                .put("reasoning", "The stored profile matches.")
                .put("uncertainty", "Profile evidence may be old.")),
        )

        val result = GatewayClient.parseSearchResponse(wrap(validPayload), corpus).single()

        assertEquals("Sample Person", result.person.name)
        assertEquals("profile:1", result.evidence.single().id)

        validPayload.getJSONArray("results").getJSONObject(0).put("personId", 999)
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseSearchResponse(wrap(validPayload), corpus)
        }
    }

    @Test
    fun proposalRejectsRecordIdsOutsideSelectedPerson() {
        val selected = person(1, "Selected Person")
        val other = person(2, "Other Person")
        val snapshot = NetworkSnapshot(
            people = listOf(selected, other),
            needs = listOf(NeedEntity(22, 2, "Other need", NeedEntity.STATUS_ACTIVE, 100, 100)),
        )
        val payload = emptyProposalPayload().put(
            "needEdits",
            JSONArray().put(JSONObject()
                .put("id", 22)
                .put("text", "Changed")
                .put("status", "closed")
                .put("lastConfirmedAt", "2026-08-26T00:00:00Z")),
        )

        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                responseBody = wrap(payload),
                rawInput = "Synthetic update",
                targetName = selected.name,
                person = selected,
                snapshot = snapshot,
                now = Instant.parse("2026-08-26T01:00:00Z"),
            )
        }
    }

    @Test
    fun proposalSurfacesFactsKeptOnlyInVerbatimInteraction() {
        val payload = emptyProposalPayload().put(
            "interactionOnlyFacts",
            JSONArray().put("They prefer introductions by email."),
        )

        val result = GatewayClient.parseProposalResponse(
            responseBody = wrap(payload),
            rawInput = "Sample Person prefers introductions by email.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-08-26T01:00:00Z"),
        )

        assertEquals(listOf("They prefer introductions by email."), result.interactionOnlyFacts)
    }

    @Test
    fun proposalRejectsDuplicateProfileFieldsAndDuplicateRecordEdits() {
        val selected = person(1, "Selected Person")
        val snapshot = NetworkSnapshot(
            people = listOf(selected),
            interactions = listOf(InteractionEntity(10, selected.id, "Original", 100, 100)),
        )
        val duplicatePatches = emptyProposalPayload().put(
            "profilePatches",
            JSONArray()
                .put(JSONObject().put("field", "role").put("value", "Engineer"))
                .put(JSONObject().put("field", "role").put("value", "Lead engineer")),
        )
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                wrap(duplicatePatches), "Synthetic update", selected.name, selected, snapshot,
                Instant.parse("2026-08-26T01:00:00Z"),
            )
        }

        val edit = JSONObject()
            .put("id", 10)
            .put("note", "Corrected")
            .put("occurredAt", "2026-08-26T00:00:00Z")
        val duplicateEdits = emptyProposalPayload().put(
            "interactionEdits",
            JSONArray().put(edit).put(JSONObject(edit.toString())),
        )
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                wrap(duplicateEdits), "Synthetic update", selected.name, selected, snapshot,
                Instant.parse("2026-08-26T01:00:00Z"),
            )
        }
    }

    @Test
    fun proposalRejectsOversizedOrExcessiveCoverageFacts() {
        val oversized = emptyProposalPayload().put(
            "interactionOnlyFacts",
            JSONArray().put("x".repeat(GatewayClient.MAX_INTERACTION_ONLY_FACT_CHARACTERS + 1)),
        )
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                wrap(oversized), "Synthetic update", "Sample Person", null, NetworkSnapshot(),
                Instant.parse("2026-08-26T01:00:00Z"),
            )
        }

        val excessiveFacts = JSONArray().also { facts ->
            repeat(GatewayClient.MAX_INTERACTION_ONLY_FACTS + 1) { facts.put("Fact $it") }
        }
        val excessive = emptyProposalPayload().put("interactionOnlyFacts", excessiveFacts)
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                wrap(excessive), "Synthetic update", "Sample Person", null, NetworkSnapshot(),
                Instant.parse("2026-08-26T01:00:00Z"),
            )
        }
    }

    @Test
    fun targetResponseRejectsMultiPersonWarning() {
        val payload = JSONObject().put("targetName", "").put("warning", "This note targets more than one person.")

        assertThrows(GatewayException::class.java) { GatewayClient.parseTargetResponse(wrap(payload)) }
    }

    @Test
    fun targetResponseReadsTheValidatedOutputObject() {
        val payload = JSONObject().put("targetName", "Synthetic Alex").put("warning", JSONObject.NULL)

        assertEquals("Synthetic Alex", GatewayClient.parseTargetResponse(wrap(payload)).targetName)
    }

    @Test
    fun responsesWithoutAnOutputObjectAreRejected() {
        // The gateway always answers with {"output": {...}}; anything else means
        // the reply did not survive schema validation and must not be trusted.
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseTargetResponse("""{"model":"claude-sonnet-5"}""")
        }
        assertThrows(GatewayException::class.java) {
            GatewayClient.parseTargetResponse("not json at all")
        }
    }

    @Test
    fun gatewayFailuresHaveSafeActionableMessages() {
        assertTrue(GatewayClient.httpFailureReason(401).contains("access token"))
        assertTrue(
            GatewayClient.httpFailureReason(429, """{"error":{"code":"queue_full"}}""").contains("busy"),
        )
        assertTrue(
            GatewayClient.httpFailureReason(429, """{"error":{"code":"upstream_rate_limited"}}""")
                .contains("usage limit"),
        )
        assertTrue(GatewayClient.httpFailureReason(429).contains("usage limit"))
        assertTrue(
            GatewayClient.httpFailureReason(502, """{"error":{"code":"schema_mismatch"}}""")
                .contains("complete result"),
        )
        assertTrue(GatewayClient.httpFailureReason(422).contains("declined"))
        assertTrue(GatewayClient.httpFailureReason(400).contains("rejected"))
        assertTrue(GatewayClient.httpFailureReason(504).contains("too long"))
        assertTrue(GatewayClient.httpFailureReason(503).contains("unavailable"))
        // No message may leak the token or raw provider payloads.
        assertFalse(GatewayClient.httpFailureReason(401).contains("Bearer"))
    }

    @Test
    fun gatewayIsReachedOverHttpsWithoutCredentialsInTheUrl() {
        assertTrue(GatewayClient.GATEWAY_BASE_URL.startsWith("https://"))
        assertFalse(GatewayClient.GATEWAY_BASE_URL.contains("?"))
    }

    private fun emptyProposalPayload() = JSONObject()
        .put("occurredAt", "2026-08-26T00:00:00Z")
        .put("interactionOnlyFacts", JSONArray())
        .put("profilePatches", JSONArray())
        .put("newNeeds", JSONArray())
        .put("newCapabilities", JSONArray())
        .put("interactionEdits", JSONArray())
        .put("needEdits", JSONArray())
        .put("capabilityEdits", JSONArray())
        .put("warning", JSONObject.NULL)

    /** The gateway answers with the schema-validated object under `output`. */
    private fun wrap(payload: JSONObject): String =
        JSONObject().put("output", payload).toString()

    private fun person(
        id: Long,
        name: String,
        contact: String = "",
        archived: Boolean = false,
    ) = PersonEntity(
        id = id,
        name = name,
        contact = contact,
        archived = archived,
        createdAt = 1,
        updatedAt = 2,
    )
}
