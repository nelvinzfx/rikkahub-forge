package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxToolTimingTest {
    private fun elapsedOf(part: UIMessagePart): Long? =
        ((Json.parseToJsonElement((part as UIMessagePart.Text).text) as JsonObject)["elapsed_ms"] as? JsonPrimitive)
            ?.longOrNull

    @Test
    fun stampAddsElapsedToFirstJsonObjectTextOnly() {
        val metadata = buildJsonObject { put("type", "diff") }
        val parts = listOf(
            UIMessagePart.Text("not json"),
            UIMessagePart.Text("""{"success":true}""", metadata = metadata),
            UIMessagePart.Text("""{"success":false}"""),
        )
        val stamped = stampTermuxElapsedMs(parts, 321L)
        assertEquals("not json", (stamped[0] as UIMessagePart.Text).text)
        assertEquals(321L, elapsedOf(stamped[1]))
        // Metadata (e.g. DiffMetadata) survives the stamp.
        assertEquals(metadata, stamped[1].metadata)
        // Only the first JSON envelope is stamped.
        assertNull(elapsedOf(stamped[2]))
    }

    @Test
    fun stampNeverOverwritesExistingElapsedAndSkipsNonObjects() {
        val existing = listOf(UIMessagePart.Text("""{"elapsed_ms":7}"""))
        assertEquals(7L, elapsedOf(stampTermuxElapsedMs(existing, 999L)[0]))
        val arrayOnly = listOf(UIMessagePart.Text("""[1,2]"""))
        assertEquals("[1,2]", (stampTermuxElapsedMs(arrayOnly, 5L)[0] as UIMessagePart.Text).text)
    }

    @Test
    fun wrappedToolStampsNonNegativeElapsedOnSuccessAndErrorEnvelopes() = runBlocking {
        val tool = Tool(
            name = "termux_fake",
            description = "fake",
            execute = { input ->
                listOf(UIMessagePart.Text(if (input == JsonNull) """{"success":true}""" else """{"error":"boom"}"""))
            },
        ).withTermuxElapsedTime()
        val success = elapsedOf(tool.execute(JsonNull).single())
        val error = elapsedOf(tool.execute(JsonPrimitive(1)).single())
        assertTrue(success != null && success >= 0L)
        assertTrue(error != null && error >= 0L)
    }
}
