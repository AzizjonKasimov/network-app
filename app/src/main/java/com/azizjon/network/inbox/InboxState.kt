package com.azizjon.network.inbox

import org.json.JSONArray
import org.json.JSONObject

/** Everything the inbox keeps on the phone. */
data class InboxState(
    /** Present while drafts from the PC are turned on. */
    val key: InboxKey? = null,
    /** Drafts taken from the repository and not yet sent, edited, or discarded, oldest first. */
    val pending: List<InboxDraft> = emptyList(),
    /** Ids of drafts already taken, so a copy put back from git history does not show again. */
    val seen: List<String> = emptyList(),
    val lastCheckAt: Long = 0,
    val lastError: String = "",
) {
    val enabled: Boolean get() = key != null

    fun toJson(): String = JSONObject()
        .put("key", key?.let { key ->
            JSONObject()
                .put("publicKey", key.publicKey)
                .put("privateKey", key.privateKey)
                .put("folder", key.folder)
                .put("createdAt", key.createdAt)
                .put("remoteSha", key.remoteSha)
        } ?: JSONObject.NULL)
        .put("pending", JSONArray().apply {
            pending.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("queuedAt", it.queuedAt)) }
        })
        .put("seen", JSONArray(seen))
        .put("lastCheckAt", lastCheckAt)
        .put("lastError", lastError)
        .toString()

    companion object {
        fun fromJson(value: String): InboxState {
            val json = JSONObject(value)
            val key = json.optJSONObject("key")?.let {
                InboxKey(
                    publicKey = it.getString("publicKey"),
                    privateKey = it.getString("privateKey"),
                    folder = it.getString("folder"),
                    createdAt = it.getLong("createdAt"),
                    remoteSha = it.optString("remoteSha"),
                )
            }
            val pending = json.optJSONArray("pending") ?: JSONArray()
            val seen = json.optJSONArray("seen") ?: JSONArray()
            return InboxState(
                key = key,
                pending = (0 until pending.length()).map(pending::getJSONObject).map {
                    InboxDraft(it.getString("id"), it.getString("text"), it.getLong("queuedAt"))
                },
                seen = (0 until seen.length()).map(seen::getString),
                lastCheckAt = json.optLong("lastCheckAt"),
                lastError = json.optString("lastError"),
            )
        }
    }
}

/** Where [InboxState] is kept: encrypted preferences on the phone, memory in tests. */
interface InboxStorage {
    fun load(): InboxState

    /** Must be on disk when it returns, because a draft is deleted from the repository right after. */
    fun save(state: InboxState)
}
