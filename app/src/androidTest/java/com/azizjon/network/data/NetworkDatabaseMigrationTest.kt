package com.azizjon.network.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationFromVersionOnePreservesRowsAndMovesTheStoredJobToAPosition() = runBlocking {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, NetworkDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                NetworkDatabase.MIGRATION_1_2,
                NetworkDatabase.MIGRATION_2_3,
                NetworkDatabase.MIGRATION_3_4,
                NetworkDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            val dao = database.networkDao()
            assertEquals("Legacy Person", dao.allPeople().single().name)
            assertEquals(InteractionEntity.ORIGIN_MANUAL, dao.allInteractions().single().origin)
            assertNull(dao.allNeeds().single().sourceInteractionId)
            assertTrue(dao.allCapabilities().single().active)
            assertNull(dao.allCapabilities().single().sourceInteractionId)

            // The single organization/role pair becomes one current position, so an
            // upgrade never silently drops where somebody works.
            val affiliation = dao.allAffiliations().single()
            assertEquals(1L, affiliation.personId)
            assertEquals("Legacy Corp", affiliation.organization)
            assertEquals("Legacy role", affiliation.role)
            assertTrue(affiliation.current)
            // A position recorded before the work/education split is work.
            assertFalse(affiliation.isEducation)
            assertTrue(dao.allFacts().isEmpty())
            // Feedback arrives empty on an upgrade and is writable straight away.
            assertTrue(dao.allFeedback().isEmpty())
            dao.insertFeedback(
                AiFeedbackEntity(
                    stage = AiFeedbackEntity.Stage.PROPOSAL,
                    label = "wrong_target",
                    userMessage = "synthetic message",
                    assistantMessage = "synthetic answer",
                    appVersion = "test",
                    createdAt = 1_000L,
                ),
            )
            assertEquals("wrong_target", dao.allFeedback().single().label)
        } finally {
            database.close()
        }
    }

    @Test
    fun aPersonCanHoldSeveralConcurrentPositions() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NetworkDatabase::class.java).build()
        try {
            val dao = database.networkDao()
            val personId = dao.savePerson(PersonEntity(name = "Synthetic Person", createdAt = 1, updatedAt = 1))
            dao.insertAffiliation(
                AffiliationEntity(personId = personId, organization = "Northwind Labs", role = "CEO", lastConfirmedAt = 2, createdAt = 2),
            )
            dao.insertAffiliation(
                AffiliationEntity(personId = personId, organization = "Sample Ventures", role = "CTO", lastConfirmedAt = 3, createdAt = 3),
            )

            val stored = dao.allAffiliations()

            assertEquals(2, stored.size)
            assertEquals(setOf("Northwind Labs", "Sample Ventures"), stored.map { it.organization }.toSet())
            assertTrue(stored.all { it.current })

            // Deleting the person takes their positions with them.
            dao.deletePerson(dao.allPeople().single())
            assertTrue(dao.allAffiliations().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun confirmedProposalIsAtomicAndLinksDerivedRecordsToAuditInteraction() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NetworkDatabase::class.java).build()
        try {
            val dao = database.networkDao()
            val personId = dao.savePerson(PersonEntity(name = "Synthetic Person", createdAt = 1, updatedAt = 1))
            val proposal = AiWriteProposal(
                rawInput = "Synthetic Person needs a designer and can mentor Kotlin.",
                targetPersonId = personId,
                targetName = "Synthetic Person",
                occurredAt = 2,
                newNeeds = listOf(AiRecordAdd("Find a designer")),
                newCapabilities = listOf(AiRecordAdd("Kotlin mentoring")),
            )

            val result = dao.applyAiProposal(proposal, now = 3)

            assertEquals(InteractionEntity.ORIGIN_AI_REVIEWED, dao.allInteractions().single().origin)
            assertEquals(result.auditInteractionId, dao.allNeeds().single().sourceInteractionId)
            assertEquals(result.auditInteractionId, dao.allCapabilities().single().sourceInteractionId)

            val interactionsBefore = dao.allInteractions()
            val invalid = proposal.copy(
                rawInput = "Invalid synthetic edit",
                newNeeds = emptyList(),
                newCapabilities = emptyList(),
                interactionEdits = listOf(AiInteractionEdit(999, "Unknown", 2)),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { dao.applyAiProposal(invalid, now = 4) }
            }
            assertEquals(interactionsBefore, dao.allInteractions())
        } finally {
            database.close()
        }
    }

    private fun createVersionOneDatabase() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, organization TEXT NOT NULL, role TEXT NOT NULL, location TEXT NOT NULL, contact TEXT NOT NULL, relationship TEXT NOT NULL, tags TEXT NOT NULL, notes TEXT NOT NULL, isSelf INTEGER NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                    )
                    database.execSQL(
                        "CREATE TABLE interactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personId INTEGER NOT NULL, note TEXT NOT NULL, occurredAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    database.execSQL(
                        "CREATE TABLE needs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personId INTEGER NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, lastConfirmedAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    database.execSQL(
                        "CREATE TABLE capabilities (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personId INTEGER NOT NULL, text TEXT NOT NULL, lastConfirmedAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    database.execSQL("CREATE INDEX index_people_name ON people(name)")
                    database.execSQL("CREATE INDEX index_people_updatedAt ON people(updatedAt)")
                    database.execSQL("CREATE INDEX index_interactions_personId ON interactions(personId)")
                    database.execSQL("CREATE INDEX index_interactions_occurredAt ON interactions(occurredAt)")
                    database.execSQL("CREATE INDEX index_needs_personId ON needs(personId)")
                    database.execSQL("CREATE INDEX index_needs_status ON needs(status)")
                    database.execSQL("CREATE INDEX index_capabilities_personId ON capabilities(personId)")
                    database.execSQL("CREATE INDEX index_capabilities_lastConfirmedAt ON capabilities(lastConfirmedAt)")
                    database.execSQL("INSERT INTO people VALUES (1, 'Legacy Person', 'Legacy Corp', 'Legacy role', '', '', '', '', '', 0, 0, 1, 2)")
                    database.execSQL("INSERT INTO interactions VALUES (2, 1, 'Legacy interaction', 3, 4)")
                    database.execSQL("INSERT INTO needs VALUES (3, 1, 'Legacy need', 'active', 5, 6)")
                    database.execSQL("INSERT INTO capabilities VALUES (4, 1, 'Legacy capability', 7, 8)")
                }

                override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper -> helper.writableDatabase }
    }

    companion object {
        private const val TEST_DATABASE = "network-migration-test.db"
    }
}
