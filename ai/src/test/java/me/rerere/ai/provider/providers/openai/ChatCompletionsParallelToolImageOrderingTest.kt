package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsParallelToolImageOrderingTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun buildMessages(messages: List<UIMessage>): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, messages, true, false) as JsonArray
    }

    @Test
    fun `parallel tool image lifts follow every tool response`() {
        val result = buildMessages(
            listOf(
                UIMessage.user("Generate two images"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("Generating both images"),
                        executedImageTool(
                            callId = "call_1",
                            name = "image_one",
                            text = "First image generated",
                            dataUri = "data:image/png;base64,AA==",
                        ),
                        executedImageTool(
                            callId = "call_2",
                            name = "image_two",
                            text = "Second image generated",
                            dataUri = "data:image/jpeg;base64,AQ==",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user", "user"),
            result.map { it.jsonObject["role"]?.jsonPrimitive?.content },
        )

        val assistantToolCalls = result[1].jsonObject["tool_calls"]!!.jsonArray
        assertEquals(
            listOf("call_1", "call_2"),
            assistantToolCalls.map { it.jsonObject["id"]?.jsonPrimitive?.content },
        )
        assertEquals("call_1", result[2].jsonObject["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("call_2", result[3].jsonObject["tool_call_id"]?.jsonPrimitive?.content)

        assertLiftedImage(
            message = result[4].jsonObject,
            expectedLabel = "[Tool image_one produced the image(s) below.]",
            expectedDataUri = "data:image/png;base64,AA==",
        )
        assertLiftedImage(
            message = result[5].jsonObject,
            expectedLabel = "[Tool image_two produced the image(s) below.]",
            expectedDataUri = "data:image/jpeg;base64,AQ==",
        )
    }

    private fun assertLiftedImage(
        message: kotlinx.serialization.json.JsonObject,
        expectedLabel: String,
        expectedDataUri: String,
    ) {
        val content = message["content"]!!.jsonArray
        assertEquals(expectedLabel, content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(
            expectedDataUri,
            content[1].jsonObject["image_url"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content,
        )
    }

    private fun executedImageTool(
        callId: String,
        name: String,
        text: String,
        dataUri: String,
    ) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = name,
        input = "{}",
        output = listOf(
            UIMessagePart.Text(text),
            UIMessagePart.Image(dataUri),
        ),
    )
}
