package me.rerere.rikkahub.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [conflateLatest], the time-based conflation applied to streaming
 * generation chunks before they reach the conversation StateFlow.
 *
 * Pure JVM: uses runBlocking with real time (milliseconds scale, no extra test deps).
 */
class ConflateLatestTest {

    @Test
    fun `burst of chunks is conflated, first and newest survive`() = runBlocking {
        // 20 chunks arriving instantly: intermediate snapshots must be dropped.
        val received = flowOf(*(1..20).toList().toTypedArray())
            .conflateLatest(windowMs = 75)
            .toList()

        assertEquals("first chunk must pass through", 1, received.first())
        assertEquals("newest trailing chunk must be delivered", 20, received.last())
        assertTrue(
            "intermediate snapshots must be conflated, got $received",
            received.size < 20,
        )
    }

    @Test
    fun `chunks spaced wider than the window all pass through`() = runBlocking {
        val received = flow {
            emit(1)
            delay(150)
            emit(2)
            delay(150)
            emit(3)
        }.conflateLatest(windowMs = 75).toList()

        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun `trailing chunk is never swallowed by the window`() = runBlocking {
        // Second chunk arrives inside the window: it must still be delivered after
        // the wait, otherwise the final message state would silently go stale.
        val received = flowOf(1, 2)
            .conflateLatest(windowMs = 75)
            .toList()

        assertEquals(listOf(1, 2), received)
    }
}
