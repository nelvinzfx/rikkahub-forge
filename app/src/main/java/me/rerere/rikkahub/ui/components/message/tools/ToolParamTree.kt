package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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

/**
 * Tree-style JSON parameter display for tool calls.
 * Skips empty objects/arrays/blank values entirely.
 * Fast typing animation when [loading] is true.
 */
@Composable
fun ToolParamTree(
    element: JsonElement,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    // Skip empty root object
    if (element is JsonObject && element.isEmpty()) return
    if (element is JsonArray && element.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        JsonTreeNodes(element, loading = loading, depth = 0)
    }
}

@Composable
private fun JsonTreeNodes(
    element: JsonElement,
    loading: Boolean,
    depth: Int,
) {
    when (element) {
        is JsonObject -> {
            element.entries
                .filterNot { (_, v) -> isEmptyValue(v) }
                .forEach { (key, value) ->
                    JsonTreeNode(key = key, value = value, loading = loading, depth = depth)
                }
        }
        is JsonArray -> {
            element.filterNot { isEmptyValue(it) }
                .forEachIndexed { index, value ->
                    JsonTreeNode(key = "[$index]", value = value, loading = loading, depth = depth)
                }
        }
        else -> {}
    }
}

@Composable
private fun JsonTreeNode(
    key: String,
    value: JsonElement,
    loading: Boolean,
    depth: Int,
) {
    val indent = (depth * 14).dp

    when (value) {
        is JsonObject -> {
            if (value.isEmpty()) return
            var expanded by remember(key, depth) { mutableStateOf(depth == 0) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 1.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    JsonTreeNodes(value, loading = loading, depth = depth + 1)
                }
            }
        }

        is JsonArray -> {
            if (value.isEmpty()) return
            var expanded by remember(key, depth) { mutableStateOf(depth == 0) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 1.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$key (${value.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    JsonTreeNodes(value, loading = loading, depth = depth + 1)
                }
            }
        }

        is JsonPrimitive -> {
            val content = value.contentOrNull ?: ""
            if (content.isBlank()) return

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent + 14.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "$key:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TypingText(
                    text = content,
                    loading = loading,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
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
            delay(8) // fast typing
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

private fun isEmptyValue(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.isEmpty()
    is JsonArray -> element.isEmpty()
    is JsonPrimitive -> element.contentOrNull.isNullOrBlank()
    else -> false
}
