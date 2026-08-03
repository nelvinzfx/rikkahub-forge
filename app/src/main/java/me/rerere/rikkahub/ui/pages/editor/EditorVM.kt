package me.rerere.rikkahub.ui.pages.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val MAX_PREVIEW_BYTES = 512 * 1024
private const val MAX_PREVIEW_FILE_SIZE = 2L * 1024 * 1024

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

data class OpenFile(
    val name: String,
    val text: String,
    val truncated: Boolean,
)

sealed interface FilePreview {
    data object None : FilePreview
    data object Loading : FilePreview
    data class Ready(val file: OpenFile) : FilePreview
    data class TooLarge(val name: String, val sizeBytes: Long) : FilePreview
    data class Binary(val name: String) : FilePreview
    data class Error(val name: String) : FilePreview
}

/**
 * State owner for the experimental code editor. Holds the SAF tree grant, the
 * per-directory children cache (lazy, loaded on first expand), expansion state,
 * and the lightweight read-only preview. The nvim-tree style flattening is
 * recomputed on every expand/collapse/refresh from the cache.
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

    private val _preview = MutableStateFlow<FilePreview>(FilePreview.None)
    val preview = _preview.asStateFlow()

    private val childrenCache = mutableMapOf<String, List<ChildEntry>>()
    private var rootUriString: String? = null
    private var initialized = false

    fun initFromSettings() {
        if (initialized) return
        initialized = true
        val uri = settings.value.codeEditorTreeUri ?: return
        rootUriString = uri
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
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        rootUriString = uri.toString()
        childrenCache.clear()
        _expanded.value = emptySet()
        _preview.value = FilePreview.None
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
            // reload previously expanded dirs so expansion survives a refresh
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
        _preview.value = FilePreview.Loading
        viewModelScope.launch {
            _preview.value = withContext(Dispatchers.IO) {
                val uri = Uri.parse(node.uri)
                val size = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
                if (size > MAX_PREVIEW_FILE_SIZE) {
                    return@withContext FilePreview.TooLarge(node.name, size)
                }
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var total = 0
                        while (total <= MAX_PREVIEW_BYTES) {
                            val read = ins.read(chunk)
                            if (read == -1) break
                            buf.write(chunk, 0, read)
                            total += read
                        }
                        val bytes = buf.toByteArray()
                        val truncated = total > MAX_PREVIEW_BYTES
                        val limit = minOf(bytes.size, MAX_PREVIEW_BYTES)
                        var binary = false
                        var i = 0
                        while (i < minOf(limit, 8192)) {
                            if (bytes[i].toInt() == 0) {
                                binary = true
                                break
                            }
                            i++
                        }
                        if (binary) {
                            FilePreview.Binary(node.name)
                        } else {
                            FilePreview.Ready(
                                OpenFile(
                                    name = node.name,
                                    text = String(bytes, 0, limit, Charsets.UTF_8),
                                    truncated = truncated,
                                )
                            )
                        }
                    } ?: FilePreview.Error(node.name)
                }.getOrElse { FilePreview.Error(node.name) }
            }
        }
    }

    fun closePreview() {
        _preview.value = FilePreview.None
    }

    fun onShowHiddenChanged() {
        // re-flatten with the new filter; the cache stays valid
        rebuildNodes()
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
