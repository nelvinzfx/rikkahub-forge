package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.PathMeasure
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

// --- Electric-flow animation (experimental, active only while a tool is running) ---
// A bright silver/white pulse travels along each connector path like current in a wire.
private const val FLOW_CYCLE_MS = 900            // one head sweep top->content; lower = faster
private const val FLOW_PULSE_FRACTION = 0.28f    // length of the bright pulse as a fraction of path
private const val FLOW_SEGMENTS = 14             // sub-segments used to fade the pulse tail
private const val FLOW_GLOW_WIDTH = 4.5f         // wide, low-alpha underlay = glow halo
private const val FLOW_CORE_WIDTH = 1.8f         // bright core stroke width
private val flowSilver = Color(0xFFB8C2CC)       // silver
private val flowWhite = Color(0xFFFFFFFF)        // white-hot head

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
        val isFirst = index == 0
        val hasMore = !isLast
        val childAncestors = ancestorHasMore + hasMore

        TreeNode(
            key = key,
            value = value,
            depth = depth,
            isLast = isLast,
            isFirst = isFirst,
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
    isFirst: Boolean,
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
                isFirst = isFirst,
                ancestorHasMore = ancestorHasMore,
                lineColor = lineColor,
                rowHeight = rowHeight,
                loading = loading,
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
                isFirst = isFirst,
                ancestorHasMore = ancestorHasMore,
                lineColor = lineColor,
                rowHeight = rowHeight,
                loading = loading,
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
                    isFirst = isFirst,
                    ancestorHasMore = ancestorHasMore,
                    lineColor = lineColor,
                    rowHeight = rowHeight,
                    loading = loading,
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
                    isFirst = isFirst,
                    ancestorHasMore = ancestorHasMore,
                    lineColor = lineColor,
                    rowHeight = rowHeight,
                    loading = loading,
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
    isFirst: Boolean,
    ancestorHasMore: List<Boolean>,
    lineColor: Color,
    rowHeight: Float,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable () -> Unit,
) {
    // Electric-flow phase: 0f..1f looping. The pulse head position along each path is
    // derived from this. Only spun up while loading so idle rows cost nothing. Read
    // .value INSIDE drawBehind so each frame redraws without recomposing the tree.
    val flowPhase = if (loading) {
        val transition = rememberInfiniteTransition(label = "toolFlow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = FLOW_CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "toolFlowPhase",
        )
    } else null

    val drawModifier = Modifier.drawBehind {
        // Butt cap = line ends exactly at endpoint, no overhang. Adjacent rows draw
        // from y=0..height so the verticals meet seamlessly with zero overlap = no dots.
        val strokeButt = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Butt)

        val phase = flowPhase?.value

        // --- Ancestor vertical lines (continuous through the row) ---
        for (i in 0 until depth) {
            if (ancestorHasMore.getOrElse(i) { false }) {
                val x = (i + 1) * INDENT_PX
                val ancestorPath = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x, size.height)
                }
                drawPath(ancestorPath, color = lineColor, style = strokeButt)
                if (phase != null) drawFlowPulse(ancestorPath, phase)
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

        if (phase != null) {
            // Exactly one pulse per branch. The middle row's vertical spine is structural
            // only; animating it created an extra downward flow in addition to the branch.
            drawFlowPulse(connector, phase)
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

/**
 * Draws a travelling "electric current" pulse along [path]: a bright silver→white head
 * with a fading tail, plus a wide low-alpha glow underlay. [phase] (0f..1f, looping)
 * positions the head along the path length. Walks the path once via [PathMeasure] and
 * paints short sub-segments whose brightness ramps toward the head, so the result reads
 * as a comet of current rather than a uniform glowing line.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlowPulse(
    path: Path,
    phase: Float,
) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val total = measure.length
    if (total <= 0f) return

    val pulseLen = (total * FLOW_PULSE_FRACTION).coerceAtLeast(6f)
    // Head sweeps from -pulseLen (just off the top) to total, so the pulse enters and
    // exits cleanly each cycle instead of popping into existence mid-path.
    val headDist = phase * (total + pulseLen) - pulseLen
    val step = pulseLen / FLOW_SEGMENTS

    for (s in 0 until FLOW_SEGMENTS) {
        val segEnd = headDist - s * step
        val segStart = segEnd - step
        if (segEnd <= 0f || segStart >= total) continue
        val a = segStart.coerceIn(0f, total)
        val b = segEnd.coerceIn(0f, total)
        if (b <= a) continue

        val p0 = measure.getPosition(a)
        val p1 = measure.getPosition(b)
        // Brightness ramps from tail (s = last, dim) to head (s = 0, white-hot).
        val t = 1f - s.toFloat() / FLOW_SEGMENTS
        val color = androidx.compose.ui.graphics.lerp(flowSilver, flowWhite, t)
        val alpha = t * t // ease-in so the tail fades faster than it brightens

        // Glow halo: wide, very transparent, drawn first so the core sits on top.
        drawLine(
            color = color.copy(alpha = alpha * 0.25f),
            start = p0,
            end = p1,
            strokeWidth = FLOW_GLOW_WIDTH,
            cap = StrokeCap.Round,
        )
        // Bright core.
        drawLine(
            color = color.copy(alpha = alpha),
            start = p0,
            end = p1,
            strokeWidth = FLOW_CORE_WIDTH,
            cap = StrokeCap.Round,
        )
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
