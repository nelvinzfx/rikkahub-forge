package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRecallToolsTest {
    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002")

    @Test
    fun `invalid uuid is rejected before loading`() {
        var loaded = false
        val tool = readConversationTool {
            loaded = true
            null
        }

        val result = obj(execTool(tool, """{"conversationId":"not-a-uuid"}"""))

        assertEquals("invalid_conversation_id", result["error"]!!.jsonPrimitive.content)
        assertFalse(loaded)
    }

    @Test
    fun `missing conversation returns a stable error`() {
        val tool = readConversationTool { null }

        val result = obj(execTool(tool, """{"conversationId":"$conversationId"}"""))

        assertEquals("conversation_not_found", result["error"]!!.jsonPrimitive.content)
        assertEquals(conversationId.toString(), result["conversationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read returns metadata and only the selected message branch`() {
        val conversation = conversation(
            MessageNode(
                messages = listOf(
                    UIMessage.user("discarded alternative"),
                    UIMessage.user("selected investigation question"),
                ),
                selectIndex = 1,
            ),
            MessageNode.of(UIMessage.assistant("selected answer")),
        )
        val tool = readConversationTool { conversation }

        val result = obj(execTool(tool, """{"conversationId":"$conversationId"}"""))
        val transcript = result["transcript"]!!.jsonPrimitive.content

        assertEquals(conversationId.toString(), result["conversationId"]!!.jsonPrimitive.content)
        assertEquals(assistantId.toString(), result["assistantId"]!!.jsonPrimitive.content)
        assertEquals("Investigation", result["title"]!!.jsonPrimitive.content)
        assertEquals(2, result["messageCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["alternateMessageCount"]!!.jsonPrimitive.int)
        assertTrue(transcript.contains("role: user"))
        assertTrue(transcript.contains("selected investigation question"))
        assertTrue(transcript.contains("selected answer"))
        assertTrue(transcript.contains("selectedAlternative: 1"))
        assertFalse(transcript.contains("discarded alternative"))
        assertFalse(result["truncated"]!!.jsonPrimitive.boolean)
        assertNull(result["nextOffset"])
    }

    @Test
    fun `long transcript pages losslessly with next offset`() {
        val conversation = conversation(MessageNode.of(UIMessage.user("x".repeat(300))))
        val tool = readConversationTool { conversation }

        val first = obj(execTool(tool, """{"conversationId":"$conversationId","maxChars":100}"""))
        val nextOffset = first["nextOffset"]!!.jsonPrimitive.int
        val second = obj(execTool(
            tool,
            """{"conversationId":"$conversationId","offset":$nextOffset,"maxChars":100}""",
        ))

        assertTrue(first["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(100, first["returnedChars"]!!.jsonPrimitive.int)
        assertEquals(nextOffset, second["offset"]!!.jsonPrimitive.int)
        assertNotNull(second["transcript"])
        assertEquals(
            renderConversationTranscript(conversation).substring(0, 200),
            first["transcript"]!!.jsonPrimitive.content + second["transcript"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `reasoning text is not exposed`() {
        val message = UIMessage.assistant("answer").copy(
            parts = listOf(
                UIMessagePart.Reasoning("private reasoning details"),
                UIMessagePart.Text("answer"),
            ),
        )
        val transcript = renderConversationTranscript(conversation(MessageNode.of(message)))

        assertTrue(transcript.contains("[reasoning omitted: 25 chars]"))
        assertFalse(transcript.contains("private reasoning details"))
        assertTrue(transcript.contains("answer"))
    }

    @Test
    fun `embedded media data is omitted from transcript`() {
        val embedded = "data:image/png;base64," + "A".repeat(200)
        val message = UIMessage.user("image attached").copy(
            parts = listOf(UIMessagePart.Text("image attached"), UIMessagePart.Image(embedded)),
        )
        val transcript = renderConversationTranscript(conversation(MessageNode.of(message)))

        assertTrue(transcript.contains("embedded data omitted"))
        assertFalse(transcript.contains("A".repeat(50)))
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        id = conversationId,
        assistantId = assistantId,
        title = "Investigation",
        messageNodes = nodes.toList(),
        createAt = Instant.ofEpochMilli(1_000),
        updateAt = Instant.ofEpochMilli(2_000),
    )

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
}
