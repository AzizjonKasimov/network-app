package com.azizjon.network.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PersonEntity::class,
        InteractionEntity::class,
        NeedEntity::class,
        CapabilityEntity::class,
        AffiliationEntity::class,
        FactEntity::class,
        AiFeedbackEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class NetworkDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao

    companion object {
        @Volatile private var instance: NetworkDatabase? = null

        fun get(context: Context): NetworkDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NetworkDatabase::class.java,
                "network.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }

        /**
         * Adds the table that holds assistant responses the user marked wrong.
         *
         * It stands alone on purpose: no foreign keys to people or
         * interactions, so a report keeps its evidence after the records it
         * describes are corrected, deleted, or replaced by a restore.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE ai_feedback (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stage TEXT NOT NULL,
                        label TEXT NOT NULL,
                        note TEXT NOT NULL,
                        userMessage TEXT NOT NULL,
                        assistantMessage TEXT NOT NULL,
                        assistantDetail TEXT NOT NULL,
                        appVersion TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        exportedAt INTEGER
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX index_ai_feedback_createdAt ON ai_feedback(createdAt)")
                database.execSQL("CREATE INDEX index_ai_feedback_exportedAt ON ai_feedback(exportedAt)")
            }
        }

        /**
         * Gives stated facts somewhere to live, and separates study from work.
         *
         * Existing positions are all work, which is what they were recorded as,
         * so the added column simply defaults.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE facts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        lastConfirmedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        sourceInteractionId INTEGER,
                        FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceInteractionId) REFERENCES interactions(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX index_facts_personId ON facts(personId)")
                database.execSQL("CREATE INDEX index_facts_sourceInteractionId ON facts(sourceInteractionId)")
                database.execSQL("ALTER TABLE affiliations ADD COLUMN kind TEXT NOT NULL DEFAULT 'work'")
            }
        }

        /**
         * Moves the single organization/role pair onto its own table.
         *
         * Every stored pair becomes one current affiliation, so nothing is lost
         * and existing people keep reading the same on screen. The columns are
         * then dropped by rebuilding `people`, since affiliations are now the
         * only place a position is recorded.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE affiliations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId INTEGER NOT NULL,
                        organization TEXT NOT NULL,
                        role TEXT NOT NULL,
                        current INTEGER NOT NULL DEFAULT 1,
                        lastConfirmedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        sourceInteractionId INTEGER,
                        FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceInteractionId) REFERENCES interactions(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX index_affiliations_personId ON affiliations(personId)")
                database.execSQL("CREATE INDEX index_affiliations_current ON affiliations(current)")
                database.execSQL(
                    "CREATE INDEX index_affiliations_sourceInteractionId ON affiliations(sourceInteractionId)",
                )
                database.execSQL(
                    """
                    INSERT INTO affiliations (personId, organization, role, current, lastConfirmedAt, createdAt)
                    SELECT id, organization, role, 1, updatedAt, createdAt FROM people
                    WHERE TRIM(organization) != '' OR TRIM(role) != ''
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE TABLE people_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        location TEXT NOT NULL,
                        contact TEXT NOT NULL,
                        relationship TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        isSelf INTEGER NOT NULL,
                        archived INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO people_new (id, name, location, contact, relationship, tags, notes, isSelf, archived, createdAt, updatedAt) " +
                        "SELECT id, name, location, contact, relationship, tags, notes, isSelf, archived, createdAt, updatedAt FROM people",
                )
                database.execSQL("DROP TABLE people")
                database.execSQL("ALTER TABLE people_new RENAME TO people")
                database.execSQL("CREATE INDEX index_people_name ON people(name)")
                database.execSQL("CREATE INDEX index_people_updatedAt ON people(updatedAt)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE interactions ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual'",
                )

                database.execSQL(
                    """
                    CREATE TABLE needs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lastConfirmedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        sourceInteractionId INTEGER,
                        FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceInteractionId) REFERENCES interactions(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO needs_new (id, personId, text, status, lastConfirmedAt, createdAt) " +
                        "SELECT id, personId, text, status, lastConfirmedAt, createdAt FROM needs",
                )
                database.execSQL("DROP TABLE needs")
                database.execSQL("ALTER TABLE needs_new RENAME TO needs")
                database.execSQL("CREATE INDEX index_needs_personId ON needs(personId)")
                database.execSQL("CREATE INDEX index_needs_status ON needs(status)")
                database.execSQL("CREATE INDEX index_needs_sourceInteractionId ON needs(sourceInteractionId)")

                database.execSQL(
                    """
                    CREATE TABLE capabilities_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        lastConfirmedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        sourceInteractionId INTEGER,
                        FOREIGN KEY(personId) REFERENCES people(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceInteractionId) REFERENCES interactions(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO capabilities_new (id, personId, text, lastConfirmedAt, createdAt) " +
                        "SELECT id, personId, text, lastConfirmedAt, createdAt FROM capabilities",
                )
                database.execSQL("DROP TABLE capabilities")
                database.execSQL("ALTER TABLE capabilities_new RENAME TO capabilities")
                database.execSQL("CREATE INDEX index_capabilities_personId ON capabilities(personId)")
                database.execSQL("CREATE INDEX index_capabilities_lastConfirmedAt ON capabilities(lastConfirmedAt)")
                database.execSQL("CREATE INDEX index_capabilities_active ON capabilities(active)")
                database.execSQL("CREATE INDEX index_capabilities_sourceInteractionId ON capabilities(sourceInteractionId)")
            }
        }
    }
}
