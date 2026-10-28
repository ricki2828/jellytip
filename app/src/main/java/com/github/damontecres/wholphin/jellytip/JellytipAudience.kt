package com.github.damontecres.wholphin.jellytip

import org.json.JSONObject

data class JellytipWatcher(
    val name: String,
    val completed: Boolean,
)

/** Who asked for a title and who has watched it (admin-only companion endpoint). */
data class JellytipAudience(
    val requestedBy: List<String>,
    val watchedBy: List<JellytipWatcher>,
    /** The caller's own friendly name as the server knows them; null if the server did not say. */
    val you: String?,
) {
    /** "Requested by Greg · Watched by Daniel, Nadine · You haven't watched" */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (requestedBy.isNotEmpty()) parts += "Requested by ${requestedBy.joinToString(", ")}"
        val others = watchedBy.filter { !it.name.equals(you, ignoreCase = true) }.map { it.name }
        val iWatched = watchedBy.size != others.size
        when {
            watchedBy.isEmpty() -> parts += "Nobody has watched this"
            others.isNotEmpty() -> parts += "Watched by ${others.joinToString(", ")}"
        }
        if (watchedBy.isNotEmpty()) parts += if (iWatched) "You've watched" else "You haven't watched"
        return parts.joinToString("  ·  ")
    }

    companion object {
        fun parse(json: String): JellytipAudience {
            val root = JSONObject(json)
            val req =
                root.optJSONArray("requestedBy")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                }.orEmpty().distinct()
            val watched =
                root.optJSONArray("watchedBy")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val o = a.optJSONObject(i) ?: return@mapNotNull null
                        o.optString("name").takeIf { it.isNotBlank() }?.let { JellytipWatcher(it, o.optBoolean("completed", false)) }
                    }
                }.orEmpty()
            return JellytipAudience(req, watched, root.optString("you").takeIf { it.isNotBlank() })
        }
    }
}
