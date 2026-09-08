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

/**
 * One organization a person belongs to, with the role they hold there.
 *
 * People commonly hold more than one position at once - a founder who is also a
 * CTO elsewhere, an advisor with a day job - so this is a list rather than a
 * pair of fields on the person. [current] separates a position someone still
 * holds from one they have left, which keeps history without implying it is
 * still true.
 */
@Entity(
    tableName = "affiliations",
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
    indices = [Index("personId"), Index("current"), Index("sourceInteractionId")],
)
data class AffiliationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val organization: String = "",
    val role: String = "",
    @ColumnInfo(defaultValue = "1") val current: Boolean = true,
    val lastConfirmedAt: Long,
    val createdAt: Long,
    val sourceInteractionId: Long? = null,
    /** [KIND_WORK] or [KIND_EDUCATION]. Studying somewhere is not a job. */
    @ColumnInfo(defaultValue = "'work'") val kind: String = KIND_WORK,
) {
    /** "Role at Organization", collapsing gracefully when only one is known. */
    val label: String
        get() = when {
            organization.isBlank() -> role
            role.isBlank() -> organization
            else -> "$role at $organization"
        }

    val isEducation: Boolean get() = kind == KIND_EDUCATION

    companion object {
        const val KIND_WORK = "work"
        const val KIND_EDUCATION = "education"
    }
}

/**
 * A stated fact about a person that is not a need, a capability, or a position.
 *
 * Without this, anything that did not fit those three shapes survived only
 * inside the verbatim interaction text - findable, but not something the user
 * could see on the person, correct, re-date, or have cited on its own. Keep it
 * deliberately plain: one sentence of stored fact, dated, with provenance.
 */
@Entity(
    tableName = "facts",
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
    indices = [Index("personId"), Index("sourceInteractionId")],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val text: String,
    val lastConfirmedAt: Long,
    val createdAt: Long,
    val sourceInteractionId: Long? = null,
)
