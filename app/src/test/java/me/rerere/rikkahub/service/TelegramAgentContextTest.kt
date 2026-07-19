package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAgentContextTest {

    @Test
    fun `context contains routing metadata without model identity`() {
        val context = buildTelegramAgentContext(
            chatId = 123456789L,
            recentCommands = emptyList(),
            nowMs = 1_000_000L,
            firstTurnOfChat = false,
        )

        assertTrue(context.startsWith("[telegram_context (host metadata):"))
        assertTrue(context.contains("Origin: Telegram"))
        assertTrue(context.contains("123456789"))
        assertFalse(context.contains("You are running as model", ignoreCase = true))
        assertFalse(context.contains("via provider", ignoreCase = true))
        assertFalse(context.contains("current assistant is", ignoreCase = true))
        assertFalse(context.contains("Background identity", ignoreCase = true))
        assertFalse(context.contains("trust this over your priors", ignoreCase = true))
    }

    @Test
    fun `context preserves recent commands and attachment capabilities`() {
        val context = buildTelegramAgentContext(
            chatId = 42L,
            recentCommands = listOf("/model kimi-k2.5" to 940_000L),
            nowMs = 1_000_000L,
            firstTurnOfChat = true,
            hasAudioAttachment = true,
            hasPhotoAttachment = true,
            modelCanSeeImages = false,
        )

        assertTrue(context.contains("/model kimi-k2.5 (1m ago)"))
        assertTrue(context.contains("first turn in this Telegram chat"))
        assertTrue(context.contains("whisper_status"))
        assertTrue(context.contains("YOU CANNOT SEE IT"))
    }

    @Test
    fun `vision-capable context says photos are directly visible`() {
        val context = buildTelegramAgentContext(
            chatId = 42L,
            recentCommands = emptyList(),
            firstTurnOfChat = false,
            hasPhotoAttachment = true,
            modelCanSeeImages = true,
        )

        assertTrue(context.contains("You can view them directly"))
        assertFalse(context.contains("YOU CANNOT SEE IT"))
    }
}
