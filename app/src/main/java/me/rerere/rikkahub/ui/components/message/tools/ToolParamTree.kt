package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.ui.context.LocalToaster

// Layout constants — wider indent for better curve visibility
private const val INDENT_PX = 22f
private const val BRANCH_PX = 18f
private const val STROKE_WIDTH = 1.5f
private const val MAX_VALUE_LINES = 15
private const val MAX_TREE_HEIGHT = 300

private val treeFont = FontFamily.Monospace
private const val treeFontSize = 11f
private const val treeLineHeight = 15f

private val treeStyle = SpanStyle(
    fontFamily = treeFont,
    fontSize = treeFontSize.sp,
)

private val keyStyle = SpanStyle(
    fontFamily = treeFont,
    fontSize = treeFontSize.sp,
)

private val valStyle = SpanStyle(
    fontFamily = treeFont,
    fontSize = treeFontSize.sp,
)

/**
 * Tree-style JSON display with smooth curved connectors drawn via Canvas.
 * Lines are continuous (no gaps) with rounded bezier transitions.
 * Skips empty objects/arrays/blank values.
 */
@Composable
fun ToolParamTree(
    element: JsonElement,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    if (element is JsonObject && element.isEmpty()) return
    if (element is JsonArray && element.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(max = MAX_TREE_HEIGHT.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        TreeChildren(element, depth = 0, loading = loading, ancestorHasMore = emptyList())
    }
}

@Composable
private fun TreeChildren(
    element: JsonElement,
    depth: Int,
    loading: Boolean,
    ancestorHasMore: List<Boolean>,
) {
    val children: List<Pair<String, JsonElement>> = when (element) {
        is JsonObject -> element.entries
            .filterNot { (_, v) -> isEmptyValue(v) }
            .map { it.key to it.value }
        is JsonArray -> element
            .filterNot { isEmptyValue(it) }
            .mapIndexed { i, v -> "[$i]" to v }
        else -> emptyList()
    }

    children.forEachIndexed { index, (key, value) ->
        val isLast = index == children.lastIndex
        val hasMore = !isLast
        val childAncestors = ancestorHasMore + hasMore

        TreeNode(
            key = key,
            value = value,
            depth = depth,
            isLast = isLast,
            loading = loading,
            ancestorHasMore = ancestorHasMore,
            childAncestorHasMore = childAncestors,
        )
    }
}

@Composable
private fun TreeNode(
    key: String,
    value: JsonElement,
    depth: Int,
    isLast: Boolean,
    loading: Boolean,
    ancestorHasMore: List<Boolean>,
    childAncestorHasMore: List<Boolean>,
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val keyColor = MaterialTheme.colorScheme.secondary
    val valColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val rowHeight = with(density) { treeLineHeight.sp.toPx() }

    when (value) {
        is JsonObject -> {
            if (value.isEmpty()) return
            var expanded by remember(key, depth) { mutableStateOf(true) }

            TreeRow(
                depth = depth,
                isLast = isLast,
                ancestorHasMore = ancestorHasMore,
                lineColor = lineColor,
                rowHeight = rowHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(keyStyle.copy(color = keyColor)) { append(" $key") }
                    },
                )
            }
            if (expanded) {
                TreeChildren(
                    value,
                    depth = depth + 1,
                    loading = loading,
                    ancestorHasMore = childAncestorHasMore,
                )
            }
        }

        is JsonArray -> {
            if (value.isEmpty()) return
            var expanded by remember(key, depth) { mutableStateOf(true) }

            TreeRow(
                depth = depth,
                isLast = isLast,
                ancestorHasMore = ancestorHasMore,
                lineColor = lineColor,
                rowHeight = rowHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(keyStyle.copy(color = keyColor)) { append(" $key (${value.size})") }
                    },
                )
            }
            if (expanded) {
                TreeChildren(
                    value,
                    depth = depth + 1,
                    loading = loading,
                    ancestorHasMore = childAncestorHasMore,
                )
            }
        }

        is JsonPrimitive -> {
            val content = value.contentOrNull ?: ""
            if (content.isBlank()) return
            val context = LocalContext.current
            val toaster = LocalToaster.current

            val allLines = content.lines()
            val truncated = allLines.size > MAX_VALUE_LINES
            val lines = if (truncated) allLines.take(MAX_VALUE_LINES) else allLines

            val annotated = buildAnnotatedString {
                withStyle(keyStyle.copy(color = keyColor)) { append("$key: ") }
                withStyle(valStyle.copy(color = valColor)) { append(lines.first()) }
                for (i in 1 until lines.size) {
                    append("\n")
                    withStyle(valStyle.copy(color = valColor)) { append(lines[i]) }
                }
                if (truncated) {
                    append("\n... (${allLines.size - MAX_VALUE_LINES} more lines, view details)")
                }
            }

            val copyModifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("value", content))
                    toaster.show("Copied", type = ToastType.Success)
                },
            )

            if (loading && annotated.length > 1) {
                val visibleCount by produceState(initialValue = 1, annotated) {
                    for (i in 1..annotated.length) {
                        this.value = i
                        delay(8)
                    }
                }
                val count = visibleCount.coerceIn(1, annotated.length)
                TreeRow(
                    depth = depth,
                    isLast = isLast,
                    ancestorHasMore = ancestorHasMore,
                    lineColor = lineColor,
                    rowHeight = rowHeight,
                    modifier = Modifier.fillMaxWidth().then(copyModifier),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = annotated.subSequence(0, count),
                        style = treeStyle.toTextStyle(),
                        maxLines = 50,
                        overflow = TextOverflow.Visible,
                    )
                }
            } else {
                TreeRow(
                    depth = depth,
                    isLast = isLast,
                    ancestorHasMore = ancestorHasMore,
                    lineColor = lineColor,
                    rowHeight = rowHeight,
                    modifier = Modifier.fillMaxWidth().then(copyModifier),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = annotated,
                        style = treeStyle.toTextStyle(),
                        maxLines = 50,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

/**
 * Tree row with smooth curved connectors drawn via Canvas Path.
 *
 * Each ancestor level draws a continuous vertical line that extends 1px
 * beyond row bounds (top/bottom) to guarantee no gaps between rows.
 *
 * The branch connector from vertical spine to node content is a smooth
 * bezier curve (quadraticTo) instead of a sharp 90-degree corner.
 *
 * Last child: curve turns right and stops (no vertical continuation).
 * Non-last child: vertical continues through, curve branches right.
 */
@Composable
private fun TreeRow(
    depth: Int,
    isLast: Boolean,
    ancestorHasMore: List<Boolean>,
    lineColor: Color,
    rowHeight: Float,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable () -> Unit,
) {
    val drawModifier = Modifier.drawBehind {
        val strokeSquare = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Square)
        val strokeButt = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Butt)

        // --- Ancestor vertical lines (continuous, extended to overlap) ---
        // Square cap: flat ends, extends half-width for clean overlap with adjacent rows
        for (i in 0 until depth) {
            if (ancestorHasMore.getOrElse(i) { false }) {
                val x = (i + 1) * INDENT_PX
                drawLine(
                    color = lineColor,
                    start = Offset(x, -1f),
                    end = Offset(x, size.height + 1f),
                    strokeWidth = STROKE_WIDTH,
                    cap = StrokeCap.Square,
                )
            }
        }

        // --- This node's connector ---
        val spineX = (depth + 1) * INDENT_PX
        val contentX = spineX + BRANCH_PX
        val centerY = rowHeight / 2f

        if (!isLast) {
            // Non-last: full height vertical (square cap for row continuity)
            drawLine(
                color = lineColor,
                start = Offset(spineX, -1f),
                end = Offset(spineX, size.height + 1f),
                strokeWidth = STROKE_WIDTH,
                cap = StrokeCap.Square,
            )
            // Branch curve: butt cap — no dot where it meets the vertical
            val branchPath = Path().apply {
                moveTo(spineX, centerY)
                quadraticTo(
                    spineX + BRANCH_PX * 0.5f, centerY,
                    contentX, centerY,
                )
            }
            drawPath(branchPath, color = lineColor, style = strokeButt)
        } else {
            // Last child: merge vertical + curve into ONE continuous path
            val fullPath = Path().apply {
                moveTo(spineX, -1f)
                lineTo(spineX, centerY)
                quadraticTo(
                    spineX + BRANCH_PX * 0.5f, centerY,
                    contentX, centerY,
                )
            }
            drawPath(fullPath, color = lineColor, style = strokeSquare)
        }
    }

    // Content offset: indent * depth + branch width + a bit extra
    val contentPadding = (depth * INDENT_PX + INDENT_PX + BRANCH_PX + 4).toInt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(drawModifier)
            .padding(start = contentPadding.dp),
        verticalAlignment = verticalAlignment,
    ) {
        content()
    }
}

private fun SpanStyle.toTextStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.ui.text.TextStyle(
        fontFamily = fontFamily,
        fontSize = fontSize,
        lineHeight = treeLineHeight.sp,
    )

private fun isEmptyValue(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.isEmpty()
    is JsonArray -> element.isEmpty()
    is JsonPrimitive -> element.contentOrNull.isNullOrBlank()
    else -> false
}
