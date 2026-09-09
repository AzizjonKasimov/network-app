package com.azizjon.network.feedback

/**
 * The fault classes an assistant response can be filed under.
 *
 * A fixed list rather than free text, because the point of a report is that it
 * can be counted and grouped later. The free-text note stays available for
 * everything the list cannot express, and [OTHER] is the honest escape hatch.
 *
 * The [id] is what lands in the database and the export file, so it must stay
 * stable across releases even if the wording on screen changes.
 */
enum class AiFeedbackLabel(val id: String, val title: String, val description: String) {
    WRONG_TARGET(
        "wrong_target",
        "Wrong person",
        "The changes were attached to the wrong person, or a new person was created for someone already saved.",
    ),
    MISSED_INFORMATION(
        "missed_information",
        "Missed something",
        "Something clearly stated in the message was left out of the proposal or the answer.",
    ),
    INVENTED_INFORMATION(
        "invented_information",
        "Invented something",
        "The response asserted something that was never said.",
    ),
    WRONG_RECORD_TYPE(
        "wrong_record_type",
        "Wrong record type",
        "Stored as the wrong kind of record - a position, need, capability, or background fact mixed up.",
    ),
    WRONG_DATE(
        "wrong_date",
        "Wrong date",
        "The interaction date or a last-confirmed date was wrong.",
    ),
    BAD_SEARCH_RESULTS(
        "bad_search_results",
        "Bad search results",
        "Irrelevant people were returned, obvious matches were missed, or the evidence did not support the match.",
    ),
    MISUNDERSTOOD(
        "misunderstood",
        "Misunderstood the request",
        "Treated a question as something to save, or the other way round.",
    ),
    OTHER(
        "other",
        "Something else",
        "Anything the labels above do not cover. Describe it in the note.",
    ),
    ;

    companion object {
        fun fromId(id: String): AiFeedbackLabel? = entries.firstOrNull { it.id == id }

        /** The stored label, or the raw id when a report predates a rename. */
        fun titleFor(id: String): String = fromId(id)?.title ?: id
    }
}
