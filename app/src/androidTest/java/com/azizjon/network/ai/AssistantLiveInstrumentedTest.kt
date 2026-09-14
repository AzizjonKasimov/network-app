package com.azizjon.network.ai

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azizjon.network.data.AssistantStore
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.NetworkDatabase
import com.azizjon.network.data.PersonEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real gateway and model with the token the user saved in Settings.
 *
 * The token is read from the app's own encrypted preferences rather than pushed
 * to the device for the test, so the run exercises exactly the credential path
 * the app uses and no copy of the secret is left in /data/local/tmp. The
 * records live in an in-memory database: nothing real is read or written.
 */
@RunWith(AndroidJUnit4::class)
class AssistantLiveInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theAgentFilesCapturesAnswersQuestionsAndOnlyQueuesDeletes() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live gateway test is opt-in", arguments.getString("liveGateway") == "true")

        val settings = GatewaySettings(context)
        assertTrue(
            "Save the gateway access token in the app's Settings screen before running this test",
            settings.state.tokenSaved,
        )
        val database = Room.inMemoryDatabaseBuilder(context, NetworkDatabase::class.java).build()
        try {
            val store = AssistantStore(database)
            val agent = AssistantAgent(AgentClient(settings::token), AssistantTools(store))
            val dao = database.networkDao()
            val now = System.currentTimeMillis()
            // Ordinary-looking names: a name that reads as placeholder data makes
            // the model hesitate to treat the note as real, which the app never hits.
            val dana = dao.savePerson(PersonEntity(name = "Dana Whitfield", createdAt = now, updatedAt = now))
            dao.insertCapability(CapabilityEntity(personId = dana, text = "Knows seed investors in Berlin.", lastConfirmedAt = now, createdAt = now))
            val priya = dao.savePerson(PersonEntity(name = "Priya Raman", createdAt = now, updatedAt = now))

            // Work is a position, study is education, and a volunteer shift is
            // neither, however organised it sounds.
            val capture = liveTurn(
                agent,
                "capture with work, study, and neither",
                "I met Marta Okafor today. She is a product designer at Brightline Studio, studied Spanish at " +
                    "Cedar Hill Language Institute last year, and volunteers at Harbor Animal Shelter on weekends.",
            )
            val afterCapture = store.snapshot()
            val marta = afterCapture.people.firstOrNull { it.name.contains("Marta", ignoreCase = true) }
            assertNotNull("Marta should have been created, reply was: ${capture.reply}", marta)
            val (study, work) = afterCapture.affiliationsFor(marta!!.id).partition { it.isEducation }
            val workText = work.joinToString(" | ") { it.label }
            val studyText = study.joinToString(" | ") { it.label }
            assertTrue("The job must be a position, got '$workText'", workText.contains("Brightline", ignoreCase = true))
            listOf("Spanish", "Cedar Hill", "Shelter").forEach { word ->
                assertFalse("'$word' came back as a position: '$workText'", workText.contains(word, ignoreCase = true))
            }
            assertFalse("Volunteering came back as education: '$studyText'", studyText.contains("Shelter", ignoreCase = true))
            assertTrue("The conversation should be kept as a note", afterCapture.interactionsFor(marta.id).isNotEmpty())
            Log.i(TAG, "capture: ${work.size} work, ${study.size} study, ${afterCapture.factsFor(marta.id).size} facts")

            // One message, two saved people.
            val both = liveTurn(agent, "two people at once", "Dana Whitfield and Priya Raman both joined Lumen Labs as advisors.")
            val afterBoth = store.snapshot()
            listOf(dana, priya).forEach { id ->
                assertTrue(
                    "person $id should hold a Lumen Labs position, reply was: ${both.reply}",
                    afterBoth.affiliationsFor(id).any { it.organization.contains("Lumen", ignoreCase = true) },
                )
            }

            // A question reads and answers, and writes nothing.
            val question = liveTurn(agent, "question", "Who could introduce me to seed investors?")
            assertTrue("A question must not write anything", question.log.changes.isEmpty())
            assertTrue("Dana should be named, reply was: ${question.reply}", question.reply.orEmpty().contains("Dana"))

            // A delete only ever queues for the user.
            val deletion = liveTurn(agent, "delete request", "Please delete Priya Raman from my network.")
            assertTrue(
                "The delete should wait for confirmation, reply was: ${deletion.reply}",
                deletion.log.pending.any { it is PendingAction.DeletePerson && it.personId == priya },
            )
            assertNotNull("Nothing is deleted before the user confirms", dao.person(priya))

            // One undo takes back everything the two-person reply saved.
            store.undo(both.log.changes, System.currentTimeMillis())
            assertTrue(store.snapshot().affiliations.none { it.organization.contains("Lumen", ignoreCase = true) })
        } finally {
            database.close()
        }
    }

    private suspend fun liveTurn(agent: AssistantAgent, name: String, message: String): AgentOutcome {
        val started = System.currentTimeMillis()
        val outcome = agent.run(message, emptyList()) {}
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "$name took $elapsed ms, ${outcome.log.calls.size} tool calls, ${outcome.log.saved.size} saved")
        assertNull("Live turn '$name' failed after $elapsed ms: ${outcome.error}", outcome.error)
        return outcome
    }

    private companion object {
        const val TAG = "LiveGatewayTiming"
    }
}
