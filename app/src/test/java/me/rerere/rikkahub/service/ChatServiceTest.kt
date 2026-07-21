package me.rerere.rikkahub.service

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
    fun `model metadata context length wins over legacy assistant fallback`() {
        val assistant = Assistant(autoCompactionContextWindow = 410_000)
        assertEquals(200_000, resolvedContextWindow(Model(contextLength = 200_000), assistant))
        assertEquals(410_000, resolvedContextWindow(Model(contextLength = null), assistant))
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
