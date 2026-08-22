package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AssistantMemoryExportTest {

    private fun entry(
        title: String = "t",
        content: String = "c",
        mode: String = "core",
        tags: List<String> = emptyList(),
        importance: Int = 0,
        createdAt: Long = 100L,
        updatedAt: Long = 200L,
        lastAccessedAt: Long = 300L,
        accessCount: Int = 7,
        sourceConversationId: String? = null,
        archived: Boolean = false,
    ) = AssistantMemoryEntry(
        title = title, content = content, mode = mode, tags = tags, importance = importance,
        createdAt = createdAt, updatedAt = updatedAt, lastAccessedAt = lastAccessedAt,
        accessCount = accessCount, sourceConversationId = sourceConversationId, archived = archived,
    )

    private val fixedTime = LocalDateTime.of(2026, 8, 22, 12, 0, 0)

    // ---- envelope build + scope filtering ----

    @Test
    fun `envelope carries all persisted fields losslessly and omits device-local id`() {
        val export = buildAssistantMemoryExport(
            memories = listOf(entry(sourceConversationId = "conv-1", archived = true)),
            sourceAssistantId = "assistant-uuid",
            sourceAssistantName = "Jarvis",
            scope = MEMORY_SCOPE_ALL,
            exportedAt = fixedTime,
        )
        assertEquals(AssistantMemoryExport.FORMAT, export.format)
        assertEquals(1, export.formatVersion)
        assertEquals("2026-08-22T12:00:00", export.exportedAt)
        assertEquals("assistant-uuid", export.sourceAssistantId)
        assertEquals("Jarvis", export.sourceAssistantName)
        assertEquals(MEMORY_SCOPE_ALL, export.scope)

        val doc = Json.parseToJsonElement(serializeAssistantMemoryExport(export)).jsonObject
        val memory = doc["memories"]!!.jsonArray.single().jsonObject
        assertEquals("t", memory["title"]!!.jsonPrimitive.content)
        assertEquals("c", memory["content"]!!.jsonPrimitive.content)
        assertEquals("core", memory["mode"]!!.jsonPrimitive.content)
        assertEquals(300L, memory["lastAccessedAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(7, memory["accessCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, memory["archived"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("conv-1", memory["sourceConversationId"]!!.jsonPrimitive.content)
        // Device-local auto-increment id is deliberately not part of the format.
        assertTrue(!memory.containsKey("id"))
        // Tags serialize as a proper JSON array.
        assertTrue(memory["tags"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `scope filtering keeps only requested mode`() {
        val entries = listOf(entry(mode = "core"), entry(mode = "bank"), entry(mode = "BANK"), entry(mode = "weird"))
        assertEquals(2, filterMemoriesByScope(entries, MEMORY_SCOPE_CORE).size)
        assertEquals(2, filterMemoriesByScope(entries, MEMORY_SCOPE_BANK).size)
        assertEquals(4, filterMemoriesByScope(entries, MEMORY_SCOPE_ALL).size)
        assertEquals(4, filterMemoriesByScope(entries, "anything-else").size)
    }

    @Test
    fun `unknown scope passed to builder normalizes to all`() {
        val export = buildAssistantMemoryExport(
            memories = listOf(entry(mode = "bank")),
            sourceAssistantId = "a",
            sourceAssistantName = "A",
            scope = "bogus",
            exportedAt = fixedTime,
        )
        assertEquals(MEMORY_SCOPE_ALL, export.scope)
        assertEquals(1, export.memories.size)
    }

    // ---- filename sanitization ----

    @Test
    fun `filename sanitizes name and falls back to assistant id`() {
        val name = assistantMemoryExportFileName("My Assistant!! v2", "abcdef123456", fixedTime)
        assertEquals("rikkahub-assistant-memory-My-Assistant-v2-20260822-120000.json", name)
        val fallback = assistantMemoryExportFileName("   ///  ", "abcdef123456", fixedTime)
        assertEquals("rikkahub-assistant-memory-abcdef12-20260822-120000.json", fallback)
    }

    // ---- import parse + validate ----

    private fun document(memoriesJson: String, format: String = AssistantMemoryExport.FORMAT, version: Int = 1): String = """
        {
          "format": "$format",
          "formatVersion": $version,
          "exportedAt": "2026-08-22T12:00:00",
          "sourceAssistantId": "src-uuid",
          "sourceAssistantName": "Source",
          "scope": "all",
          "memories": $memoriesJson
        }
    """.trimIndent()

    @Test
    fun `valid document parses with defaults for missing optional fields`() {
        val parsed = parseAssistantMemoryImport(
            document("""[{"content":"b"}]""")
        )
        val success = parsed as AssistantMemoryImportParse.Success
        assertEquals("src-uuid", success.sourceAssistantId)
        assertEquals("Source", success.sourceAssistantName)
        val memory = success.memories.single()
        // Missing optional fields fall back to defaults; unknown mode normalizes to core.
        assertEquals("core", memory.mode)
        assertEquals("", memory.title)
        assertEquals(0, memory.importance)
        assertEquals(0L, memory.createdAt)
        assertEquals(emptyList<String>(), memory.tags)
        assertEquals(null, memory.sourceConversationId)
        assertEquals(false, memory.archived)
    }

    @Test
    fun `bank mode survives round trip`() {
        val parsed = parseAssistantMemoryImport(document("""[{"mode":"bank","tags":["x","y"]}]"""))
        val memory = (parsed as AssistantMemoryImportParse.Success).memories.single()
        assertEquals("bank", memory.mode)
        assertEquals(listOf("x", "y"), memory.tags)
    }

    @Test
    fun `foreign format is rejected`() {
        val parsed = parseAssistantMemoryImport(
            document("""[]""", format = "rikkahub-conversation-raw")
        )
        assertEquals(MEMORY_IMPORT_FAILURE_UNKNOWN_FORMAT, (parsed as AssistantMemoryImportParse.Failure).reason)
    }

    @Test
    fun `arbitrary json file is rejected as unknown format`() {
        val parsed = parseAssistantMemoryImport("""{"hello":"world"}""")
        assertEquals(MEMORY_IMPORT_FAILURE_UNKNOWN_FORMAT, (parsed as AssistantMemoryImportParse.Failure).reason)
    }

    @Test
    fun `malformed json is rejected`() {
        val parsed = parseAssistantMemoryImport("{not json")
        assertEquals(MEMORY_IMPORT_FAILURE_INVALID_JSON, (parsed as AssistantMemoryImportParse.Failure).reason)
    }

    @Test
    fun `future format version is rejected`() {
        val parsed = parseAssistantMemoryImport(document("""[{"title":"a"}]""", version = 99))
        assertEquals(
            MEMORY_IMPORT_FAILURE_UNSUPPORTED_VERSION,
            (parsed as AssistantMemoryImportParse.Failure).reason
        )
    }

    @Test
    fun `empty memory list is rejected`() {
        val parsed = parseAssistantMemoryImport(document("""[]"""))
        assertEquals(MEMORY_IMPORT_FAILURE_EMPTY, (parsed as AssistantMemoryImportParse.Failure).reason)
    }

    // ---- dedupe skip logic ----

    @Test
    fun `exact mode title content match is skipped others import`() {
        val existing = listOf(
            AssistantMemory(id = 1, content = "Same content ", title = " Same Title ", mode = "core"),
            AssistantMemory(id = 2, content = "other", title = "other", mode = "bank"),
        )
        val incoming = listOf(
            // duplicate of #1 despite whitespace differences and different metadata
            entry(title = "Same Title", content = "Same content", mode = "CORE", importance = 99),
            // same text but bank vs core -> NOT a duplicate
            entry(title = "Same Title", content = "Same content", mode = "bank"),
            // brand new
            entry(title = "fresh", content = "fresh body", mode = "core"),
        )
        val plan = planMemoryImport(incoming, existing)
        assertEquals(2, plan.toImport.size)
        assertEquals(1, plan.skippedAsDuplicate)
        assertEquals(listOf("bank", "core"), plan.toImport.map { it.mode })
    }

    @Test
    fun `duplicate entries inside a single file are collapsed`() {
        // A hand-merged file containing the same memory twice must import it ONCE;
        // newly-planned entries count as present for the rest of the same run.
        val plan = planMemoryImport(
            listOf(
                entry(title = "t", content = "c", mode = "core"),
                entry(title = " t ", content = " c ", mode = "CORE"),
                entry(title = "other", content = "other", mode = "core"),
            ),
            existing = emptyList(),
        )
        assertEquals(2, plan.toImport.size)
        assertEquals(1, plan.skippedAsDuplicate)
    }

    @Test
    fun `re-importing an entire export skips everything`() {
        val existing = listOf(
            AssistantMemory(id = 1, content = "c1", title = "t1", mode = "core"),
            AssistantMemory(id = 2, content = "c2", title = "t2", mode = "bank"),
        )
        val plan = planMemoryImport(
            listOf(
                entry(title = "t1", content = "c1", mode = "core"),
                entry(title = "t2", content = "c2", mode = "bank"),
            ),
            existing,
        )
        assertEquals(0, plan.toImport.size)
        assertEquals(2, plan.skippedAsDuplicate)
    }
}
