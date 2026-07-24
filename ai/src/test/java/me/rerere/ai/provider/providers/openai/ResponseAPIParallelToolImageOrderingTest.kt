package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseAPIParallelToolImageOrderingTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `parallel tool image lifts follow every function output`() {
        val result = api.buildMessages(
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
            listOf(
                "user",
                "assistant",
                "function_call",
                "function_call_output",
                "function_call",
                "function_call_output",
                "user",
                "user",
            ),
            result.map(::itemKind),
        )

        assertEquals("call_1", result[2].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("call_1", result[3].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("call_2", result[4].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("call_2", result[5].jsonObject["call_id"]?.jsonPrimitive?.content)

        assertLiftedImage(
            message = result[6].jsonObject,
            expectedLabel = "[Tool image_one produced the image(s) below.]",
            expectedDataUri = "data:image/png;base64,AA==",
        )
        assertLiftedImage(
            message = result[7].jsonObject,
            expectedLabel = "[Tool image_two produced the image(s) below.]",
            expectedDataUri = "data:image/jpeg;base64,AQ==",
        )
    }

    private fun itemKind(item: kotlinx.serialization.json.JsonElement): String? {
        val objectValue = item.jsonObject
        return objectValue["type"]?.jsonPrimitive?.content
            ?: objectValue["role"]?.jsonPrimitive?.content
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
            content[1].jsonObject["image_url"]?.jsonPrimitive?.content,
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
