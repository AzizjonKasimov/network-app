package com.azizjon.network.ai

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real gateway with the token the user saved in the app's Settings.
 *
 * The token is read from the app's own encrypted preferences rather than pushed
 * to the device for the test, so the run exercises exactly the credential path
 * the app uses and no copy of the secret is left in /data/local/tmp.
 */
@RunWith(AndroidJUnit4::class)
class GatewayLiveApiInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theConfiguredTokenExercisesCaptureAndSearchWithSyntheticData() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live gateway test is opt-in", arguments.getString("liveGateway") == "true")

        val settings = GatewaySettings(context)
        assertTrue(
            "Save the gateway access token in the app's Settings screen before running this test",
            settings.state.tokenSaved,
        )
        val client = GatewayClient(settings::token)
        assertTrue(client.configured)

        val now = Instant.now()
        // Keep the fixture name ordinary. A name that reads as placeholder data
        // ("Synthetic Alex") makes the model refuse to treat the note as a real
        // capture, which fails the test for a reason the app never hits.
        val draft = "Alex Rivera needs help with Kotlin testing."

        // The capture path as the view model runs it: name the target, then propose.
        val target = liveStage("resolveTarget") {
            client.resolveTarget(draft, emptyList(), now, ZoneOffset.UTC, "en-US")
        }
        assertEquals("Alex Rivera", target.targetName)
        assertEquals(ChatIntent.CAPTURE, target.intent)

        val reply = liveStage("proposeChanges") {
            client.proposeChanges(
                input = draft,
                targetName = "Alex Rivera",
                snapshot = NetworkSnapshot(),
                person = null,
                now = now,
                zoneId = ZoneOffset.UTC,
                locale = "en-US",
            )
        }
        val proposal = reply.proposal
        assertTrue("Every reply needs a sentence for the chat", reply.assistantMessage.isNotBlank())
        assertEquals(draft, proposal.rawInput)
        assertEquals("Alex Rivera", proposal.targetName)
        assertTrue(
            "The explicit synthetic facts should produce at least one proposed change",
            proposal.profilePatches.isNotEmpty() ||
                proposal.newNeeds.isNotEmpty() ||
                proposal.newCapabilities.isNotEmpty(),
        )

        val person = PersonEntity(
            id = 101,
            name = "Alex Rivera",
            location = "Seoul",
            tags = "Kotlin, Compose, synthetic",
            notes = "Synthetic QA profile only.",
            createdAt = now.minusSeconds(86_400).toEpochMilli(),
            updatedAt = now.toEpochMilli(),
        )
        val snapshot = NetworkSnapshot(
            affiliations = listOf(
                AffiliationEntity(
                    id = 501,
                    personId = person.id,
                    organization = "Lunar Lab",
                    role = "Android engineer",
                    lastConfirmedAt = now.toEpochMilli(),
                    createdAt = now.toEpochMilli(),
                ),
            ),
            people = listOf(person),
            interactions = listOf(
                InteractionEntity(
                    id = 201,
                    personId = person.id,
                    note = "Synthetic evidence: built Kotlin and Compose prototypes.",
                    occurredAt = now.minusSeconds(3_600).toEpochMilli(),
                    createdAt = now.minusSeconds(3_600).toEpochMilli(),
                ),
            ),
            needs = listOf(
                NeedEntity(
                    id = 301,
                    personId = person.id,
                    text = "Needs a Compose accessibility audit.",
                    lastConfirmedAt = now.toEpochMilli(),
                    createdAt = now.toEpochMilli(),
                ),
            ),
            capabilities = listOf(
                CapabilityEntity(
                    id = 401,
                    personId = person.id,
                    text = "Builds Kotlin prototypes.",
                    lastConfirmedAt = now.toEpochMilli(),
                    createdAt = now.toEpochMilli(),
                ),
            ),
        )

        // Once the person exists, the note names them outright and the capture
        // should skip resolveTarget entirely - the whole point of the fast path.
        val skipped = PersonResolver.resolveFromNote(snapshot.people, draft)
        assertNotNull("A note naming a saved person must not need the assistant to identify them", skipped)
        assertEquals(person.id, skipped?.id)

        val fastProposal = liveStage("proposeChanges (fast path, no resolveTarget)") {
            client.proposeChanges(
                input = draft,
                targetName = person.name,
                snapshot = snapshot,
                person = person,
                now = now,
                zoneId = ZoneOffset.UTC,
                locale = "en-US",
            )
        }
        assertEquals(person.id, fastProposal.proposal.targetPersonId)

        // A correction typed into the chat revises the open proposal rather than
        // starting a new capture. The revision must stay on the same person.
        val refined = liveStage("proposeChanges (refinement)") {
            client.proposeChanges(
                input = "Also note that he is looking for a Compose testing mentor.",
                targetName = person.name,
                snapshot = snapshot,
                person = person,
                now = now,
                zoneId = ZoneOffset.UTC,
                locale = "en-US",
                history = listOf(
                    ChatTurn(ChatRole.USER, draft),
                    ChatTurn(ChatRole.ASSISTANT, fastProposal.assistantMessage),
                ),
                previousProposal = fastProposal.proposal,
            )
        }
        assertEquals(person.id, refined.proposal.targetPersonId)
        assertEquals(
            "A refinement must keep the original note verbatim",
            fastProposal.proposal.rawInput,
            refined.proposal.rawInput,
        )

        // Two concurrent roles do not fit one organization and one role field. The
        // assistant must say how it handled that without the client discarding the
        // whole proposal, which is what used to happen to any explanatory text.
        val twoRoles = "Priya Raghunathan is CEO of Northwind Labs and is also CTO at Sample Ventures."
        val dualReply = liveStage("proposeChanges (two concurrent roles)") {
            client.proposeChanges(
                input = twoRoles,
                targetName = "Priya Raghunathan",
                snapshot = NetworkSnapshot(),
                person = null,
                now = now,
                zoneId = ZoneOffset.UTC,
                locale = "en-US",
            )
        }
        val dual = dualReply.proposal
        assertTrue(
            "A person with two roles must still produce an applicable proposal",
            dual.profilePatches.isNotEmpty() ||
                dual.newCapabilities.isNotEmpty() ||
                dual.interactionOnlyFacts.isNotEmpty(),
        )
        Log.i(TAG, "two-role caveat present: ${dualReply.caveat != null}")

        // A question routes to search instead of a write, with no target person.
        val question = "Who can build Kotlin prototypes?"
        val routed = liveStage("resolveTarget (search routing)") {
            client.resolveTarget(question, emptyList(), now, ZoneOffset.UTC, "en-US")
        }
        assertEquals(ChatIntent.SEARCH, routed.intent)

        val searchReply = liveStage("search") {
            client.search(question, snapshot)
        }
        assertTrue(searchReply.assistantMessage.isNotBlank())
        assertTrue(searchReply.results.any { it.person.id == person.id && it.evidence.isNotEmpty() })
    }

    private suspend fun <T> liveStage(name: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        return try {
            block().also { Log.i(TAG, "$name took ${System.currentTimeMillis() - started} ms") }
        } catch (error: GatewayException) {
            Log.w(TAG, "$name failed after ${System.currentTimeMillis() - started} ms")
            throw AssertionError("Live gateway $name failed: ${error.message}", error)
        }
    }

    private companion object {
        const val TAG = "LiveGatewayTiming"
    }
}
