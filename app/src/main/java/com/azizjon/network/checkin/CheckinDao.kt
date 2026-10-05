package com.azizjon.network.checkin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CheckinDao {
    @Query("SELECT * FROM checkins")
    abstract fun observeAll(): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins")
    abstract suspend fun all(): List<CheckinEntity>

    @Query("SELECT * FROM checkins WHERE source = :source")
    abstract suspend fun bySource(source: String): List<CheckinEntity>

    /** -1 for a row that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(row: CheckinEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(rows: List<CheckinEntity>)

    @Upsert
    abstract suspend fun upsert(rows: List<CheckinEntity>)

    /** Keeps the newest name and message time; never moves the time backwards. */
    @Query("UPDATE checkins SET name = :name, lastSeenAt = MAX(lastSeenAt, :seenAt) WHERE ref = :ref")
    abstract suspend fun touch(ref: String, name: String, seenAt: Long)

    @Query("UPDATE checkins SET name = :name WHERE ref = :ref")
    abstract suspend fun rename(ref: String, name: String)

    @Query("UPDATE checkins SET status = :status, statusAt = :at WHERE ref IN (:refs)")
    abstract suspend fun setStatus(refs: List<String>, status: String, at: Long)

    @Query("DELETE FROM checkins WHERE ref IN (:refs)")
    abstract suspend fun delete(refs: List<String>)

    /** Records a chat sender, or brings an existing one up to date. */
    @Transaction
    open suspend fun recordSighting(row: CheckinEntity) {
        if (insertIgnore(row) == -1L) touch(row.ref, row.name, row.lastSeenAt)
    }
}
