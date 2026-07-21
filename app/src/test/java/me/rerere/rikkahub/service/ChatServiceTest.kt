package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `auto compaction gates generation only when an answered turn reaches threshold`() {
        assertTrue(shouldAutoCompactBeforeGeneration(true, true, 83_616, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(true, true, 83_615, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(false, true, 83_616, 100_000, 16_384))
        assertFalse(shouldAutoCompactBeforeGeneration(true, false, 80_000, 100_000, 80))
    }
}
