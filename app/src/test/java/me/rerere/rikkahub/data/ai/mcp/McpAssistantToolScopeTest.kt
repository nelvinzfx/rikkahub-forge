package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpAssistantToolScopeTest {
    private val serverA = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val serverB = Uuid.parse("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `assistant MCP scope includes only its enabled servers and tools`() {
        val servers = listOf(
            McpServerConfig.SseTransportServer(
                id = serverA,
                commonOptions = McpCommonOptions(
                    enable = true,
                    name = "Alpha",
                    tools = listOf(
                        McpTool(enable = true, name = "read"),
                        McpTool(enable = false, name = "disabled"),
                    ),
                ),
            ),
            McpServerConfig.SseTransportServer(
                id = serverB,
                commonOptions = McpCommonOptions(
                    enable = true,
                    name = "Beta",
                    tools = listOf(McpTool(enable = true, name = "other")),
                ),
            ),
        )

        val scoped = availableMcpToolsForAssistant(servers, setOf(serverA))

        assertEquals(listOf("read"), scoped.map { it.third.name })
        assertTrue(scoped.all { it.first == serverA })
        assertFalse(scoped.any { it.first == serverB })
    }

    @Test
    fun `model facing MCP name remains stable`() {
        assertEquals(
            "mcp__00000000_Alpha__read",
            modelFacingMcpToolName(serverA, "Alpha", "read"),
        )
    }
}
