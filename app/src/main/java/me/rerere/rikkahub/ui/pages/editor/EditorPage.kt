package me.rerere.rikkahub.ui.pages.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CancelSquare
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Experimental code editor (beta), gated behind Settings > Code Editor (Beta).
 * Commit 2 scope: SAF folder picker + nvim-tree style file tree + read-only
 * preview. The sora editor surface replaces the preview in commit 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPage(vm: EditorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootName by vm.rootName.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val loadingDirs by vm.loadingDirs.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.initFromSettings() }
    LaunchedEffect(settings.codeEditorShowHidden) { vm.onShowHiddenChanged() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onTreePicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rootName ?: stringResource(R.string.code_editor_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (rootName != null) {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(HugeIcons.Refresh01, stringResource(R.string.code_editor_refresh))
                        }
                    }
                    IconButton(onClick = { picker.launch(null) }) {
                        Icon(HugeIcons.Folder01, stringResource(R.string.code_editor_open_folder))
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
            when (val p = preview) {
                is FilePreview.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is FilePreview.Ready -> PreviewContent(p.file, onClose = vm::closePreview)

                is FilePreview.TooLarge -> PreviewMessage(
                    message = stringResource(R.string.code_editor_file_too_large, p.name),
                    onClose = vm::closePreview,
                )

                is FilePreview.Binary -> PreviewMessage(
                    message = stringResource(R.string.code_editor_binary_file, p.name),
                    onClose = vm::closePreview,
                )

                is FilePreview.Error -> PreviewMessage(
                    message = stringResource(R.string.code_editor_read_error, p.name),
                    onClose = vm::closePreview,
                )

                FilePreview.None -> {
                    if (rootName == null) {
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
                }
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

@Composable
private fun PreviewContent(file: OpenFile, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (file.truncated) {
                Text(
                    text = stringResource(R.string.code_editor_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(HugeIcons.CancelSquare, stringResource(R.string.code_editor_close_file))
            }
        }
        HorizontalDivider()
        SelectionContainer {
            Text(
                text = file.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun PreviewMessage(message: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.code_editor_close_file))
        }
    }
}
