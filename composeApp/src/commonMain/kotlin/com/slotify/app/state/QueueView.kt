package com.slotify.app.state

/** Read-only queue snapshot for the Up Next sheet. */
data class QueueView(
    val items: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    val upcoming: List<IndexedValue<QueueEntry>>
        get() = items.withIndex().filter { it.index > currentIndex }
}

/** Pure reorder used by [AppState.moveQueueItem] — [from] and [to] are indices. */
fun <T> moveEntry(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}
