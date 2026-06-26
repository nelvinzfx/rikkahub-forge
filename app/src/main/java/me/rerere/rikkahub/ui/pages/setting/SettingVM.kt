package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import kotlin.uuid.Uuid

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * Fully delete an MCP server: close the live client connection, remove it from
     * the global mcpServers list, and drop stale references from every assistant's
     * mcpServers set. Mirrors the cleanup the LLM-facing mcp_delete tool does so the
     * UI path doesn't leave orphan connections or stale UUIDs behind.
     */
    fun deleteMcpServer(serverId: Uuid) {
        viewModelScope.launch {
            val config = settingsStore.settingsFlow.value.mcpServers
                .firstOrNull { it.id == serverId } ?: return@launch
            mcpManager.removeClient(config)
            settingsStore.update { s ->
                s.copy(
                    mcpServers = s.mcpServers.filter { it.id != serverId },
                    assistants = s.assistants.map { a ->
                        a.copy(mcpServers = a.mcpServers.filter { it != serverId }.toSet())
                    },
                )
            }
        }
    }
}
