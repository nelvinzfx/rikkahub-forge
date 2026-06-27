package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01

private const val BRANCH_MID = "├── "
private const val BRANCH_END = "└── "
private const val PIPE = "│   "
private const val SPACE = "    "

/**
 * Tree-style JSON parameter display for tool calls.
 * Renders like the Unix `tree` command with ├── │ └── box-drawing characters.
 * Skips empty objects/arrays/blank values entirely.
 * Fast typing animation when [loading] is true.
 */
@Composable
fun ToolParamTree(
    element: JsonElement,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    // Skip empty root
    if (element is JsonObject && element.isEmpty()) return
    if (element is JsonArray && element.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
    childPrefix: String,
    loading: Boolean,
) {
    when (value) {
        is JsonObject -> {
            if (value.isEmpty()) return
            var expanded by remember(key, prefix) { mutableStateOf(true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 0.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prefix + branch,
                    style = treeStyle,
                    color = treeColor,
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = " $key",
                    style = treeKeyStyle,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    TreeChildren(value, prefix = childPrefix, loading = loading)
                }
            }
        }

        is JsonArray -> {
            if (value.isEmpty()) return
            var expanded by remember(key, prefix) { mutableStateOf(true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 0.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prefix + branch,
                    style = treeStyle,
                    color = treeColor,
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = " $key (${value.size})",
                    style = treeKeyStyle,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    TreeChildren(value, prefix = childPrefix, loading = loading)
                }
            }
        }

        is JsonPrimitive -> {
            val content = value.contentOrNull ?: ""
            if (content.isBlank()) return

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = prefix + branch,
                    style = treeStyle,
                    color = treeColor,
                )
                Text(
                    text = "$key: ",
                    style = treeKeyStyle,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TypingText(
                    text = content,
                    loading = loading,
                    style = treeValueStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Fast typing animation: reveals text character by character when loading.
 * ~8ms per char — fast but visible. Full text immediately when not loading.
 */
@Composable
private fun TypingText(
    text: String,
    loading: Boolean,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    if (!loading || text.length <= 1) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val visibleCount by produceState(initialValue = 1, text) {
        for (i in 1..text.length) {
            value = i
            delay(8)
        }
    }

    Text(
        text = text.take(visibleCount),
        style = style,
        color = color,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

private val treeColor
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

private val treeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

private val treeKeyStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

private val treeValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

private fun isEmptyValue(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.isEmpty()
    is JsonArray -> element.isEmpty()
    is JsonPrimitive -> element.contentOrNull.isNullOrBlank()
    else -> false
}
