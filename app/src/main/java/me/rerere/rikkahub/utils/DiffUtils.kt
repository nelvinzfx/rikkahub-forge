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

internal fun commonLineTrim(oldLines: List<String>, newLines: List<String>): Pair<Int, Int> {
    val shared = minOf(oldLines.size, newLines.size)
    var prefix = 0
    while (prefix < shared && oldLines[prefix] == newLines[prefix]) prefix++
    val suffixLimit = minOf(oldLines.size - prefix, newLines.size - prefix)
    var suffix = 0
    while (suffix < suffixLimit && oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
    return prefix to suffix
}

/**
 * Unified diff whose Myers run is bounded to the changed middle: the common line
 * prefix/suffix is trimmed first (keeping [contextLines] of context on each side so
 * hunk context windows stay inside the slice), so a large file with small scattered
 * edits produces per-edit hunks with global line numbers instead of tripping the
 * caller's quadratic full-size work gate. Hunks whose context windows touch are merged
 * into one, matching git behavior. Returns null when the texts are equal or only
 * line-invisible details differ, so callers can fall back to their whole-span renderer.
 */
fun generateTrimmedUnifiedDiff(
    oldText: String,
    newText: String,
    path: String,
    contextLines: Int = DEFAULT_CONTEXT_LINES,
): String? {
    if (oldText == newText) return null
    val context = contextLines.coerceAtLeast(0)
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val (rawPrefix, rawSuffix) = commonLineTrim(oldLines, newLines)
    val prefix = maxOf(0, rawPrefix - context)
    val suffix = maxOf(0, rawSuffix - context)
    val midOld = oldLines.subList(prefix, oldLines.size - suffix)
    val midNew = newLines.subList(prefix, newLines.size - suffix)
    if (midOld.isEmpty() && midNew.isEmpty()) return null
    val deltas = DiffUtils.diff(midOld, midNew).deltas.sortedBy { it.source.position }
    if (deltas.isEmpty()) return null
    val headerPath = diffHeaderPath(path)
    val builder = StringBuilder()
    builder.append("--- a/").append(headerPath).append('\n')
    builder.append("+++ b/").append(headerPath).append('\n')
    var start = 0
    while (start < deltas.size) {
        var end = start
        while (end + 1 < deltas.size &&
            deltas[end + 1].source.position <= deltas[end].source.position + deltas[end].source.size() + 2 * context
        ) end++
        val firstDelta = deltas[start]
        val lastDelta = deltas[end]
        val oldStart = maxOf(0, firstDelta.source.position - context)
        val oldEnd = minOf(midOld.size, lastDelta.source.position + lastDelta.source.size() + context)
        val shiftBefore = deltas.subList(0, start).sumOf { it.target.size() - it.source.size() }
        val shiftInside = deltas.subList(start, end + 1).sumOf { it.target.size() - it.source.size() }
        val newStart = oldStart + shiftBefore
        val newEnd = newStart + (oldEnd - oldStart) + shiftInside
        val oldCount = oldEnd - oldStart
        val newCount = newEnd - newStart
        val oldHeader = if (oldCount == 0) prefix + oldStart else prefix + oldStart + 1
        val newHeader = if (newCount == 0) prefix + newStart else prefix + newStart + 1
        builder.append("@@ -").append(oldHeader).append(',').append(oldCount)
        builder.append(" +").append(newHeader).append(',').append(newCount).append(" @@\n")
        var oldPtr = oldStart
        for (delta in deltas.subList(start, end + 1)) {
            while (oldPtr < delta.source.position) {
                builder.append(' ').append(midOld[oldPtr]).append('\n')
                oldPtr++
            }
            for (line in delta.source.lines) builder.append('-').append(line).append('\n')
            for (line in delta.target.lines) builder.append('+').append(line).append('\n')
            oldPtr += delta.source.size()
        }
        while (oldPtr < oldEnd) {
            builder.append(' ').append(midOld[oldPtr]).append('\n')
            oldPtr++
        }
        start = end + 1
    }
    return builder.toString()
}
