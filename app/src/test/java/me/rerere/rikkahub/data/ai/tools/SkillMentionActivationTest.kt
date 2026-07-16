package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillMentionActivationTest {
    private fun meta(name: String, autoLoad: Boolean = false) = SkillMetadata(
        name = name,
        description = "description for $name",
        autoLoad = autoLoad,
        skillDir = File("/tmp/$name"),
    )

    @Test
    fun `find mentions keeps enabled canonical names`() {
        val found = findMentionedSkillNames(
            text = "use @Proof-Driven-Engineering with @ponytail and @disabled",
            enabledSkills = setOf("proof-driven-engineering", "ponytail"),
        )

        assertEquals(listOf("proof-driven-engineering", "ponytail"), found)
    }

    @Test
    fun `email path and url fragments are not skill mentions`() {
        val found = findMentionedSkillNames(
            text = "mail x@ponytail, open @ponytail/file and https://x/@ponytail",
            enabledSkills = setOf("ponytail"),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun `mentioned lazy skills are injected directly for the turn`() {
        val reads = mutableListOf<String>()
        val prompt = buildMentionedSkillsPrompt(
            userText = "@proof-driven-engineering inspect this",
            enabledSkills = setOf("proof-driven-engineering", "agent-core"),
            allSkills = listOf(meta("proof-driven-engineering"), meta("agent-core", autoLoad = true)),
            contentReader = { name ->
                reads += name
                "instructions for $name"
            },
        )

        assertEquals(listOf("proof-driven-engineering"), reads)
        assertTrue(prompt.contains("activated the following skills for this turn"))
        assertTrue(prompt.contains("instructions for proof-driven-engineering"))
        assertTrue(prompt.contains("do not call use_skill"))
        assertFalse(prompt.contains("instructions for agent-core"))
    }
}
