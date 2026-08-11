package me.rerere.rikkahub.ui.pages.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.CancelSquare
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Experimental code editor (beta), gated behind Settings > Code Editor (Beta).
 * The page renders the editor directly; the file tree lives in a right-side
 * modal drawer (vscode/MT Manager style) so opening files never swaps views.
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
    val editTick by vm.editTick.collectAsStateWithLifecycle()
    val externalChangeUri by vm.externalChangeUri.collectAsStateWithLifecycle()

    val activeTab = openTabs.firstOrNull { it.uri == activeTabUri }
    var pendingClose by remember { mutableStateOf<EditorTab?>(null) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    val wordWrap = settings.codeEditorWordWrap

    // search state
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchCurrent by remember { mutableIntStateOf(0) }
    var matchCount by remember { mutableIntStateOf(0) }

    // go to line state
    var gotoOpen by remember { mutableStateOf(false) }
    var gotoInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerAutoOpened by remember { mutableStateOf(false) }

    val canUndo = editTick.let { editor?.canUndo() == true }
    val canRedo = editTick.let { editor?.canRedo() == true }

    fun refreshMatchInfo() {
        val s = editor?.searcher
        if (s != null && s.hasQuery()) {
            val idx = s.currentMatchedPositionIndex
            matchCurrent = if (idx >= 0) idx + 1 else 0
            matchCount = s.matchedPositionCount
        } else {
            matchCurrent = 0
            matchCount = 0
        }
    }

    LaunchedEffect(Unit) { vm.initFromSettings() }
    LaunchedEffect(settings.codeEditorShowHidden) { vm.onShowHiddenChanged() }

    // watch open files for external edits while visible; parking the page
    // (navigation / background) flushes drafts + session so nothing is lost
    LifecycleResumeEffect(Unit) {
        vm.startPolling()
        onPauseOrDispose {
            vm.stopPolling()
            vm.flushNow()
        }
    }

    // auto-open the files drawer once when a root exists and nothing is open
    LaunchedEffect(rootName, activeTabUri) {
        if (!drawerAutoOpened && rootName != null && activeTabUri == null) {
            drawerAutoOpened = true
            drawerState.open()
        }
    }

    // saved notice auto-dismiss
    LaunchedEffect(notice) {
        if (notice is EditorNotice.Saved) {
            delay(2500)
            vm.dismissNotice()
        }
    }

    // close search when switching tabs
    LaunchedEffect(activeTabUri) {
        searchOpen = false
        searchQuery = ""
        matchCurrent = 0
        matchCount = 0
    }

    // debounced search
    LaunchedEffect(searchQuery, searchOpen) {
        if (!searchOpen) return@LaunchedEffect
        delay(300)
        val s = editor?.searcher ?: return@LaunchedEffect
        if (searchQuery.isBlank()) {
            s.stopSearch()
        } else {
            s.search(searchQuery, EditorSearcher.SearchOptions(true, false))
        }
        delay(200)
        refreshMatchInfo()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onTreePicked(uri) }

    BackHandler(drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // swipe gestures fight the editor's own scrolling (vertical scrolls with
            // horizontal drift opened the drawer; long-line horizontal scroll lost to it)
            gesturesEnabled = false,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                        val iconFont = rememberFileIconFont()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = FileIcons.FOLDER_ROOT,
                                fontFamily = iconFont,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = rootName ?: stringResource(R.string.code_editor_files),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (rootName != null) {
                                IconButton(onClick = { vm.refresh() }) {
                                    Icon(
                                        HugeIcons.Refresh01,
                                        stringResource(R.string.code_editor_refresh),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            IconButton(onClick = { picker.launch(null) }) {
                                Icon(
                                    HugeIcons.Folder01,
                                    stringResource(R.string.code_editor_change_folder),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        HorizontalDivider()
                        if (rootName == null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.code_editor_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { picker.launch(null) }) {
                                    Text(stringResource(R.string.code_editor_choose_folder))
                                }
                            }
                        } else {
                            FileTreeList(
                                nodes = nodes,
                                expanded = expanded,
                                loadingDirs = loadingDirs,
                                onRowClick = { node ->
                                    vm.openFile(node)
                                    if (!node.isDir) {
                                        scope.launch { drawerState.close() }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                val base = activeTab?.name ?: rootName
                                ?: stringResource(R.string.code_editor_title)
                                val suffix = if (activeTab?.readOnly == true) {
                                    " (" + stringResource(R.string.code_editor_read_only) + ")"
                                } else ""
                                Text(base + suffix)
                            },
                            navigationIcon = { BackButton() },
                            actions = {
                                if (activeTab != null) {
                                    if (!activeTab.readOnly) {
                                        IconButton(onClick = { vm.saveActive() }) {
                                            Icon(HugeIcons.Download01, stringResource(R.string.code_editor_save))
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { showOverflow = true }) {
                                            Icon(HugeIcons.MoreVertical, stringResource(R.string.code_editor_menu))
                                        }
                                        DropdownMenu(
                                            expanded = showOverflow,
                                            onDismissRequest = { showOverflow = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.code_editor_search)) },
                                                onClick = {
                                                    showOverflow = false
                                                    searchOpen = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.code_editor_goto_line)) },
                                                onClick = {
                                                    showOverflow = false
                                                    gotoInput = ""
                                                    gotoOpen = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.code_editor_undo)) },
                                                enabled = canUndo && !activeTab.readOnly,
                                                onClick = {
                                                    showOverflow = false
                                                    editor?.undo()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.code_editor_redo)) },
                                                enabled = canRedo && !activeTab.readOnly,
                                                onClick = {
                                                    showOverflow = false
                                                    editor?.redo()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(R.string.code_editor_word_wrap) +
                                                                if (wordWrap) " ✓" else ""
                                                    )
                                                },
                                                onClick = {
                                                    showOverflow = false
                                                    vm.toggleWordWrap()
                                                }
                                            )
                                        }
                                    }
                                    IconButton(onClick = {
                                        if (activeTab.dirty) pendingClose = activeTab else vm.closeActiveTab()
                                    }) {
                                        Icon(HugeIcons.CancelSquare, stringResource(R.string.code_editor_close_file))
                                    }
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(HugeIcons.Folder01, stringResource(R.string.code_editor_files))
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
                        if (activeTab != null) {
                            Column(Modifier.fillMaxSize()) {
                                EditorTabsBar(
                                    tabs = openTabs,
                                    activeUri = activeTabUri,
                                    onSelect = vm::activateTab,
                                    onClose = { tab ->
                                        if (tab.dirty) pendingClose = tab else vm.closeTab(tab.uri)
                                    },
                                )
                                if (searchOpen) {
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            placeholder = {
                                                Text(
                                                    stringResource(R.string.code_editor_search_hint),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                        )
                                        Text(
                                            text = "$matchCurrent/$matchCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(onClick = {
                                            editor?.searcher?.gotoPrevious()
                                            refreshMatchInfo()
                                        }) {
                                            Icon(
                                                HugeIcons.ArrowUp01,
                                                stringResource(R.string.code_editor_search_prev),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(onClick = {
                                            editor?.searcher?.gotoNext()
                                            refreshMatchInfo()
                                        }) {
                                            Icon(
                                                HugeIcons.ArrowDown01,
                                                stringResource(R.string.code_editor_search_next),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(onClick = {
                                            editor?.searcher?.stopSearch()
                                            searchOpen = false
                                            searchQuery = ""
                                            matchCurrent = 0
                                            matchCount = 0
                                        }) {
                                            Icon(
                                                HugeIcons.CancelSquare,
                                                stringResource(R.string.code_editor_search_close),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                                EditorSurface(
                                    tab = activeTab,
                                    darkTheme = isSystemInDarkTheme(),
                                    wordWrap = wordWrap,
                                    vm = vm,
                                    onEditorChange = { editor = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
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
                                    text = stringResource(R.string.code_editor_no_tab),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { scope.launch { drawerState.open() } }) {
                                    Text(stringResource(R.string.code_editor_browse_files))
                                }
                            }
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
            }
        }
    }

    // external-change conflict: only renders when the conflicted tab is the
    // active one; background tabs hold their flag until switched to
    externalChangeUri?.takeIf { it == activeTabUri }?.let { uri ->
        openTabs.firstOrNull { it.uri == uri }?.let { tab ->
            AlertDialog(
                onDismissRequest = { vm.keepLocalContent() },
                title = { Text(stringResource(R.string.code_editor_external_change_title)) },
                text = {
                    Text(
                        stringResource(
                            if (tab.dirty) R.string.code_editor_external_change_msg_dirty
                            else R.string.code_editor_external_change_msg,
                            tab.name,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.reloadActiveFromDisk() }) {
                        Text(stringResource(R.string.code_editor_reload))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.keepLocalContent() }) {
                        Text(stringResource(R.string.code_editor_cancel))
                    }
                },
            )
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

    if (gotoOpen) {
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text(stringResource(R.string.code_editor_goto_line)) },
            text = {
                OutlinedTextField(
                    value = gotoInput,
                    onValueChange = { gotoInput = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text(stringResource(R.string.code_editor_goto_line_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val line = gotoInput.toIntOrNull()
                    val ed = editor
                    if (line != null && line > 0 && ed != null) {
                        // sora's setSelection does charAt(line, ...) unchecked; an
                        // out-of-range line crashes the app, so clamp to lineCount
                        val target = line.coerceAtMost(ed.lineCount) - 1
                        ed.setSelection(target, 0)
                    }
                    gotoOpen = false
                }) {
                    Text(stringResource(R.string.code_editor_goto))
                }
            },
            dismissButton = {
                TextButton(onClick = { gotoOpen = false }) {
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
        is EditorNotice.FileMissing -> stringResource(R.string.code_editor_notice_missing, notice.name)
        is EditorNotice.RestoreDropped -> stringResource(
            R.string.code_editor_restore_dropped,
            notice.names.joinToString(", "),
        )
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
