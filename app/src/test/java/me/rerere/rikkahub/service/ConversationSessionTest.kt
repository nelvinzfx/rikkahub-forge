package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `cancelCurrentJob detaches a non cooperative job immediately`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val stuckJob = scope.launch {
            withContext(NonCancellable) { release.await() }
        }
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}
        session.setJob(stuckJob)

        val detached = session.cancelCurrentJob()

        assertSame(stuckJob, detached)
        assertNull(session.getJob())
        assertFalse(stuckJob.isCompleted)

        release.complete(Unit)
        stuckJob.join()
        scope.cancel()
    }

    @Test
    fun `late completion of detached job cannot clear replacement job`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val oldJob = scope.launch {
            withContext(NonCancellable) { release.await() }
        }
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}
        session.setJob(oldJob)
        session.cancelCurrentJob()

        val replacement = Job()
        session.setJob(replacement)
        release.complete(Unit)
        oldJob.join()

        assertSame(replacement, session.getJob())

        replacement.cancel()
        joinAll(replacement)
        scope.cancel()
    }

    @Test
    fun `applyConversationSnapshotIfIdle loads a cold idle session`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}

        val snapshot = Conversation.ofId(id).copy(title = "from-db")

        val applied = applyConversationSnapshotIfIdle(session, snapshot)

        assertTrue(applied)
        assertEquals("from-db", session.state.value.title)

        scope.cancel()
    }

    @Test
    fun `applyConversationSnapshotIfIdle never clobbers a generating session`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}
        // Simulate the live in-memory state of a conversation mid-stream, exactly the
        // case the race described in the fix reproduces: a DB read lags this progress.
        val live = Conversation.ofId(id).copy(title = "live-streaming")
        session.state.value = live

        // Mark the session as mid-generation with a non-cooperative job (mirrors the
        // existing cancelCurrentJob test harness).
        val release = CompletableDeferred<Unit>()
        val job = scope.launch { withContext(NonCancellable) { release.await() } }
        session.setJob(job)
        assertTrue(session.isGenerating)

        // A stale DB snapshot (e.g. only the first turn) must NOT replace the live state.
        val stale = Conversation.ofId(id).copy(title = "stale-db-first-turn")
        val applied = applyConversationSnapshotIfIdle(session, stale)

        assertFalse(applied)
        assertEquals("live-streaming", session.state.value.title)

        release.complete(Unit)
        job.join()
        scope.cancel()
    }

    @Test
    fun `applyConversationSnapshotIfIdle applies again once generation has ended`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}

        val release = CompletableDeferred<Unit>()
        val job = scope.launch { withContext(NonCancellable) { release.await() } }
        session.setJob(job)
        assertTrue(session.isGenerating)

        // While generating: the snapshot is refused and the live state is preserved.
        assertFalse(applyConversationSnapshotIfIdle(session, Conversation.ofId(id).copy(title = "stale")))

        // Generation completes -> the session is idle again -> a snapshot now applies.
        release.complete(Unit)
        job.join()
        assertFalse(session.isGenerating)

        val fresh = Conversation.ofId(id).copy(title = "fresh-db")
        assertTrue(applyConversationSnapshotIfIdle(session, fresh))
        assertEquals("fresh-db", session.state.value.title)

        scope.cancel()
    }

    @Test
    fun `applyConversationSnapshotIfIdle rejects a mismatched conversation id`() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(id, Conversation.ofId(id), scope) {}

        val wrongId = Conversation.ofId(Uuid.random()).copy(title = "wrong")

        assertFalse(applyConversationSnapshotIfIdle(session, wrongId))
        // The session state is untouched (still the blank placeholder).
        assertTrue(session.state.value.title.isBlank())

        scope.cancel()
    }
}
