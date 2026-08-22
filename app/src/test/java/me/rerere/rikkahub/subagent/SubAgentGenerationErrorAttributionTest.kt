package me.rerere.rikkahub.subagent

import kotlin.uuid.Uuid
import me.rerere.rikkahub.service.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for the ox-alpha 401 incident (Aug 22 2026): a sub-agent dispatch against a
 * provider with a broken API key failed with the generic
 * "max_steps_exhausted_after_tool: worker finished without any final assistant text"
 * envelope. The real error (401 from the provider) existed only in ChatService's
 * in-memory UI error channel — visible to a human watching the screen, invisible to
 * subagent_get, the run record, and the parent notification. These tests pin the
 * attribution logic that diffs that channel so the root cause lands in the run record.
 */
class SubAgentGenerationErrorAttributionTest {

    private fun chatError(
        conversationId: Uuid,
        timestamp: Long,
        message: String? = "HTTP 401 Unauthorized",
    ) = ChatError(
        error = IllegalStateException(message),
        conversationId = conversationId,
        timestamp = timestamp,
    )

    @Test fun `earliest new error for the worker conversation wins`() {
        val conv = Uuid.random()
        val early = chatError(conv, timestamp = 100L, message = "401 Unauthorized")
        val late = chatError(conv, timestamp = 200L, message = "follow-on failure")
        val picked = firstNewChatError(listOf(early, late), conv, emptySet())
        assertEquals("the earliest new error is the root cause", "401 Unauthorized", picked?.error?.message)
    }

    @Test fun `baseline errors from earlier attempts are excluded`() {
        val conv = Uuid.random()
        val stale = chatError(conv, timestamp = 50L, message = "stale error from a previous attempt")
        val fresh = chatError(conv, timestamp = 100L, message = "HTTP 401 Unauthorized")
        val picked = firstNewChatError(listOf(stale, fresh), conv, setOf(stale.id))
        assertEquals("only post-baseline errors are attributed", "HTTP 401 Unauthorized", picked?.error?.message)
    }

    @Test fun `errors for other conversations are ignored`() {
        val worker = Uuid.random()
        val other = Uuid.random()
        val picked = firstNewChatError(listOf(chatError(other, 100L)), worker, emptySet())
        assertNull(picked)
    }

    @Test fun `empty channel yields null so the generic reason stays`() {
        val picked = firstNewChatError(emptyList(), Uuid.random(), emptySet())
        assertNull(picked)
        assertNull(formatGenerationFailure(picked))
    }

    @Test fun `format embeds exception class and message with the no-text suffix`() {
        val formatted = formatGenerationFailure(chatError(Uuid.random(), 100L, message = "HTTP 401 Unauthorized"))
        assertEquals(
            "generation_error: IllegalStateException: HTTP 401 Unauthorized (worker produced no final assistant text)",
            formatted,
        )
    }

    @Test fun `format tolerates a null message`() {
        val formatted = formatGenerationFailure(chatError(Uuid.random(), 1L, message = null))
        assertEquals(
            "generation_error: IllegalStateException: (worker produced no final assistant text)",
            formatted,
        )
    }
}
