package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertEquals

class QueueViewTest {

    private val items = listOf("a", "b", "c", "d")

    @Test
    fun moveEntryShiftsDown() {
        assertEquals(listOf("b", "c", "a", "d"), moveEntry(items, 0, 2))
    }

    @Test
    fun moveEntryShiftsUp() {
        // removeAt(3) + insert at 1 — the moved item lands on target index 1.
        assertEquals(listOf("a", "d", "b", "c"), moveEntry(items, 3, 1))
    }

    @Test
    fun moveEntryNoOpOnSameIndex() {
        assertEquals(items, moveEntry(items, 1, 1))
    }

    @Test
    fun moveEntryNoOpOutOfBounds() {
        assertEquals(items, moveEntry(items, 0, 9))
        assertEquals(items, moveEntry(items, -1, 2))
    }

    @Test
    fun upcomingHidesHistoryAndCurrent() {
        val view = QueueView(items.map { QueueEntry(it, it, it, it) }, currentIndex = 1)
        assertEquals(listOf(2, 3), view.upcoming.map { it.index })
    }
}
