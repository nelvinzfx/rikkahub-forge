package me.rerere.rikkahub.ui.pages.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CancelSquare
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Experimental code editor (beta), gated behind Settings > Code Editor (Beta).
 * Commit 3 scope: sora editor surface with tabs, dirty tracking, SAF save and
 * TextMate syntax highlighting. The tree stays one back press away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPage(vm: EditorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootName by vm.rootName.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val loadingDirs by vm.loadingDirs.collectAsStateWithLifecycle()
    val openTabs by vm.openTabs.collectAsStateWithLifecycle()
    val activeTabUri by vm.activeTabUri.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val showTree by vm.showTree.collectAsStateWithLifecycle()

    val activeTab = openTabs.firstOrNull { it.uri == activeTabUri }
    var pendingClose by remember { mutableStateOf<EditorTab?>(null) }

    LaunchedEffect(Unit) { vm.initFromSettings() }
    LaunchedEffect(settings.codeEditorShowHidden) { vm.onShowHiddenChanged() }

    // saved notice auto-dismiss
    LaunchedEffect(notice) {
        if (notice is EditorNotice.Saved) {
            delay(2500)
            vm.dismissNotice()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onTreePicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val base = activeTab?.name ?: rootName ?: stringResource(R.string.code_editor_title)
                    val suffix = if (activeTab?.readOnly == true) {
                        " (" + stringResource(R.string.code_editor_read_only) + ")"
                    } else ""
                    Text(base + suffix)
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (activeTab != null && !showTree) {
                        if (!activeTab.readOnly) {
                            IconButton(onClick = { vm.saveActive() }) {
                                Icon(HugeIcons.Download01, stringResource(R.string.code_editor_save))
                            }
                        }
                        IconButton(onClick = { vm.setShowTree(true) }) {
                            Icon(HugeIcons.Folder01, stringResource(R.string.code_editor_files))
                        }
                        IconButton(onClick = {
                            if (activeTab.dirty) pendingClose = activeTab else vm.closeActiveTab()
                        }) {
                            Icon(HugeIcons.CancelSquare, stringResource(R.string.code_editor_close_file))
                        }
                    } else {
                        if (openTabs.isNotEmpty()) {
                            IconButton(onClick = { vm.setShowTree(false) }) {
                                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.code_editor_back_to_editor))
                            }
                        }
                        if (rootName != null) {
                            IconButton(onClick = { vm.refresh() }) {
                                Icon(HugeIcons.Refresh01, stringResource(R.string.code_editor_refresh))
                            }
                        }
                        IconButton(onClick = { picker.launch(null) }) {
                            Icon(HugeIcons.Folder01, stringResource(R.string.code_editor_open_folder))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeTab != null && !showTree) {
                Column(Modifier.fillMaxSize()) {
                    EditorTabsBar(
                        tabs = openTabs,
                        activeUri = activeTabUri,
                        onSelect = vm::activateTab,
                        onClose = { tab ->
                            if (tab.dirty) pendingClose = tab else vm.closeTab(tab.uri)
                        },
                    )
                    HorizontalDivider()
                    EditorSurface(
                        tab = activeTab,
                        darkTheme = isSystemInDarkTheme(),
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (rootName == null) {
                EmptyState(onPick = { picker.launch(null) })
            } else {
                FileTreeList(
                    nodes = nodes,
                    expanded = expanded,
                    loadingDirs = loadingDirs,
                    onRowClick = vm::openFile,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (notice !is EditorNotice.None) {
                NoticeBar(
                    notice = notice,
                    onDismiss = vm::dismissNotice,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }
    }

    pendingClose?.let { tab ->
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text(stringResource(R.string.code_editor_discard_title)) },
            text = { Text(stringResource(R.string.code_editor_discard_msg, tab.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeTab(tab.uri)
                    pendingClose = null
                }) {
                    Text(stringResource(R.string.code_editor_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClose = null }) {
                    Text(stringResource(R.string.code_editor_cancel))
                }
            },
        )
    }
}

@Composable
private fun NoticeBar(
    notice: EditorNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (notice) {
        is EditorNotice.TooLarge -> stringResource(R.string.code_editor_notice_too_large, notice.name)
        is EditorNotice.Binary -> stringResource(R.string.code_editor_notice_binary, notice.name)
        is EditorNotice.Error -> stringResource(R.string.code_editor_notice_error, notice.name)
        is EditorNotice.Saved -> stringResource(R.string.code_editor_notice_saved, notice.name)
        is EditorNotice.SaveFailed -> stringResource(R.string.code_editor_save_failed, notice.name)
        EditorNotice.None -> ""
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.code_editor_ok))
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.code_editor_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPick) {
            Text(stringResource(R.string.code_editor_choose_folder))
        }
    }
}
