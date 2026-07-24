package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach

internal val DiffAddedColor = Color(0xFF4CAF50)
internal val DiffRemovedColor = Color(0xFFEF5350)

/** unified diff 的增删行数统计 */
internal data class DiffStats(val additions: Int, val deletions: Int)

internal fun parseDiffStats(diff: String): DiffStats {
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> {}
            line.startsWith("+") -> additions++
            line.startsWith("-") -> deletions++
        }
    }
    return DiffStats(additions, deletions)
}

internal enum class DiffLineKind { ADD, DELETE, CONTEXT, HUNK, META }

internal data class DiffDisplayLine(
    val kind: DiffLineKind,
    val sign: Char,
    val gutter: String,
    val text: String,
)

private val HunkHeaderRegex = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

/**
 * Parse unified diff text into display rows with GitHub-style gutter numbers:
 * deletions and context lines show old-file numbers, additions show new-file
 * numbers, and hunk headers collapse into a dim ellipsis separator. Lines
 * outside any hunk (headers, malformed input) carry no number.
 */
internal fun layoutDiffLines(diff: String, showFileHeader: Boolean): List<DiffDisplayLine> {
    val raw = diff.lines()
    val startIndex = if (!showFileHeader && raw.size >= 2 &&
        raw[0].startsWith("---") && raw[1].startsWith("+++")
    ) {
        2
    } else {
        0
    }
    val result = ArrayList<DiffDisplayLine>(raw.size - startIndex)
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    for (i in startIndex until raw.size) {
        val line = raw[i]
        when {
            line.startsWith("@@") -> {
                val match = HunkHeaderRegex.find(line)
                if (match == null) {
                    result += DiffDisplayLine(DiffLineKind.META, ' ', "", line)
                } else {
                    oldLine = match.groupValues[1].toInt()
                    newLine = match.groupValues[2].toInt()
                    inHunk = true
                    result += DiffDisplayLine(DiffLineKind.HUNK, ' ', "", "…")
                }
            }
            line.startsWith("+++") || line.startsWith("---") ->
                result += DiffDisplayLine(DiffLineKind.META, ' ', "", line)
            line.startsWith("+") && inHunk -> {
                result += DiffDisplayLine(DiffLineKind.ADD, '+', newLine.toString(), line.substring(1))
                newLine++
            }
            line.startsWith("-") && inHunk -> {
                result += DiffDisplayLine(DiffLineKind.DELETE, '-', oldLine.toString(), line.substring(1))
                oldLine++
            }
            line.startsWith("\\") ->
                result += DiffDisplayLine(DiffLineKind.META, ' ', "", line)
            inHunk -> {
                val text = if (line.startsWith(" ")) line.substring(1) else line
                result += DiffDisplayLine(DiffLineKind.CONTEXT, ' ', oldLine.toString(), text)
                oldLine++
                newLine++
            }
            else -> result += DiffDisplayLine(DiffLineKind.META, ' ', "", line)
        }
    }
    return result
}

/**
 * 渲染 unified diff 文本, GitHub 风格行号 + 按行着色, 支持横向滚动; 纵向滚动由调用方容器提供
 *
 * @param maxLines 最多渲染的行数, 超出部分折叠为一行提示
 * @param showFileHeader 是否渲染开头的 `---`/`+++` 文件头
 */
@Composable
fun DiffView(
    diff: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    showFileHeader: Boolean = true,
) {
    val allLines = remember(diff, showFileHeader) { layoutDiffLines(diff, showFileHeader) }
    val lines = remember(allLines, maxLines) { allLines.take(maxLines) }
    val truncated = allLines.size - lines.size
    val gutterWidth = remember(lines) { lines.maxOfOrNull { it.gutter.length } ?: 0 }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .width(IntrinsicSize.Max)
            .padding(vertical = 4.dp),
    ) {
        lines.fastForEach { line ->
            DiffLine(line, gutterWidth)
        }
        if (truncated > 0) {
            Text(
                text = "… +$truncated lines",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun DiffLine(line: DiffDisplayLine, gutterWidth: Int) {
    val (textColor, background) = when (line.kind) {
        DiffLineKind.ADD -> DiffAddedColor to DiffAddedColor.copy(alpha = 0.12f)
        DiffLineKind.DELETE -> DiffRemovedColor to DiffRemovedColor.copy(alpha = 0.12f)
        DiffLineKind.HUNK -> MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
        DiffLineKind.META -> MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
        DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurface to Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = "${line.sign} ${line.gutter.padStart(gutterWidth)}",
            color = textColor.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            softWrap = false,
        )
        Text(
            text = " ${line.text.ifEmpty { " " }}",
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            softWrap = false,
        )
    }
}
