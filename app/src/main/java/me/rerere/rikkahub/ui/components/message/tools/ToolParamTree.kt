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
import androidx.compose.ui.platform.LocalContext
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

private const val BRANCH_MID = "├── "
private const val BRANCH_END = "└── "
private const val PIPE = "│   "
private const val SPACE = "    "
private const val MAX_VALUE_LINES = 15
private const val MAX_TREE_HEIGHT = 300

private val treeFont = FontFamily.Monospace
private const val treeFontSize = 11f
private const val treeLineHeight = 12f

private val treeStyle = SpanStyle(
    fontFamily = treeFont,
    fontSize = treeFontSize.sp,
)

/**
 * Tree-style JSON parameter display for tool calls.
 * Renders like the Unix `tree` command with ├── │ └── box-drawing characters.
 * Multi-line values get proper │ continuation prefix on each wrapped line.
 * Skips empty objects/arrays/blank values entirely.
 * Fast typing animation when [loading] is true.
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
        TreeChildren(element, prefix = "", loading = loading)
    }
}

@Composable
private fun TreeChildren(
    element: JsonElement,
    prefix: String,
    loading: Boolean,
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
        val branch = if (isLast) BRANCH_END else BRANCH_MID
        val childPrefix = prefix + (if (isLast) SPACE else PIPE)

        TreeNode(
            key = key,
            value = value,
            prefix = prefix,
            branch = branch,
            isLast = isLast,
            childPrefix = childPrefix,
            loading = loading,
        )
    }
}

@Composable
private fun TreeNode(
    key: String,
    value: JsonElement,
    prefix: String,
    branch: String,
    isLast: Boolean,
    childPrefix: String,
    loading: Boolean,
) {
    val branchColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val keyColor = MaterialTheme.colorScheme.secondary
    val valColor = MaterialTheme.colorScheme.onSurfaceVariant

    when (value) {
        is JsonObject -> {
            if (value.isEmpty()) return
            var expanded by remember(key, prefix) { mutableStateOf(true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(treeStyle.copy(color = branchColor)) {
                            append(prefix + branch)
                        }
                        withStyle(treeStyle.copy(color = keyColor)) {
                            append(" $key")
                        }
                    },
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                TreeChildren(value, prefix = childPrefix, loading = loading)
            }
        }

        is JsonArray -> {
            if (value.isEmpty()) return
            var expanded by remember(key, prefix) { mutableStateOf(true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(treeStyle.copy(color = branchColor)) {
                            append(prefix + branch)
                        }
                        withStyle(treeStyle.copy(color = keyColor)) {
                            append(" $key (${value.size})")
                        }
                    },
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                TreeChildren(value, prefix = childPrefix, loading = loading)
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
            // Continuation prefix: │ or spaces, aligned with where value text starts
            val contPrefix = prefix + (if (isLast) SPACE else PIPE)
            val padAfterBranch = " ".repeat(key.length + 2) // align after "key: "

            val annotated = buildAnnotatedString {
                // First line: prefix + branch + "key: " + value
                withStyle(treeStyle.copy(color = branchColor)) {
                    append(prefix + branch)
                }
                withStyle(treeStyle.copy(color = keyColor)) {
                    append("$key: ")
                }
                withStyle(treeStyle.copy(color = valColor)) {
                    append(lines.first())
                }
                // Continuation lines: contPrefix + padding + line
                for (i in 1 until lines.size) {
                    append("\n")
                    withStyle(treeStyle.copy(color = branchColor)) {
                        append(contPrefix + padAfterBranch)
                    }
                    withStyle(treeStyle.copy(color = valColor)) {
                        append(lines[i])
                    }
                }
                // Truncation indicator
                if (truncated) {
                    append("\n")
                    withStyle(treeStyle.copy(color = branchColor)) {
                        append(contPrefix + padAfterBranch)
                    }
                    withStyle(treeStyle.copy(color = valColor)) {
                        append("... (${allLines.size - MAX_VALUE_LINES} more lines, view details)")
                    }
                }
            }

            // Typing animation: reveal char by char when loading
            if (loading && annotated.length > 1) {
                val visibleCount by produceState(initialValue = 1, annotated) {
                    for (i in 1..annotated.length) {
                        this.value = i
                        delay(8)
                    }
                }
                val count = visibleCount.coerceIn(1, annotated.length)
                Text(
                    text = annotated.subSequence(0, count),
                    style = treeStyle.toTextStyle(),
                    maxLines = 50,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("value", content))
                                toaster.show("Copied", type = ToastType.Success)
                            },
                        ),
                )
            } else {
                Text(
                    text = annotated,
                    style = treeStyle.toTextStyle(),
                    maxLines = 50,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("value", content))
                                toaster.show("Copied", type = ToastType.Success)
                            },
                        ),
                )
            }
        }
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
