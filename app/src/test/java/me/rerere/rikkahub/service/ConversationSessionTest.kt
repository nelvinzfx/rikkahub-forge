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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
