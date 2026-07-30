package me.rerere.rikkahub.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the job semantics the engine's cancel classification relies on.
 *
 * A worker cancelled from inside its own conversation (the stop button in the worker
 * chat) is a pure ChatService-side cancel: the registry never hears about it, and the
 * chat pipeline swallows the CancellationException. So after `join()` the engine cannot
 * tell "finished normally" from "cancelled by user" — except via [Job.isCancelled],
 * which stays true once cancel() was requested even when the body swallowed the CE.
 *
 * If these semantics ever changed, executeRun would silently go back to publishing
 * user-cancelled workers as SUCCEEDED whenever partial text was harvestable.
 */
class SubAgentCancelledJobClassificationTest {

    @Test
    fun `join returns normally for a cancelled job and isCancelled stays true even when the body swallows the CE`() = runBlocking {
        val job = launch {
            try {
                delay(60_000)
            } catch (e: CancellationException) {
                // Swallowed, mirroring the chat generation pipeline.
            }
        }
        job.cancel()
        job.join() // must not throw

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a normally completed job is not reported cancelled`() = runBlocking {
        val job = launch { /* completes immediately */ }
        job.join()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
    }
}
