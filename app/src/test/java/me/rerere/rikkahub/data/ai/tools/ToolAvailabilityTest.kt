package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the general "called while unavailable" resolution layer
 * (Termux gate flipped mid-conversation, MCP server disabled / disconnected /
 * re-synced, unknown names) and the per-generation tool-set change notice.
 */
class ToolAvailabilityTest {

    private val json = Json

    private fun server(
        slug: String = "deadbeef",
        name: String = "brave",
        enabled: Boolean = true,
        enabledForAssistant: Boolean = true,
        tools: Set<String> = setOf("search", "ask"),
        connected: Boolean = true,
    ) = McpServerSnapshot(
        slugPrefix = slug,
        name = name,
        enabled = enabled,
        enabledForAssistant = enabledForAssistant,
        knownToolNames = tools,
        connected = connected,
    )

    // ---- termux gate ----

    @Test
    fun `termux tool while gate off explains the settings switch`() {
        val info = ToolAvailability.inspect(
            toolName = "termux_run_command",
            termuxIntegrationEnabled = false,
            mcpServers = emptyList(),
        )
        requireNotNull(info)
        assertTrue(info.reason.contains("Termux integration is switched off"))
        assertTrue(info.recovery.contains("Settings > Termux"))
    }

    @Test
    fun `every gated termux-group name is covered while gate off`() {
        TERMUX_GROUP_TOOL_NAMES.forEach { name ->
            requireNotNull(
                ToolAvailability.inspect(name, termuxIntegrationEnabled = false, mcpServers = emptyList())
            ) { "$name should resolve to the termux-gate info while the gate is off" }
        }
    }

    @Test
    fun `termux tool while gate on resolves to null`() {
        assertNull(
            ToolAvailability.inspect(
                "termux_run_command",
                termuxIntegrationEnabled = true,
                mcpServers = emptyList(),
            )
        )
    }

    @Test
    fun `whisper tools live under the termux gate`() {
        assertTrue("transcribe_audio_file" in TERMUX_GROUP_TOOL_NAMES)
        assertTrue("whisper_status" in TERMUX_GROUP_TOOL_NAMES)
    }

    // ---- unknown names ----

    @Test
    fun `unknown tool name resolves to null`() {
        assertNull(
            ToolAvailability.inspect(
                "definitely_not_a_tool",
                termuxIntegrationEnabled = false,
                mcpServers = listOf(server()),
            )
        )
    }

    // ---- MCP sources ----

    @Test
    fun `mcp tool on disabled server explains disabled`() {
        val info = ToolAvailability.inspect(
            "mcp__deadbeef_brave__search",
            termuxIntegrationEnabled = true,
            mcpServers = listOf(server(enabled = false)),
        )
        requireNotNull(info)
        assertTrue(info.reason.contains("disabled"))
        assertTrue(info.reason.contains("brave"))
    }

    @Test
    fun `mcp tool on server not enabled for this assistant says so`() {
        val info = ToolAvailability.inspect(
            "mcp__deadbeef_brave__search",
            termuxIntegrationEnabled = true,
            mcpServers = listOf(server(enabledForAssistant = false)),
        )
        requireNotNull(info)
        assertTrue(info.reason.contains("not enabled for this assistant"))
    }

    @Test
    fun `mcp tool on disconnected server explains disconnected`() {
        val info = ToolAvailability.inspect(
            "mcp__deadbeef_brave__search",
            termuxIntegrationEnabled = true,
            mcpServers = listOf(server(connected = false)),
        )
        requireNotNull(info)
        assertTrue(info.reason.contains("disconnected"))
        assertTrue(info.recovery.contains("Settings > MCP servers"))
    }

    @Test
    fun `mcp tool that vanished in a re-sync explains the list change`() {
        val info = ToolAvailability.inspect(
            "mcp__deadbeef_brave__old_tool",
            termuxIntegrationEnabled = true,
            mcpServers = listOf(server()),
        )
        requireNotNull(info)
        assertTrue(info.reason.contains("no longer exposes"))
        assertTrue(info.reason.contains("old_tool"))
    }

