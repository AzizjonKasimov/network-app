package com.azizjon.network.ai

import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.FactEntity
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
                "newAffiliations", "newFacts", "interactionEdits", "needEdits", "capabilityEdits",
                "affiliationEdits", "factEdits", "assistantMessage", "caveat", "warning",
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

        val result = GatewayClient.parseSearchResponse(wrap(validPayload), corpus).results.single()

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

        assertEquals(listOf("They prefer introductions by email."), result.proposal.interactionOnlyFacts)
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
                .put(JSONObject().put("field", "location").put("value", "Seoul"))
                .put(JSONObject().put("field", "location").put("value", "Busan")),
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
        val payload = JSONObject()
            .put("intent", "capture")
            .put("targetName", "")
            .put("warning", "This note targets more than one person.")

        assertThrows(GatewayException::class.java) { GatewayClient.parseTargetResponse(wrap(payload)) }
    }

    @Test
    fun targetResponseReadsTheValidatedOutputObject() {
        val payload = JSONObject()
            .put("intent", "capture")
            .put("targetName", "Synthetic Alex")
            .put("warning", JSONObject.NULL)

        val resolution = GatewayClient.parseTargetResponse(wrap(payload))

        assertEquals("Synthetic Alex", resolution.targetName)
        assertEquals(ChatIntent.CAPTURE, resolution.intent)
    }

    @Test
    fun moveIntentCarriesBothNamesAndNoTarget() {
        // A move is the one intent that names two people. It must not be pushed
        // through the single-target checks, which would reject the empty name.
        val payload = JSONObject()
            .put("intent", "move")
            .put("targetName", "")
            .put("moveFrom", "  Synthetic Alex ")
            .put("moveTo", "Synthetic Robin")
            .put("warning", JSONObject.NULL)

        val resolution = GatewayClient.parseTargetResponse(wrap(payload))

        assertEquals(ChatIntent.MOVE, resolution.intent)
        assertEquals("Synthetic Alex", resolution.moveFrom)
        assertEquals("Synthetic Robin", resolution.moveTo)
        assertEquals("", resolution.targetName)
    }

    @Test
    fun everyOtherIntentLeavesTheMoveNamesEmpty() {
        val payload = JSONObject()
            .put("intent", "capture")
            .put("targetName", "Synthetic Alex")
            .put("warning", JSONObject.NULL)

        val resolution = GatewayClient.parseTargetResponse(wrap(payload))

        assertEquals("", resolution.moveFrom)
        assertEquals("", resolution.moveTo)
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
    fun searchIntentNeedsNoTargetPersonAndUnclearIntentIsRefused() {
        // A question routes to search, where there is no single person to name.
        val search = JSONObject()
            .put("intent", "search")
            .put("targetName", "")
            .put("warning", JSONObject.NULL)

        val resolution = GatewayClient.parseTargetResponse(wrap(search))

        assertEquals(ChatIntent.SEARCH, resolution.intent)
        assertEquals("", resolution.targetName)

        val unclear = JSONObject()
            .put("intent", "unclear")
            .put("targetName", "")
            .put("warning", "This could be a note or a question.")
        assertThrows(GatewayException::class.java) { GatewayClient.parseTargetResponse(wrap(unclear)) }
    }

    @Test
    fun proposalCarriesTheAssistantSentenceAndFallsBackWhenItIsBlank() {
        val payload = emptyProposalPayload().put("assistantMessage", "  Added   one need.  ")

        val spoken = GatewayClient.parseProposalResponse(
            responseBody = wrap(payload),
            rawInput = "Sample note.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-08-26T01:00:00Z"),
        )

        assertEquals("Added one need.", spoken.assistantMessage)

        val blank = GatewayClient.parseProposalResponse(
            responseBody = wrap(emptyProposalPayload().put("assistantMessage", "   ")),
            rawInput = "Sample note.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-08-26T01:00:00Z"),
        )

        assertEquals("Prepared changes for Sample Person.", blank.assistantMessage)
    }

    @Test
    fun aCaveatExplainsAnAwkwardFactWithoutDiscardingTheProposal() {
        // Someone holding two concurrent roles fits only one organization and one
        // role in the profile. The assistant says how it handled the rest; that
        // explanation must never cost the user the whole proposal.
        val payload = emptyProposalPayload()
            .put(
                "newAffiliations",
                JSONArray()
                    .put(
                        JSONObject().put("organization", "Northwind Labs").put("role", "CEO")
                            .put("current", true).put("education", false),
                    )
                    .put(
                        JSONObject().put("organization", "Sample Ventures").put("role", "CTO")
                            .put("current", true).put("education", false),
                    ),
            )
            .put("caveat", "Both concurrent positions were recorded separately.")

        val reply = GatewayClient.parseProposalResponse(
            responseBody = wrap(payload),
            rawInput = "Synthetic note about two roles.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-09-08T01:00:00Z"),
        )

        // Both positions survive: neither is demoted to a capability or a note.
        assertEquals(2, reply.proposal.newAffiliations.size)
        assertEquals(
            listOf("Northwind Labs" to "CEO", "Sample Ventures" to "CTO"),
            reply.proposal.newAffiliations.map { it.organization to it.role },
        )
        assertTrue(reply.proposal.newAffiliations.all { it.current })
        assertTrue(reply.caveat!!.contains("Both concurrent positions"))
    }

    @Test
    fun aWarningStillDiscardsTheProposal() {
        val payload = emptyProposalPayload().put("warning", "This note targets more than one person.")

        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                responseBody = wrap(payload),
                rawInput = "Synthetic note.",
                targetName = "Sample Person",
                person = null,
                snapshot = NetworkSnapshot(),
                now = Instant.parse("2026-09-08T01:00:00Z"),
            )
        }
    }

    @Test
    fun anAdvisoryTargetWarningDoesNotBlockAUsableName() {
        // Same rule on the routing call: a note beside a usable name is advice,
        // and only an unusable name refuses, carrying the warning as its reason.
        val advisory = JSONObject()
            .put("intent", "capture")
            .put("targetName", "Sample Person")
            .put("warning", "Two organizations were mentioned for this person.")

        val resolution = GatewayClient.parseTargetResponse(wrap(advisory))

        assertEquals("Sample Person", resolution.targetName)
        assertEquals(ChatIntent.CAPTURE, resolution.intent)

        val refused = JSONObject()
            .put("intent", "capture")
            .put("targetName", "")
            .put("warning", "This note targets more than one person.")
        assertThrows(GatewayException::class.java) { GatewayClient.parseTargetResponse(wrap(refused)) }
    }

    @Test
    fun statedFactsWithNowhereElseToGoBecomeBackgroundRecords() {
        // A user count and a conference attendance are neither a need, a
        // capability, nor a position. Before background records existed they
        // survived only inside the verbatim note, so they could not be seen on
        // the person, corrected, or cited on their own.
        val payload = emptyProposalPayload()
            .put(
                "newFacts",
                JSONArray()
                    .put(JSONObject().put("text", "The sample app has over two million users."))
                    .put(JSONObject().put("text", "Attended a synthetic conference in 2015.")),
            )

        val reply = GatewayClient.parseProposalResponse(
            responseBody = wrap(payload),
            rawInput = "Synthetic background note.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-09-08T01:00:00Z"),
        )

        assertEquals(2, reply.proposal.newFacts.size)
        assertTrue(reply.proposal.interactionOnlyFacts.isEmpty())
    }

    @Test
    fun studyIsRecordedAsEducationRatherThanAJob() {
        val payload = emptyProposalPayload().put(
            "newAffiliations",
            JSONArray().put(
                JSONObject().put("organization", "Sample University").put("role", "MS and PhD")
                    .put("current", false).put("education", true),
            ),
        )

        val reply = GatewayClient.parseProposalResponse(
            responseBody = wrap(payload),
            rawInput = "Synthetic education note.",
            targetName = "Sample Person",
            person = null,
            snapshot = NetworkSnapshot(),
            now = Instant.parse("2026-09-08T01:00:00Z"),
        )

        val position = reply.proposal.newAffiliations.single()
        assertTrue(position.education)
        assertFalse(position.current)
        assertEquals("Sample University", position.organization)
    }

    @Test
    fun backgroundFactEditsMustReferenceAStoredFactForThatPerson() {
        val person = person(1, "Selected Person")
        val snapshot = NetworkSnapshot(
            people = listOf(person),
            facts = listOf(FactEntity(id = 91, personId = 1, text = "Stored background", lastConfirmedAt = 5, createdAt = 5)),
        )
        val payload = emptyProposalPayload().put(
            "factEdits",
            JSONArray().put(
                JSONObject().put("id", 999).put("text", "Rewritten")
                    .put("lastConfirmedAt", "2026-09-08T00:00:00Z"),
            ),
        )

        assertThrows(GatewayException::class.java) {
            GatewayClient.parseProposalResponse(
                responseBody = wrap(payload),
                rawInput = "Synthetic note.",
                targetName = person.name,
                person = person,
                snapshot = snapshot,
                now = Instant.parse("2026-09-08T01:00:00Z"),
            )
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
        .put("newAffiliations", JSONArray())
        .put("newFacts", JSONArray())
        .put("interactionEdits", JSONArray())
        .put("needEdits", JSONArray())
        .put("capabilityEdits", JSONArray())
        .put("affiliationEdits", JSONArray())
        .put("factEdits", JSONArray())
        .put("assistantMessage", "Prepared changes.")
        .put("caveat", JSONObject.NULL)
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
