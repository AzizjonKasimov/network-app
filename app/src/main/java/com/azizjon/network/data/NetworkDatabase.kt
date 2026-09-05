package com.azizjon.network.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PersonEntity::class, InteractionEntity::class, NeedEntity::class, CapabilityEntity::class],
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
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
