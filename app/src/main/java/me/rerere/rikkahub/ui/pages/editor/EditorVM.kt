package me.rerere.rikkahub.ui.pages.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rosemoe.sora.text.Content
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val MAX_OPEN_BYTES = 5 * 1024 * 1024
private const val READ_ONLY_THRESHOLD = 2L * 1024 * 1024
private const val POLL_INTERVAL_MS = 5_000L
private const val EDIT_DEBOUNCE_MS = 2_000L
private const val SESSION_PERSIST_DEBOUNCE_MS = 500L

data class FileNode(
    val uri: String,
    val name: String,
    val isDir: Boolean,
    val depth: Int,
)

private data class ChildEntry(
    val uri: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
)

data class EditorTab(
    val uri: String,
    val name: String,
    val content: Content,
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    /** last known on-disk state, refreshed on open/save/reload; polled against */
    val diskSnapshot: DiskSnapshot = DiskSnapshot(0L, -1L),
    /** bumped on reload-from-disk so EditorSurface's swap effect refires for the same uri */
    val contentVersion: Int = 0,
    /** file vanished; polling and auto-save skip this tab */
    val missing: Boolean = false,
)

sealed interface EditorNotice {
    data object None : EditorNotice
    data class TooLarge(val name: String) : EditorNotice
    data class Binary(val name: String) : EditorNotice
    data class Error(val name: String) : EditorNotice
    data class Saved(val name: String) : EditorNotice
    data class SaveFailed(val name: String) : EditorNotice
    data class FileMissing(val name: String) : EditorNotice
    data class RestoreDropped(val names: List<String>) : EditorNotice
}

/**
 * State owner for the experimental code editor. Holds the SAF tree grant, the
 * per-directory children cache, expansion state, and the open-tab set. Each tab
 * owns a sora [Content] document; saving serializes the active tab's Content
 * back through SAF.
 *
 * Reliability layer on top of that:
 * - external changes are detected by polling [DocumentFile] metadata (SAF URIs
 *   cannot be watched); [ioMutex] serializes our own writes against the poller
 *   so a save never trips the conflict dialog
 * - the session (tabs + dirty drafts) persists to [EditorSessionStore], so tabs
 *   and unsaved edits survive navigation and process restarts without ever
 *   touching the real files
 * - auto-save (settings.codeEditorAutoSave) debounces edits and refuses to
 *   write over an external change, deferring to the conflict flow instead
 */
class EditorVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val sessionStore: EditorSessionStore,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    private val _rootName = MutableStateFlow<String?>(null)
    val rootName = _rootName.asStateFlow()

    private val _nodes = MutableStateFlow<List<FileNode>>(emptyList())
    val nodes = _nodes.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded = _expanded.asStateFlow()

    private val _loadingDirs = MutableStateFlow<Set<String>>(emptySet())
    val loadingDirs = _loadingDirs.asStateFlow()

    private val _openTabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val openTabs = _openTabs.asStateFlow()

    private val _activeTabUri = MutableStateFlow<String?>(null)
    val activeTabUri = _activeTabUri.asStateFlow()

    private val _notice = MutableStateFlow<EditorNotice>(EditorNotice.None)
    val notice = _notice.asStateFlow()

    /** bumped on every content change so the page can re-read canUndo/canRedo */
    private val _editTick = MutableStateFlow(0)
    val editTick = _editTick.asStateFlow()

    /**
     * uri of the tab whose on-disk content diverged, awaiting the user's
     * Reload/Cancel. the page only renders the dialog when this is the active
     * tab, so background tabs never steal a dialog.
     */
    private val _externalChangeUri = MutableStateFlow<String?>(null)
    val externalChangeUri = _externalChangeUri.asStateFlow()

    /** set while the view swaps documents on tab switch, so the swap is not counted as an edit */
    val swapGuard = AtomicBoolean(false)

    private val childrenCache = mutableMapOf<String, List<ChildEntry>>()
    private var rootUriString: String? = null
    private var initialized = false

    /** serializes save / poll / draft writes so they never observe each other mid-flight */
    private val ioMutex = Mutex()
    private var pollJob: Job? = null
    private var editDebounceJob: Job? = null
    private var sessionPersistJob: Job? = null
    private val draftedUris = mutableSetOf<String>()

    fun initFromSettings() {
        if (initialized) return
        initialized = true
        val uri = settings.value.codeEditorTreeUri ?: return
        rootUriString = uri
        // older grants may have been persisted read-only; upgrading to rw is
        // best-effort and simply no-ops when the provider refuses
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            _rootName.value = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
            }
            loadChildren(uri, force = true)
            restoreSession()
        }
    }

    fun onTreePicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        rootUriString = uri.toString()
        childrenCache.clear()
        _expanded.value = emptySet()
        _openTabs.value = emptyList()
        _activeTabUri.value = null
        _notice.value = EditorNotice.None
        _externalChangeUri.value = null
        draftedUris.clear()
        _rootName.value = DocumentFile.fromTreeUri(context, uri)?.name
        viewModelScope.launch {
            val current = settings.value
            settingsStore.update(current.copy(codeEditorTreeUri = uri.toString()))
            sessionStore.clearAll()
            loadChildren(uri.toString(), force = true)
        }
    }

    fun toggleDir(node: FileNode) {
        if (!node.isDir) return
        val uri = node.uri
        if (uri in _expanded.value) {
            _expanded.value = _expanded.value - uri
            rebuildNodes()
        } else {
            _expanded.value = _expanded.value + uri
            if (childrenCache.containsKey(uri)) {
                rebuildNodes()
            } else {
                viewModelScope.launch { loadChildren(uri) }
            }
        }
    }

    fun refresh() {
        val root = rootUriString ?: return
        viewModelScope.launch {
            val expandedSnapshot = _expanded.value
            childrenCache.clear()
            loadChildren(root, force = true)
            for (dir in expandedSnapshot) {
                if (dir != root) loadChildren(dir)
            }
        }
    }

    fun openFile(node: FileNode) {
        if (node.isDir) {
            toggleDir(node)
            return
        }
        if (_openTabs.value.any { it.uri == node.uri }) {
            _activeTabUri.value = node.uri
            return
        }
        viewModelScope.launch {
            when (val result = readUriContents(node.uri)) {
                OpenResult.TooLarge -> _notice.value = EditorNotice.TooLarge(node.name)
                OpenResult.Binary -> _notice.value = EditorNotice.Binary(node.name)
                OpenResult.Error -> _notice.value = EditorNotice.Error(node.name)
                is OpenResult.Ok -> {
                    val tab = EditorTab(
                        uri = node.uri,
                        name = node.name,
                        content = Content(result.text),
                        readOnly = result.readOnly,
                        diskSnapshot = statFile(node.uri)
                            ?: DiskSnapshot(0L, result.text.toByteArray(Charsets.UTF_8).size.toLong()),
                    )
                    _openTabs.value = _openTabs.value + tab
                    _activeTabUri.value = tab.uri
                    persistSessionDebounced()
                }
            }
        }
    }

    fun activateTab(uri: String) {
        if (_openTabs.value.none { it.uri == uri }) return
        val previous = _activeTabUri.value
        if (previous == uri) return
        _activeTabUri.value = uri
        // a pending debounce for the tab we just left must not silently die with
        // the switch; flush it now so its draft/auto-save runs immediately
        if (previous != null && editDebounceJob?.isActive == true) {
            editDebounceJob?.cancel()
            viewModelScope.launch { flushEdits(previous) }
        }
        persistSessionDebounced()
    }

    fun closeTab(uri: String) {
        val tabs = _openTabs.value
        val idx = tabs.indexOfFirst { it.uri == uri }
        if (idx == -1) return
        val newTabs = tabs.toMutableList().apply { removeAt(idx) }
        _openTabs.value = newTabs
        if (_activeTabUri.value == uri) {
            val next = newTabs.getOrNull(idx) ?: newTabs.getOrNull(idx - 1)
            _activeTabUri.value = next?.uri
        }
        if (_externalChangeUri.value == uri) _externalChangeUri.value = null
        viewModelScope.launch {
            sessionStore.deleteDraft(uri)
            draftedUris.remove(uri)
        }
        persistSessionDebounced()
    }

    fun closeActiveTab() {
        _activeTabUri.value?.let { closeTab(it) }
    }

    fun onContentChanged() {
        if (swapGuard.get()) return
        _editTick.value += 1
        val active = _activeTabUri.value ?: return
        _openTabs.value = _openTabs.value.map {
            if (it.uri == active && !it.dirty) it.copy(dirty = true) else it
        }
        editDebounceJob?.cancel()
        editDebounceJob = viewModelScope.launch {
            delay(EDIT_DEBOUNCE_MS)
            flushEdits(active)
        }
    }

    fun saveActive() {
        val active = _activeTabUri.value ?: return
        viewModelScope.launch {
            ioMutex.withLock { saveTabLocked(active, silent = false) }
        }
    }

    // --- external change detection -------------------------------------------------

    /** called by the page while RESUMED; polls all open tabs for external edits */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollOnce()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        ioMutex.withLock {
            for (tab in _openTabs.value) {
                if (tab.missing) continue
                if (tab.uri == _externalChangeUri.value) continue
                val snap = statFile(tab.uri)
                when (compareSnapshots(tab.diskSnapshot, snap)) {
                    ChangeVerdict.MISSING -> {
                        _openTabs.value = _openTabs.value.map {
                            if (it.uri == tab.uri) it.copy(missing = true, dirty = true) else it
                        }
                        _notice.value = EditorNotice.FileMissing(tab.name)
                        persistSessionLocked()
                    }
                    ChangeVerdict.CHANGED -> {
                        // first conflict wins; remaining tabs are re-checked on
                        // the next cycle after this one is resolved
                        _externalChangeUri.value = tab.uri
                        break
                    }
                    ChangeVerdict.UNCHANGED -> {
                        // open-time stat failures leave a tab with no baseline;
                        // adopt the first successful stat instead of prompting
                        if (snap != null && tab.diskSnapshot.lastModified <= 0L &&
                            tab.diskSnapshot.length < 0L
                        ) {
                            _openTabs.value = _openTabs.value.map {
                                if (it.uri == tab.uri) it.copy(diskSnapshot = snap) else it
                            }
                        }
                    }
                }
            }
        }
    }

    /** dialog "Reload": discard in-editor state and load the on-disk content */
    fun reloadActiveFromDisk() {
        val uri = _externalChangeUri.value ?: return
        viewModelScope.launch {
            ioMutex.withLock {
                val tab = _openTabs.value.firstOrNull { it.uri == uri }
                _externalChangeUri.value = null
                if (tab == null) return@withLock
                when (val result = readUriContents(uri)) {
                    is OpenResult.Ok -> {
                        val snap = statFile(uri)
                        _openTabs.value = _openTabs.value.map {
                            if (it.uri == uri) {
                                it.copy(
                                    content = Content(result.text),
                                    readOnly = result.readOnly,
                                    dirty = false,
                                    contentVersion = it.contentVersion + 1,
                                    diskSnapshot = snap ?: it.diskSnapshot,
                                    missing = false,
                                )
                            } else it
                        }
                        sessionStore.deleteDraft(uri)
                        draftedUris.remove(uri)
                        persistSessionLocked()
                    }
                    OpenResult.TooLarge -> onReloadFailed(uri, EditorNotice.TooLarge(tab.name))
                    OpenResult.Binary -> onReloadFailed(uri, EditorNotice.Binary(tab.name))
                    OpenResult.Error -> onReloadFailed(uri, EditorNotice.Error(tab.name))
                }
            }
        }
    }

    private suspend fun onReloadFailed(uri: String, notice: EditorNotice) {
        _notice.value = notice
        // refresh the snapshot so the same disk state does not re-prompt; a null
        // stat means the file is gone
        val snap = statFile(uri)
        _openTabs.value = _openTabs.value.map {
            if (it.uri == uri) it.copy(
                diskSnapshot = snap ?: it.diskSnapshot,
                missing = snap == null,
            ) else it
        }
        persistSessionLocked()
    }

    /** dialog "Cancel": keep the in-editor content; it now diverges from disk */
    fun keepLocalContent() {
        val uri = _externalChangeUri.value ?: return
        viewModelScope.launch {
            ioMutex.withLock {
                val tab = _openTabs.value.firstOrNull { it.uri == uri }
                _externalChangeUri.value = null
                if (tab == null) return@withLock
                // adopt the new on-disk state as baseline so this same external
                // edit does not re-prompt; the NEXT external edit will
                val snap = statFile(uri)
                _openTabs.value = _openTabs.value.map {
                    if (it.uri == uri) {
                        it.copy(dirty = true, diskSnapshot = snap ?: it.diskSnapshot)
                    } else it
                }
                if (!tab.readOnly) writeDraftLocked(uri)
                persistSessionLocked()
            }
        }
    }

    // --- session persistence + auto-save -------------------------------------------

    fun toggleWordWrap() {
        viewModelScope.launch {
            settingsStore.update(
                settings.value.copy(codeEditorWordWrap = !settings.value.codeEditorWordWrap)
            )
        }
    }

    /** writes drafts for every dirty tab + the manifest; called on page stop/dispose */
    fun flushNow() {
        editDebounceJob?.cancel()
        sessionPersistJob?.cancel()
        viewModelScope.launch(NonCancellable) {
            ioMutex.withLock {
                for (tab in _openTabs.value) {
                    if (tab.dirty && !tab.readOnly) writeDraftLocked(tab.uri)
                }
                persistSessionLocked()
            }
        }
    }

    private suspend fun flushEdits(uri: String) {
        ioMutex.withLock {
            val tab = _openTabs.value.firstOrNull { it.uri == uri } ?: return@withLock
            if (!tab.dirty || tab.readOnly || tab.missing) return@withLock
            if (settings.value.codeEditorAutoSave) {
                val verdict = compareSnapshots(tab.diskSnapshot, statFile(uri))
                when (decideAutoSave(tab.dirty, tab.readOnly, _externalChangeUri.value == uri, verdict)) {
                    AutoSaveDecision.SAVE -> saveTabLocked(uri, silent = true)
                    AutoSaveDecision.DEFER_CONFLICT -> {
                        // never overwrite an external edit: keep the local
                        // content safe as a draft and raise the conflict flow
                        writeDraftLocked(uri)
                        if (verdict == ChangeVerdict.MISSING) {
                            _openTabs.value = _openTabs.value.map {
                                if (it.uri == uri) it.copy(missing = true) else it
                            }
                            _notice.value = EditorNotice.FileMissing(tab.name)
                        } else {
                            _externalChangeUri.value = uri
                        }
                    }
                    AutoSaveDecision.SKIP -> Unit
                }
            } else {
                writeDraftLocked(uri)
            }
        }
    }

    /** caller must hold [ioMutex] */
    private suspend fun saveTabLocked(uri: String, silent: Boolean) {
        val tab = _openTabs.value.firstOrNull { it.uri == uri } ?: return
        if (tab.readOnly || !tab.dirty) return
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(Uri.parse(tab.uri), "wt")?.use { os ->
                    os.write(tab.content.toString().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
        }
        if (ok) {
            // stat AFTER the stream closed, still under the lock, so the poller
            // never sees our own write as an external change
            val snap = statFile(uri)
            _openTabs.value = _openTabs.value.map {
                if (it.uri == uri) it.copy(dirty = false, diskSnapshot = snap ?: it.diskSnapshot) else it
            }
            sessionStore.deleteDraft(uri)
            draftedUris.remove(uri)
            if (!silent) _notice.value = EditorNotice.Saved(tab.name)
            persistSessionLocked()
        } else if (!silent) {
            _notice.value = EditorNotice.SaveFailed(tab.name)
        }
    }

    /** caller must hold [ioMutex] */
    private suspend fun writeDraftLocked(uri: String) {
        val tab = _openTabs.value.firstOrNull { it.uri == uri } ?: return
        val text = withContext(Dispatchers.IO) { tab.content.toString() }
        sessionStore.writeDraft(uri, text)
        draftedUris += uri
        persistSessionLocked()
    }

    private fun persistSessionDebounced() {
        sessionPersistJob?.cancel()
        sessionPersistJob = viewModelScope.launch {
            delay(SESSION_PERSIST_DEBOUNCE_MS)
            ioMutex.withLock { persistSessionLocked() }
        }
    }

    /** caller must hold [ioMutex] (or be on the only writer path) */
    private suspend fun persistSessionLocked() {
        sessionStore.saveSession(
            EditorSession(
                tabs = _openTabs.value.map { tab ->
                    SessionTab(
                        uri = tab.uri,
                        name = tab.name,
                        readOnly = tab.readOnly,
                        dirty = tab.dirty,
                        hasDraft = tab.uri in draftedUris,
                        diskLastModified = tab.diskSnapshot.lastModified,
                        diskLength = tab.diskSnapshot.length,
                    )
                },
                activeUri = _activeTabUri.value,
            )
        )
    }

    private suspend fun restoreSession() {
        val session = sessionStore.loadSession() ?: return
        if (session.tabs.isEmpty()) return
        val restored = mutableListOf<EditorTab>()
        val dropped = mutableListOf<String>()
        for (st in session.tabs) {
            val snap = statFile(st.uri)
            if (st.hasDraft) {
                val draftText = sessionStore.readDraft(st.uri)
                if (draftText != null) {
                    draftedUris += st.uri
                    restored += EditorTab(
                        uri = st.uri,
                        name = st.name,
                        content = Content(draftText),
                        readOnly = st.readOnly,
                        dirty = true,
                        // keep the PERSISTED snapshot: an external edit made
                        // while the app was closed is caught by the first poll
                        // and raised as a dirty conflict
                        diskSnapshot = DiskSnapshot(st.diskLastModified, st.diskLength),
                        missing = snap == null,
                    )
                } else if (snap != null) {
                    // draft lost but file alive: fall back to the disk content
                    restored += readCleanTab(st, snap)
                } else {
                    dropped += st.name
                }
            } else {
                if (snap == null) {
                    dropped += st.name
                } else {
                    val clean = readCleanTab(st, snap)
                    if (clean != null) restored += clean else dropped += st.name
                }
            }
        }
        if (restored.isNotEmpty()) {
            _openTabs.value = restored
            _activeTabUri.value = session.activeUri
                ?.takeIf { u -> restored.any { it.uri == u } }
                ?: restored.first().uri
        }
        if (dropped.isNotEmpty()) {
            _notice.value = EditorNotice.RestoreDropped(dropped)
        }
    }

    /** re-reads a clean tab from disk; null when the file can no longer be opened */
    private suspend fun readCleanTab(st: SessionTab, snap: DiskSnapshot): EditorTab? =
        when (val result = readUriContents(st.uri)) {
            is OpenResult.Ok -> EditorTab(
                uri = st.uri,
                name = st.name,
                content = Content(result.text),
                readOnly = result.readOnly,
                diskSnapshot = snap,
            )
            else -> null
        }

    fun dismissNotice() {
        _notice.value = EditorNotice.None
    }

    fun onShowHiddenChanged() {
        rebuildNodes()
    }

    // --- io primitives ---------------------------------------------------------------

    private suspend fun statFile(uri: String): DiskSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(uri))
            if (doc == null || !doc.exists()) null
            else DiskSnapshot(doc.lastModified(), doc.length())
        }.getOrNull()
    }

    private suspend fun readUriContents(uriString: String): OpenResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (size > MAX_OPEN_BYTES) {
            return@withContext OpenResult.TooLarge
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (total <= MAX_OPEN_BYTES) {
                    val read = ins.read(chunk)
                    if (read == -1) break
                    buf.write(chunk, 0, read)
                    total += read
                }
                val bytes = buf.toByteArray()
                val limit = minOf(bytes.size, MAX_OPEN_BYTES)
                var binary = false
                var i = 0
                while (i < minOf(limit, 8192)) {
                    if (bytes[i].toInt() == 0) {
                        binary = true
                        break
                    }
                    i++
                }
                when {
                    binary -> OpenResult.Binary
                    else -> OpenResult.Ok(
                        text = String(bytes, 0, limit, Charsets.UTF_8),
                        readOnly = size > READ_ONLY_THRESHOLD,
                    )
                }
            } ?: OpenResult.Error
        }.getOrElse { OpenResult.Error }
    }

    private sealed interface OpenResult {
        data object TooLarge : OpenResult
        data object Binary : OpenResult
        data object Error : OpenResult
        data class Ok(val text: String, val readOnly: Boolean) : OpenResult
    }

    private suspend fun loadChildren(dirUri: String, force: Boolean = false) {
        if (!force && childrenCache.containsKey(dirUri)) return
        _loadingDirs.value = _loadingDirs.value + dirUri
        val kids = withContext(Dispatchers.IO) {
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
                    ?.listFiles()
                    ?.map { df ->
                        ChildEntry(
                            uri = df.uri.toString(),
                            name = df.name ?: "?",
                            isDir = df.isDirectory,
                            size = if (df.isDirectory) 0 else df.length(),
                        )
                    }
                    ?.sortedWith(childComparator)
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        childrenCache[dirUri] = kids
        _loadingDirs.value = _loadingDirs.value - dirUri
        rebuildNodes()
    }

    private fun rebuildNodes() {
        val root = rootUriString ?: return
        val showHidden = settings.value.codeEditorShowHidden
        val out = mutableListOf<FileNode>()
        fun walk(uri: String, depth: Int) {
            val kids = childrenCache[uri] ?: return
            for (k in kids) {
                if (!showHidden && k.name.startsWith(".")) continue
                out += FileNode(k.uri, k.name, k.isDir, depth)
                if (k.isDir && k.uri in _expanded.value) walk(k.uri, depth + 1)
            }
        }
        walk(root, 0)
        _nodes.value = out
    }

    companion object {
        private val childComparator = compareByDescending<ChildEntry> { it.isDir }
            .thenComparator { a, b -> compareNatural(a.name, b.name) }

        // natural sort: file2 < file10, case-insensitive, digit runs compare numerically
        private fun compareNatural(a: String, b: String): Int {
            val ta = tokenize(a)
            val tb = tokenize(b)
            val n = minOf(ta.size, tb.size)
            for (i in 0 until n) {
                val x = ta[i]
                val y = tb[i]
                if (x == y) continue
                val xn = x.toLongOrNull()
                val yn = tb[i].toLongOrNull()
                return when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
            }
            return ta.size.compareTo(tb.size)
        }

        private fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val lower = s.lowercase()
            while (i < lower.length) {
                var j = i
                val digit = lower[i].isDigit()
                while (j < lower.length && lower[j].isDigit() == digit) j++
                out += lower.substring(i, j)
                i = j
            }
            return out
        }
    }
}
