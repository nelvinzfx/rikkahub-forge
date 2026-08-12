package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.InputSchema
import kotlin.uuid.Uuid

/** Slug embedded in model-facing MCP tool names; also used by tool-unavailability
 *  diagnostics to map an unresolved `mcp__*` call back to its server. */
internal fun mcpServerSlug(serverId: Uuid): String = serverId.toString().take(8).replace("-", "")

internal fun modelFacingMcpToolName(serverId: Uuid, serverName: String, toolName: String): String {
    return "mcp__${mcpServerSlug(serverId)}_${serverName}__${toolName}"
}

internal fun availableMcpToolsForAssistant(
    servers: List<McpServerConfig>,
    enabledServerIds: Set<Uuid>,
): List<Triple<Uuid, String, McpTool>> = servers
    .filter { it.commonOptions.enable && it.id in enabledServerIds }
    .flatMap { server ->
        server.commonOptions.tools
            .filter { it.enable }
            .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
    }

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList()
)

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    val needsApproval: Boolean = false
)

@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}
