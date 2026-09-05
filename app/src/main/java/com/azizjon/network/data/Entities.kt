package com.azizjon.network.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "people",
    indices = [Index("name"), Index("updatedAt")],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val organization: String = "",
    val role: String = "",
    val location: String = "",
    val contact: String = "",
    val relationship: String = "",
    val tags: String = "",
    val notes: String = "",
    val isSelf: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "interactions",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId"), Index("occurredAt")],
)
data class InteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val note: String,
    val occurredAt: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'manual'") val origin: String = ORIGIN_MANUAL,
) {
    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_AI_REVIEWED = "ai_reviewed"
    }
}

@Entity(
    tableName = "needs",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InteractionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceInteractionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("personId"), Index("status"), Index("sourceInteractionId")],
)
data class NeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val text: String,
    val status: String = "active",
    val lastConfirmedAt: Long,
    val createdAt: Long,
    val sourceInteractionId: Long? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_CLOSED = "closed"
    }
}

@Entity(
    tableName = "capabilities",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InteractionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceInteractionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("personId"), Index("lastConfirmedAt"), Index("active"), Index("sourceInteractionId")],
)
data class CapabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val text: String,
    val lastConfirmedAt: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    val sourceInteractionId: Long? = null,
)
