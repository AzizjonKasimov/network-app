package com.azizjon.network.ai

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayLiveApiInstrumentedTest {
    @Test
    fun authorizedKeyExercisesCaptureAndSearchWithSyntheticData() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live gateway test is opt-in", arguments.getString("liveGateway") == "true")

        val client = GatewayClient { readLiveApiKey() }
        assertTrue(client.configured)

        val now = Instant.now()
        val draft = "Synthetic Alex needs help with Kotlin testing."

        val proposal = liveStage("proposal") {
            client.proposeChanges(
                input = draft,
                targetName = "Synthetic Alex",
                snapshot = NetworkSnapshot(),
                person = null,
                now = now,
                zoneId = ZoneOffset.UTC,
                locale = "en-US",
            )
        }
        assertEquals(draft, proposal.rawInput)
        assertEquals("Synthetic Alex", proposal.targetName)
        assertTrue(
            "The explicit synthetic facts should produce at least one proposed change",
            proposal.profilePatches.isNotEmpty() ||
                proposal.newNeeds.isNotEmpty() ||
                proposal.newCapabilities.isNotEmpty(),
        )

        val target = liveStage("target resolution") {
            client.resolveTarget(draft, now, ZoneOffset.UTC, "en-US")
        }
        assertEquals("Synthetic Alex", target.targetName)

        val person = PersonEntity(
            id = 101,
            name = "Synthetic Alex",
            organization = "Lunar Lab",
            role = "Android engineer",
            location = "Seoul",
            tags = "Kotlin, Compose, synthetic",
            notes = "Synthetic QA profile only.",
            createdAt = now.minusSeconds(86_400).toEpochMilli(),
            updatedAt = now.toEpochMilli(),
        )
        val snapshot = NetworkSnapshot(
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

        val searchResults = liveStage("network search") {
            client.search("Who can build Kotlin prototypes?", snapshot)
        }
        assertTrue(searchResults.any { it.person.id == person.id && it.evidence.isNotEmpty() })
    }

    private suspend fun <T> liveStage(name: String, block: suspend () -> T): T = try {
        block()
    } catch (error: GatewayException) {
        throw AssertionError("Live gateway $name failed: ${error.message}", error)
    }

    private fun readLiveApiKey(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat $LIVE_ENV_PATH")
        val contents = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        val match = contents.lineSequence()
            .mapNotNull { LIVE_KEY_PATTERN.matchEntire(it) }
            .firstOrNull()
            ?: error("Push a temporary live-test .env file before running this opt-in test")
        return match.groupValues[1].trim().trim('"', '\'')
            .also { key -> require(key.length >= 20) { "The live-test key is missing or malformed" } }
    }

    companion object {
        private const val LIVE_ENV_PATH = "/data/local/tmp/network-app-gateway-live.env"
        private val LIVE_KEY_PATTERN = Regex("""\s*(?:export\s+)?GEMINI_API_KEY\s*=\s*(.*)\s*""")
    }
}
