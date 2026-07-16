package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
    fun `mentions execute use skill before the request with duplicates removed`() = runBlocking {
        val calls = mutableListOf<String>()
        val useSkill = Tool(
            name = "use_skill",
            description = "test",
            execute = { input ->
                val name = input.jsonObject["name"]!!.jsonPrimitive.content
                calls += name
                listOf(UIMessagePart.Text("instructions for $name"))
            },
        )
        val userText = "@proof-driven-engineering @ponytail @proof-driven-engineering inspect this"

        val prompt = buildMentionedSkillsPrompt(
            userText = userText,
            enabledSkills = setOf("proof-driven-engineering", "ponytail", "agent-core"),
            allSkills = listOf(
                meta("proof-driven-engineering"),
                meta("ponytail"),
                meta("agent-core", autoLoad = true),
            ),
            useSkillTool = useSkill,
        )

        assertEquals(listOf("proof-driven-engineering", "ponytail"), calls)
        assertTrue(prompt.contains("use_skill calls were automatically executed"))
        assertTrue(prompt.contains("instructions for proof-driven-engineering"))
        assertTrue(prompt.contains("instructions for ponytail"))
        assertEquals("@proof-driven-engineering @ponytail @proof-driven-engineering inspect this", userText)
    }

    @Test
    fun `disabled missing and ordinary mentions do not execute use skill`() = runBlocking {
        val calls = mutableListOf<String>()
        val useSkill = Tool(
            name = "use_skill",
            description = "test",
            execute = { input ->
                calls += input.jsonObject["name"]!!.jsonPrimitive.content
                listOf(UIMessagePart.Text("unexpected"))
            },
        )

        val prompt = buildMentionedSkillsPrompt(
            userText = "hello @disabled @missing x@enabled and @enabled/file",
            enabledSkills = setOf("enabled", "missing"),
            allSkills = listOf(meta("enabled"), meta("disabled")),
            useSkillTool = useSkill,
        )

        assertTrue(calls.isEmpty())
        assertTrue(prompt.isEmpty())
    }

    @Test
    fun `cancellation from automatic use skill is propagated`() {
        val useSkill = Tool(
            name = "use_skill",
            description = "test",
            execute = { throw CancellationException("cancelled") },
        )

        try {
            runBlocking {
                buildMentionedSkillsPrompt(
                    userText = "@ponytail inspect this",
                    enabledSkills = setOf("ponytail"),
                    allSkills = listOf(meta("ponytail")),
                    useSkillTool = useSkill,
                )
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Generation cancellation must not be converted into skill prompt content.
        }
    }

    @Test
    fun `no mention leaves use skill path untouched`() = runBlocking {
        var called = false
        val useSkill = Tool(
            name = "use_skill",
            description = "test",
            execute = {
                called = true
                listOf(UIMessagePart.Text("unexpected"))
            },
        )

        val prompt = buildMentionedSkillsPrompt(
            userText = "inspect this normally",
            enabledSkills = setOf("ponytail"),
            allSkills = listOf(meta("ponytail")),
            useSkillTool = useSkill,
        )

        assertFalse(called)
        assertTrue(prompt.isEmpty())
    }
}