    @Test
    fun `healthy mcp tool resolves to null`() {
        assertNull(
            ToolAvailability.inspect(
                "mcp__deadbeef_brave__search",
                termuxIntegrationEnabled = true,
                mcpServers = listOf(server()),
            )
        )
    }

    @Test
    fun `mcp name with unknown server slug resolves to null`() {
        assertNull(
            ToolAvailability.inspect(
                "mcp__cafebabe_other__search",
                termuxIntegrationEnabled = true,
                mcpServers = listOf(server()),
            )
        )
    }

    @Test
    fun `malformed mcp name resolves to null`() {
        assertNull(
            ToolAvailability.inspect(
                "mcp__noseparator",
                termuxIntegrationEnabled = true,
                mcpServers = listOf(server()),
            )
        )
    }

    // ---- envelope ----

    @Test
    fun `envelope with cause carries name detail and recovery`() {
        val info = UnavailableToolInfo(reason = "gated off", recovery = "turn it back on")
        val obj = json.parseToJsonElement(
            ToolAvailability.buildUnavailableEnvelope("termux_run_command", info)
        ).jsonObject
        assertEquals("tool_unavailable", obj.getValue("error").jsonPrimitive.content)
        assertEquals("termux_run_command", obj.getValue("tool").jsonPrimitive.content)
        assertEquals("gated off", obj.getValue("detail").jsonPrimitive.content)
        assertEquals("turn it back on", obj.getValue("recovery").jsonPrimitive.content)
    }

    @Test
    fun `envelope without cause is still explicit`() {
        val obj = json.parseToJsonElement(
            ToolAvailability.buildUnavailableEnvelope("mystery_tool", null)
        ).jsonObject
        assertEquals("tool_unavailable", obj.getValue("error").jsonPrimitive.content)
        assertEquals("mystery_tool", obj.getValue("tool").jsonPrimitive.content)
        assertTrue(obj.getValue("detail").jsonPrimitive.content.contains("not in the active tool set"))
        assertTrue(obj.getValue("recovery").jsonPrimitive.content.contains("Do not retry"))
    }

    // ---- tool-set change notice ----

    @Test
    fun `notice is null on the first generation of a conversation`() {
        assertNull(ToolAvailability.toolSetChangeNotice(null, setOf("a", "b")))
    }

    @Test
    fun `notice is null when the tool set is unchanged`() {
        assertNull(
            ToolAvailability.toolSetChangeNotice(setOf("a", "b"), setOf("b", "a"))
        )
    }

    @Test
    fun `notice lists removed and added tools`() {
        val notice = ToolAvailability.toolSetChangeNotice(
            previous = setOf("clipboard", "termux_run_command"),
            current = setOf("clipboard", "share"),
        )
        requireNotNull(notice)
        assertTrue(notice.contains("Removed: termux_run_command"))
        assertTrue(notice.contains("Added: share"))
        assertTrue(notice.contains("tool_unavailable"))
        assertTrue(notice.contains("Settings > Termux"))
    }

    @Test
    fun `notice caps long name lists`() {
        val previous = (1..20).map { "tool_$it" }.toSet()
        val notice = ToolAvailability.toolSetChangeNotice(previous, emptySet())
        requireNotNull(notice)
        assertTrue(notice.contains("(+12 more)"))
        assertTrue(!notice.contains("tool_20"))
    }

    // ---- tombstone tools ----

