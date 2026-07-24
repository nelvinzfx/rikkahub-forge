package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the shapes emitted after provider adapters resolve each wire index
 * to its original tool-call id. Every continuation must reach [handleMessageChunk] with that id,
 * allowing its existing id-based branch to keep parallel argument streams isolated.
 */
class ParallelToolCallStreamingAssemblyTest {
    private val json = Json

    @Test
    fun `interleaved parallel tool argument deltas remain separate and parseable`() {
        var messages = listOf(UIMessage.user("run both tools"))

        // A normal parallel-call start: both calls are declared with ids and empty arguments.
        messages = messages.handleMessageChunk(
            chunk(
                tool(id = "call-0", name = "run_command", input = ""),
                tool(id = "call-1", name = "schedule_job", input = ""),
            )
        )

        // Provider adapters resolve wire indexes 0/1 back to call-0/call-1 before emitting.
        val deltas = listOf(
            tool(id = "call-0", input = "{\"command\":\"cd ~/rik"),
            tool(id = "call-1", input = "{\"at_unix_ms\":1784542200000,"),
            tool(id = "call-0", input = "kahub-agent\",\"timeout\":30}"),
            tool(id = "call-1", input = "\"schedule_type\":\"once\"}"),
        )
        deltas.forEach { delta -> messages = messages.handleMessageChunk(chunk(delta)) }

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        val expected = listOf(
            "{\"command\":\"cd ~/rikkahub-agent\",\"timeout\":30}",
            "{\"at_unix_ms\":1784542200000,\"schedule_type\":\"once\"}",
        )

        assertEquals(expected, tools.map { it.input })
        tools.forEach { json.parseToJsonElement(it.input).jsonObject }
    }

    @Test
    fun `parallel call boundary cannot fuse two complete argument objects`() {
        var messages = listOf(UIMessage.user("run both tools"))
        messages = messages.handleMessageChunk(
            chunk(
                tool(id = "call-0", name = "run_command", input = ""),
                tool(id = "call-1", name = "schedule_job", input = ""),
            )
        )

        val expected = listOf(
            "{\"command\":\"cd ~/rikkahub-agent\",\"timeout\":30}",
            "{\"at_unix_ms\":1784542200000,\"schedule_type\":\"once\"}",
        )
        expected.forEachIndexed { index, completeArgs ->
            messages = messages.handleMessageChunk(
                chunk(tool(id = "call-$index", input = completeArgs))
            )
        }

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(expected, tools.map { it.input })
        tools.forEach { json.parseToJsonElement(it.input).jsonObject }
    }

    @Test
    fun `provider echoing the tool name in every delta still yields a single name`() {
        var messages = listOf(UIMessage.user("start a server"))
        messages = messages.handleMessageChunk(
            chunk(tool(id = "call-0", name = "termux_run_command", input = ""))
        )

        // Some relays re-emit function.name on every continuation delta instead of only the
        // first. Assembly must keep the first name and never concatenate it.
        val fragments = listOf(
            "{\"command\":\"cd ~/app ",
            "&& PORT=18765 ",
            "exec python3 app.py\",",
            "\"background\":true}",
        )
        fragments.forEach { fragment ->
            messages = messages.handleMessageChunk(
                chunk(tool(id = "call-0", name = "termux_run_command", input = fragment))
            )
        }

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("termux_run_command", tool.toolName)
        assertEquals(
            "{\"command\":\"cd ~/app && PORT=18765 exec python3 app.py\",\"background\":true}",
            tool.input,
        )
        json.parseToJsonElement(tool.input).jsonObject
    }

    private fun tool(
        id: String = "",
        name: String = "",
        input: String,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
    )

    private fun chunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "stream",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )
}
