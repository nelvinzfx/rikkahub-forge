package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SkillCompletionProviderTest {
    private fun meta(name: String) = SkillMetadata(
        name = name,
        description = "description for $name",
        skillDir = File("/tmp/$name"),
    )

    @Test
    fun `skill mention completes enabled skill name`() = runBlocking {
        val provider = SkillCompletionProvider(
            listOf(meta("ponytail"), meta("proof-driven-engineering")),
        )

        val result = provider.complete(
            ChatCompletionContext(text = "run @pro", selection = TextRange(8)),
        )!!

        assertEquals(TextRange(4, 8), result.replacementRange)
        assertEquals("@proof-driven-engineering", result.items.first().label)
        assertEquals("@proof-driven-engineering ", result.items.first().insertText)
    }

    @Test
    fun `email address does not open skill completion`() = runBlocking {
        val provider = SkillCompletionProvider(listOf(meta("ponytail")))

        val result = provider.complete(
            ChatCompletionContext(text = "mail x@pony", selection = TextRange(11)),
        )

        assertNull(result)
    }
}
