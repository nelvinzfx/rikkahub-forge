package me.rerere.rikkahub.utils

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

private const val DEFAULT_CONTEXT_LINES = 3

/**
 * Diff header path following git's a/ b/ convention: the prefix must join a RELATIVE path.
 * Callers pass either relative paths (workspace/APK entry paths like "res/layout/x.xml") or
 * absolute ones (termux file tools resolve to /data/...); without stripping, the absolute
 * case renders as the malformed-looking "a//data/..." double slash.
 */
internal fun diffHeaderPath(path: String): String = path.removePrefix("/")

/**
 * 生成 [oldText] 到 [newText] 的 unified diff 文本, 内容相同时返回 null
 */
fun generateUnifiedDiff(
    oldText: String,
    newText: String,
    path: String,
    contextLines: Int = DEFAULT_CONTEXT_LINES,
): String? {
    if (oldText == newText) return null
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val patch = DiffUtils.diff(oldLines, newLines)
    if (patch.deltas.isEmpty()) return null
    val headerPath = diffHeaderPath(path)
    return UnifiedDiffUtils
        .generateUnifiedDiff("a/$headerPath", "b/$headerPath", oldLines, patch, contextLines)
        .joinToString("\n")
}
