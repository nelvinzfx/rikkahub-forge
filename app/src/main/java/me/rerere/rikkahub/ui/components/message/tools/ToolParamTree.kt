package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.McpServer
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
 * Pill-style collapsible JSON tree.
 *
 * Collapsed: a single rounded-outline pill — MCP icon, a dim [label] ("Ran"/
 * "Result"), the first entry's key in a brighter tone and its value ellipsized
 * to one line. Click to expand into the full connector tree, click again to
 * collapse. Outline stroke only, no container fill.
 * Skips empty objects/arrays/blank values.
 */
@Composable
fun ToolParamTree(
    element: JsonElement,
    label: String,
    modifier: Modifier = Modifier,
) {
    if (element is JsonObject && element.isEmpty()) return
    if (element is JsonArray && element.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val brightKeyColor = MaterialTheme.colorScheme.onSurface
    val shape = if (expanded) RoundedCornerShape(12.dp) else RoundedCornerShape(percent = 50)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.McpServer,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = labelColor,
            )
            val (previewKey, previewValue) = remember(element) { firstEntryPreview(element) }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = labelColor)) { append("$label ") }
                    withStyle(SpanStyle(color = brightKeyColor)) { append(previewKey) }
                    withStyle(SpanStyle(color = labelColor)) { append(": $previewValue") }
                },
                fontFamily = treeFont,
                fontSize = treeFontSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .heightIn(max = MAX_TREE_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TreeChildren(element, depth = 0, ancestorHasMore = emptyList())
            }
        }
    }
}

/**
 * First non-empty entry of [element] rendered as the pill's one-line preview:
 * the key plus a single-line scalar (containers collapse to "{…}"/"[…]").
 */
private fun firstEntryPreview(element: JsonElement): Pair<String, String> {
    val entry: Pair<String, JsonElement>? = when (element) {
        is JsonObject -> element.entries
            .firstOrNull { !isEmptyValue(it.value) }
            ?.let { it.key to it.value }
        is JsonArray -> {
            val idx = element.indexOfFirst { !isEmptyValue(it) }
            if (idx >= 0) "[$idx]" to element[idx] else null
        }
        else -> null
    } ?: return "" to ""
    val valueText = when (val v = entry.second) {
        is JsonPrimitive -> v.contentOrNull.orEmpty()
            .trimEnd('\n', '\r')
            .lineSequence().firstOrNull().orEmpty()
        is JsonObject -> "{…}"
        is JsonArray -> "[…]"
    }
    return entry.first to valueText
}

