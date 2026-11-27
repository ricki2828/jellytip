package com.github.damontecres.wholphin.ui.cards

/**
 * Stable lazy-list keys for a row's tiles (27-Nov perf batch). A refresh that inserts, removes or reorders tiles then keeps
 * each tile's composition and focus instead of re-binding by index. Keys are Strings (Bundle-able for saved state), and
 * a repeated id (the same title twice in one row) gets a "#n" suffix, because a duplicate key crashes LazyRow.
 */
object ItemRowKeys {
    /** One key per slot of [ids]; a null id (placeholder) keys by its slot index. */
    fun build(ids: List<String?>): List<String> {
        val seen = HashMap<String, Int>(ids.size * 2)
        return ids.mapIndexed { index, id ->
            if (id == null) {
                "slot-$index"
            } else {
                val n = seen.merge(id, 1, Int::plus) ?: 1
                if (n == 1) id else "$id#$n"
            }
        }
    }
}
