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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val MAX_OPEN_BYTES = 5 * 1024 * 1024
private const val READ_ONLY_THRESHOLD = 2L * 1024 * 1024

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
)

sealed interface EditorNotice {
    data object None : EditorNotice
    data class TooLarge(val name: String) : EditorNotice
    data class Binary(val name: String) : EditorNotice
    data class Error(val name: String) : EditorNotice
    data class Saved(val name: String) : EditorNotice
    data class SaveFailed(val name: String) : EditorNotice
}

/**
 * State owner for the experimental code editor. Holds the SAF tree grant, the
 * per-directory children cache, expansion state, and the open-tab set. Each tab
 * owns a sora [Content] document; saving serializes the active tab's Content
 * back through SAF.
 */
class EditorVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
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

    /** set while the view swaps documents on tab switch, so the swap is not counted as an edit */
    val swapGuard = AtomicBoolean(false)

    private val childrenCache = mutableMapOf<String, List<ChildEntry>>()
    private var rootUriString: String? = null
    private var initialized = false

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
        _rootName.value = DocumentFile.fromTreeUri(context, uri)?.name
        viewModelScope.launch {
            val current = settings.value
            settingsStore.update(current.copy(codeEditorTreeUri = uri.toString()))
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
            val result = withContext(Dispatchers.IO) {
                val uri = Uri.parse(node.uri)
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
            when (result) {
                OpenResult.TooLarge -> _notice.value = EditorNotice.TooLarge(node.name)
                OpenResult.Binary -> _notice.value = EditorNotice.Binary(node.name)
                OpenResult.Error -> _notice.value = EditorNotice.Error(node.name)
                is OpenResult.Ok -> {
                    val tab = EditorTab(
                        uri = node.uri,
                        name = node.name,
                        content = Content(result.text),
                        readOnly = result.readOnly,
                    )
                    _openTabs.value = _openTabs.value + tab
                    _activeTabUri.value = tab.uri
                }
            }
        }
    }

    fun activateTab(uri: String) {
        if (_openTabs.value.any { it.uri == uri }) {
            _activeTabUri.value = uri
        }
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
    }

    fun saveActive() {
        val active = _activeTabUri.value ?: return
        val tab = _openTabs.value.firstOrNull { it.uri == active } ?: return
        if (tab.readOnly || !tab.dirty) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(Uri.parse(tab.uri), "wt")?.use { os ->
                        os.write(tab.content.toString().toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            if (ok) {
                _openTabs.value = _openTabs.value.map {
                    if (it.uri == active) it.copy(dirty = false) else it
                }
                _notice.value = EditorNotice.Saved(tab.name)
            } else {
                _notice.value = EditorNotice.SaveFailed(tab.name)
            }
        }
    }

    fun dismissNotice() {
        _notice.value = EditorNotice.None
    }

    fun onShowHiddenChanged() {
        rebuildNodes()
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
                val yn = y.toLongOrNull()
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