@Composable
private fun TreeChildren(
    element: JsonElement,
    depth: Int,
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
        val isFirst = index == 0
        val hasMore = !isLast
        val childAncestors = ancestorHasMore + hasMore

        TreeNode(
            key = key,
            value = value,
            depth = depth,
            isLast = isLast,
            isFirst = isFirst,
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
    isFirst: Boolean,
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
                isFirst = isFirst,
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
                isFirst = isFirst,
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
                    ancestorHasMore = childAncestorHasMore,
                )
            }
        }

        is JsonPrimitive -> {
            val content = value.contentOrNull ?: ""
            if (content.isBlank()) return
            val context = LocalContext.current
            val toaster = LocalToaster.current

            // Strip trailing blank lines BEFORE counting so the line count reflects real
            // content. String.lines() yields a trailing empty element for a trailing
            // newline ("a\nb\n" -> [a, b, ""]), which would over-count by 1+ and could
            // show a misleading "1 more lines" when nothing real was actually hidden.
            val allLines = content.trimEnd('\n', '\r').lines()
            // Only truncate when there are genuinely MORE non-empty lines beyond the cap.
            // If everything past MAX_VALUE_LINES is blank, hiddenCount drops to 0 and we
            // show the full thing instead of a deceptive "view details" with empty tail.
            val hiddenCount = allLines.drop(MAX_VALUE_LINES).count { it.isNotBlank() }
            val truncated = hiddenCount > 0
            val lines = if (truncated) allLines.take(MAX_VALUE_LINES) else allLines

            val annotated = buildAnnotatedString {
                withStyle(keyStyle.copy(color = keyColor)) { append("$key: ") }
                withStyle(valStyle.copy(color = valColor)) { append(lines.first()) }
                for (i in 1 until lines.size) {
                    append("\n")
                    withStyle(valStyle.copy(color = valColor)) { append(lines[i]) }
                }
                if (truncated) {
                    append("\n... ($hiddenCount more lines, view details)")
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

            TreeRow(
                depth = depth,
                isLast = isLast,
                isFirst = isFirst,
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
    isFirst: Boolean,
    ancestorHasMore: List<Boolean>,
    lineColor: Color,
    rowHeight: Float,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable () -> Unit,
) {
    val drawModifier = Modifier.drawBehind {
        // Butt cap = line ends exactly at endpoint, no overhang. Adjacent rows draw
        // from y=0..height so the verticals meet seamlessly with zero overlap = no dots.
        val strokeButt = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Butt)

        // --- Ancestor vertical lines (continuous through the row) ---
        for (i in 0 until depth) {
            if (ancestorHasMore.getOrElse(i) { false }) {
                val x = (i + 1) * INDENT_PX
                val ancestorPath = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x, size.height)
                }
                drawPath(ancestorPath, color = lineColor, style = strokeButt)
            }
        }

        // --- This node's connector ---
        val spineX = (depth + 1) * INDENT_PX
        val contentX = spineX + BRANCH_PX
        val centerY = size.height / 2f

        // Curve radius: how far up the vertical the bend starts AND how far right it
        // reaches before settling horizontal. Bigger = rounder. MUST be clamped to the
        // vertical room actually available in this row — the desired 16px easily exceeds
        // half the row height on a single-line value (centerY can be ~7-20px). Unclamped,
        // (centerY - curveRadius) goes NEGATIVE and (centerY + curveRadius) exceeds
        // height, so the bend's vertical leg renders past the row bounds and overlaps the
        // neighbouring row's line in the same column — the visible "thick vertical bar".
        // Clamp keeps the bend inside [0, height] so nothing bleeds into adjacent rows.
        val curveRadius = minOf(BRANCH_PX * 0.9f, centerY, size.height - centerY)
            .coerceAtLeast(0f)

        // Three connector shapes, all corner-rounded with NO sharp/pointed tips:
        //  - First child (top of the brace): vertical continues DOWN to its siblings,
        //    and the branch curls OUT from the top — a smooth inverted bend. No straight
        //    vertical run above the bend (first child is the topmost, nothing above it).
        //  - Last child (bottom of the brace): vertical comes from the top, then curls
        //    out at the bottom. Mirror of the first.
        //  - Middle child: plain right-angle tee — full vertical + a straight horizontal
        //    branch. Square corner (90°), which is tidy, not a pointed tip.
        // first && last (sole child) falls through to the last-child rounded shape.
        val connector = when {
            isFirst && !isLast -> {
                // Top of the brace. Vertical from the bend down to the bottom (feeds the
                // siblings below); branch curls outward from the top edge.
                Path().apply {
                    moveTo(spineX, size.height)
                    lineTo(spineX, centerY + curveRadius)
                    cubicTo(
                        spineX, centerY,
                        spineX, centerY,
                        spineX + curveRadius, centerY,
                    )
                    lineTo(contentX, centerY)
                }
            }

            !isFirst && !isLast -> {
                // Middle: full-height vertical (butts against neighbours) + straight branch.
                drawLine(
                    color = lineColor,
                    start = Offset(spineX, 0f),
                    end = Offset(spineX, size.height),
                    strokeWidth = STROKE_WIDTH,
                    cap = StrokeCap.Butt,
                )
                Path().apply {
                    moveTo(spineX, centerY)
                    lineTo(contentX, centerY)
                }
            }

            else -> {
                // Last child (and sole child): vertical from the top down to the bend,
                // then a rounded corner out to the content — bottom of the brace.
                Path().apply {
                    moveTo(spineX, 0f)
                    lineTo(spineX, centerY - curveRadius)
                    cubicTo(
                        spineX, centerY,
                        spineX, centerY,
                        spineX + curveRadius, centerY,
                    )
                    lineTo(contentX, centerY)
                }
            }
        }
        drawPath(connector, color = lineColor, style = strokeButt)
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
