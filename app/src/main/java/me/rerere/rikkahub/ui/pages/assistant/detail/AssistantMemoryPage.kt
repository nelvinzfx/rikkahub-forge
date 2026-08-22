package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.AssistantMemoryImportResult
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.io.FileOutputStream
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            memories = memories,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            onExportMemories = { scope -> vm.buildMemoryExport(scope) },
            onImportMemories = { json -> vm.importMemories(json) }
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onExportMemories: suspend (String) -> Pair<String, String>,
    onImportMemories: suspend (String) -> AssistantMemoryImportResult,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var isTransferBusy by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportScope by remember { mutableStateOf("all") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            isTransferBusy = true
            scope.launch {
                try {
                    val result = runCatching {
                        val text = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                        } ?: error(context.getString(R.string.memory_import_read_failed))
                        onImportMemories(text)
                    }
                    when (val outcome = result.getOrNull()) {
                        is AssistantMemoryImportResult.Imported -> toaster.show(
                            message = context.getString(R.string.memory_import_done, outcome.importedCount, outcome.skippedCount)
                        )
                        is AssistantMemoryImportResult.Failed -> toaster.show(
                            message = context.getString(R.string.memory_import_invalid_format),
                            type = ToastType.Error
                        )
                        null -> toaster.show(
                            message = result.exceptionOrNull()?.message
                                ?: context.getString(R.string.memory_import_failed),
                            type = ToastType.Error
                        )
                    }
                } finally {
                    isTransferBusy = false
                }
            }
        }
    }

    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var filter by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    val visibleMemories by remember(memories, filter, search) {
        derivedStateOf {
            memories.filter { memory ->
                val modeMatches = filter == "all" || memory.mode == filter
                val textMatches = search.isBlank() || memory.title.contains(search, true) ||
                    memory.content.contains(search, true) || memory.tags.any { it.contains(search, true) }
                modeMatches && textMatches && !memory.archived
            }
        }
    }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = memory.title,
                        onValueChange = { update(memory.copy(title = it)) },
                        label = { Text(stringResource(R.string.memory_bank_title)) },
                        singleLine = true,
                    )
                    TextField(
                        value = memory.content,
                        onValueChange = { update(memory.copy(content = it)) },
                        label = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
                        minLines = 2, maxLines = 8,
                    )
                    TextField(
                        value = memory.tags.joinToString(", "),
                        onValueChange = { value ->
                            update(memory.copy(tags = value.split(',').map(String::trim).filter(String::isNotBlank)))
                        },
                        label = { Text(stringResource(R.string.memory_bank_tags)) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = memory.mode == "core",
                            onClick = { update(memory.copy(mode = "core")) },
                            label = { Text(stringResource(R.string.memory_bank_core)) },
                        )
                        FilterChip(
                            selected = memory.mode == "bank",
                            onClick = { update(memory.copy(mode = "bank")) },
                            label = { Text(stringResource(R.string.memory_bank_bank)) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_time_reminder_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
        }

      OutlinedTextField(
          value = assistant.memoryCoreTokenBudget.toString(),
          onValueChange = { value ->
              value.toIntOrNull()?.let { budget ->
                  onUpdateAssistant(assistant.copy(memoryCoreTokenBudget = budget.coerceIn(0, 20000)))
              }
          },
          label = { Text(stringResource(R.string.memory_bank_core_budget)) },
          supportingText = { Text(stringResource(R.string.memory_bank_core_budget_desc)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("all" to R.string.memory_bank_all, "core" to R.string.memory_bank_core, "bank" to R.string.memory_bank_bank).forEach { (mode, label) ->
              FilterChip(selected = filter == mode, onClick = { filter = mode }, label = { Text(stringResource(label)) })
          }
      }
      OutlinedTextField(
          value = search, onValueChange = { search = it },
          label = { Text(stringResource(R.string.memory_bank_search)) },
          singleLine = true, modifier = Modifier.fillMaxWidth(),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
              onClick = { showExportDialog = true },
              enabled = !isTransferBusy && memories.isNotEmpty()
          ) {
              Text(stringResource(R.string.memory_export))
          }
          OutlinedButton(
              onClick = { importLauncher.launch(arrayOf("application/json")) },
              enabled = !isTransferBusy
          ) {
              Text(stringResource(R.string.memory_import))
          }
      }

      Box(
          modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp)
      ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory(0, ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = null
                )
            }
        }

        visibleMemories.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Text(stringResource(R.string.memory_export_scope_title))
            },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "all" to R.string.memory_bank_all,
                        "core" to R.string.memory_bank_core,
                        "bank" to R.string.memory_bank_bank,
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = exportScope == mode,
                            onClick = { exportScope = mode },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        isTransferBusy = true
                        scope.launch {
                            try {
                                runCatching { onExportMemories(exportScope) }
                                    .onSuccess { (fileName, json) ->
                                        val file = writeMemoryExportFile(context, fileName, json)
                                        shareMemoryExportFile(context, file)
                                        toaster.show(
                                            message = context.getString(R.string.memory_export_done, file.absolutePath)
                                        )
                                    }
                                    .onFailure { exception ->
                                        toaster.show(
                                            message = exception.message
                                                ?: context.getString(R.string.memory_export_failed),
                                            type = ToastType.Error
                                        )
                                    }
                            } finally {
                                isTransferBusy = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/** Write the export document into the app temp folder; returns the file for sharing. */
private suspend fun writeMemoryExportFile(context: Context, fileName: String, json: String): File =
    withContext(Dispatchers.IO) {
        val dir = context.appTempFolder
        val file = dir.resolve(fileName)
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
        FileOutputStream(file).use {
            it.write(json.toByteArray())
        }
        file
    }

/** Offer the exported memory file via the share sheet (same plumbing as chat raw export). */
private fun shareMemoryExportFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = buildString {
                        append("#${memory.id} · ${memory.mode.uppercase()}")
                        if (memory.title.isNotBlank()) append(" · ${memory.title}")
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,

                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