    @Test
    fun `tombstone names are previous minus current and never fire on first generation`() {
        assertEquals(emptySet<String>(), ToolAvailability.tombstoneNames(null, setOf("a")))
        assertEquals(emptySet<String>(), ToolAvailability.tombstoneNames(setOf("a"), setOf("a")))
        assertEquals(setOf("b"), ToolAvailability.tombstoneNames(setOf("a", "b"), setOf("a")))
        // Never-advertised names (only in current, or in neither) are never tombstoned.
        assertEquals(emptySet<String>(), ToolAvailability.tombstoneNames(setOf("a"), setOf("a", "new")))
    }

    @Test
    fun `tombstone for termux gate keeps the name and screams disabled with the cause`() {
        val info = ToolAvailability.inspect(
            "termux_run_command",
            termuxIntegrationEnabled = false,
            mcpServers = emptyList(),
        )
        val tool = ToolAvailability.buildTombstoneTool("termux_run_command", info)
        assertEquals("termux_run_command", tool.name)
        assertTrue(tool.description.startsWith("CURRENTLY DISABLED"))
        assertTrue(tool.description.contains("Termux integration is switched off"))
        assertTrue(tool.description.contains("DO NOT CALL"))
        assertTrue(tool.description.contains("tell them how to re-enable it"))
    }

    @Test
    fun `tombstone without a known cause uses the generic unavailable text`() {
        val tool = ToolAvailability.buildTombstoneTool("mystery_tool", null)
        assertEquals("mystery_tool", tool.name)
        assertTrue(tool.description.startsWith("CURRENTLY UNAVAILABLE"))
        assertTrue(tool.description.contains("DO NOT CALL"))
        assertTrue(tool.description.contains("inform the user"))
    }

    @Test
    fun `tombstone never needs approval and has a minimal object schema`() {
        val tool = ToolAvailability.buildTombstoneTool("termux_run_command", null)
        assertFalse(tool.needsApproval(JsonNull))
        val schema = tool.parameters()
        assertTrue(schema is InputSchema.Obj)
        assertTrue((schema as InputSchema.Obj).properties.isEmpty())
    }

    @Test
    fun `tombstone execute returns the tool_unavailable envelope through normal dispatch`() = runBlocking {
        val info = ToolAvailability.inspect(
            "termux_run_command",
            termuxIntegrationEnabled = false,
            mcpServers = emptyList(),
        )
        val output = ToolAvailability.buildTombstoneTool("termux_run_command", info)
            .execute(JsonNull)
        assertEquals(1, output.size)
        val text = (output.single() as UIMessagePart.Text).text
        val obj = json.parseToJsonElement(text).jsonObject
        assertEquals("tool_unavailable", obj.getValue("error").jsonPrimitive.content)
        assertEquals("termux_run_command", obj.getValue("tool").jsonPrimitive.content)
        assertTrue(obj.getValue("detail").jsonPrimitive.content.contains("Termux integration is switched off"))
        assertTrue(obj.getValue("recovery").jsonPrimitive.content.contains("Settings > Termux"))
    }

    @Test
    fun `baseline excludes tombstones so a returning tool sheds its tombstone`() {
        // Simulates ChatService's per-generation rule: the tombstone baseline is
        // CUMULATIVE (ever-advertised names), so a still-absent tool keeps its tombstone
        // and a returning tool sheds it because it is back in the current set.
        var baseline: Set<String>? = null
        fun generation(real: Set<String>): Set<String> {
            val tombstones = ToolAvailability.tombstoneNames(baseline, real)
            baseline = baseline.orEmpty() + real // accumulate ever-advertised names
            return tombstones
        }
        assertEquals(emptySet<String>(), generation(setOf("a", "termux_run_command"))) // gen 1: no baseline
        assertEquals(setOf("termux_run_command"), generation(setOf("a")))              // gate off → tombstone
        assertEquals(emptySet<String>(), generation(setOf("a", "termux_run_command"))) // gate back on → real def again
        assertEquals(setOf("termux_run_command"), generation(setOf("a")))              // flip-flop still works
        assertEquals(setOf("termux_run_command"), generation(setOf("a")))              // NOT tombstoned-forever growth
    }
}
