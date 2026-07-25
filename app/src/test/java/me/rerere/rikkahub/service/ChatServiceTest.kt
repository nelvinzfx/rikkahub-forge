package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration and output cap`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model, maxTokens = 4_000)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(4_000, params.maxTokens)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `auto compaction gates only after answered turn exceeds threshold`() {
        assertTrue(shouldAutoCompactBeforeGeneration(true, true, 83_617, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(true, true, 83_616, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(false, true, 90_000, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(true, false, 90_000, 100_000, 16_384))
    }

    @Test
    fun `configured compaction context window overrides model metadata`() {
        val configured = Assistant(autoCompactionContextWindow = 410_000)
        val automatic = Assistant(autoCompactionContextWindow = 0)
        assertEquals(410_000, resolvedContextWindow(Model(contextLength = 200_000), configured))
        assertEquals(200_000, resolvedContextWindow(Model(contextLength = 200_000), automatic))
        assertEquals(200_000, resolvedContextWindow(Model(contextLength = null), automatic))
    }

    @Test
    fun `regeneration entry waits for prior job and parent stop gate`() = runBlocking {
        coroutineScope {
            val previous = Job()
            val stopGate = CompletableDeferred<Unit>()
            var entered = false

            val waiter = async {
                awaitGenerationEntryReady(previous) { stopGate.await() }
                entered = true
            }
            yield()
            assertFalse(entered)

            previous.complete()
            yield()
            assertFalse(entered)

            stopGate.complete(Unit)
            waiter.await()
            assertTrue(entered)
        }
    }

    @Test
    fun `only current stop epoch may release its fence`() {
        val lock = Any()
        val oldEpoch = Any()
        val successorEpoch = Any()
        val epochs = mutableMapOf("chat" to oldEpoch)
        var releases = 0

        assertTrue(releaseEpochIfCurrent(lock, epochs, "chat", oldEpoch) { releases++ })
        assertEquals(1, releases)
        assertFalse(epochs.containsKey("chat"))

        epochs["chat"] = successorEpoch
        assertFalse(releaseEpochIfCurrent(lock, epochs, "chat", oldEpoch) { releases++ })
        assertEquals(1, releases)
        assertTrue(epochs["chat"] === successorEpoch)
    }

    @Test
    fun `context overflow detector recognizes nested provider errors only`() {
        val overflow = IllegalStateException(
            "wrapper",
            Exception("400 context_length_exceeded: maximum context length reached"),
        )
        assertTrue(isContextOverflowError(overflow))
        assertFalse(isContextOverflowError(Exception("401 invalid api key")))
    }
}
