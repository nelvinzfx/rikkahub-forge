package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.local.readConversationTool
import me.rerere.rikkahub.data.ai.tools.local.searchConversationsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the "conversation recall is ALWAYS ON" contract:
 *
 *  - [LocalTools.getTools] registers search_conversations / read_conversation /
 *    open_conversation unconditionally — there is no switch, no menu entry and no
 *    per-assistant [LocalToolOption] gate any more. Instantiating [LocalTools] needs a real
 *    Android Context + Koin graph, so the registration itself is pinned structurally (the
 *    `options.contains(LocalToolOption.ConversationRecall)` gate must not come back) plus the
 *    tool factories are exercised directly for their advertised names.
 *  - [LocalToolOption.ConversationRecall] must STAY declared as a legacy decode target.
 *    Assistants persist `localTools` as a polymorphic kotlinx-serialization list and
 *    `ignoreUnknownKeys` does NOT cover unknown polymorphic serial names, so deleting the
 *    object would crash decoding for every existing assistant that stored
 *    "conversation_recall". Same reasoning as [LocalToolOption.Termux].
 */
class ConversationRecallAlwaysOnTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- legacy decode target ----

    @Test
    fun `stored conversation_recall still decodes into the legacy option`() {
        val decoded = json.decodeFromString<List<LocalToolOption>>(
            """[{"type":"time_info"},{"type":"conversation_recall"},{"type":"termux"}]"""
        )

        assertEquals(
            listOf(
                LocalToolOption.TimeInfo,
                LocalToolOption.ConversationRecall,
                LocalToolOption.Termux,
            ),
            decoded,
        )
    }

    @Test
    fun `legacy option round-trips through its stable serial name`() {
        val encoded = json.encodeToString<List<LocalToolOption>>(listOf(LocalToolOption.ConversationRecall))

        assertTrue(encoded.contains("conversation_recall"))
        assertEquals(
            listOf(LocalToolOption.ConversationRecall),
            json.decodeFromString<List<LocalToolOption>>(encoded),
        )
    }

    @Test
    fun `an unknown polymorphic serial name would crash decoding - why the object is kept`() {
        // This is the whole reason LocalToolOption.ConversationRecall (and .Termux) survive:
        // ignoreUnknownKeys does not save us from an unknown polymorphic discriminator value.
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<List<LocalToolOption>>("""[{"type":"deleted_option"}]""")
        }
    }

    // ---- always-on registration ----

    @Test
    fun `recall tool factories advertise the stable tool names`() {
        assertEquals("search_conversations", searchConversationsTool { _, _ -> emptyList() }.name)
        assertEquals("read_conversation", readConversationTool { null }.name)
    }

    @Test
    fun `getTools has no ConversationRecall gate`() {
        val source = localToolsSource() ?: return // source not reachable from this working dir
        assertTrue(
            "LocalTools.kt should still register the recall tools",
            source.contains("searchConversationsTool(conversationRepo)"),
        )
        assertFalse(
            "conversation recall must never be gated again — it is always on",
            source.contains("options.contains(LocalToolOption.ConversationRecall)"),
        )
        assertTrue(
            "the legacy decode target must stay declared",
            source.contains("""@SerialName("conversation_recall")"""),
        )
    }

    private fun localToolsSource(): String? {
        val relative = "src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt"
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
