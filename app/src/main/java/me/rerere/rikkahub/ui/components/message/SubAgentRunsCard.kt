package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject

/**
 * Wraps all sub-agent dispatches of one assistant turn into a single live card
 * (instead of one pill per dispatch). The card observes SubAgentRegistry, whose
 * usage ticker keeps tokens/tool calls fresh while runs are non-terminal.
 * Tap opens a bottom sheet with one row per worker; tapping a row jumps into
 * that worker's conversation.
 */

private val StateGreen = Color(0xFF7FCF8E)
private val StateAmber = Color(0xFFE3C26B)
private val StateOrange = Color(0xFFF0A35E)

/** Pull dispatched run ids out of a subagent_dispatch(_batch/_continue) tool output. */
internal fun subAgentRunIdsFromTool(tool: UIMessagePart.Tool): List<String> {
    if (!tool.toolName.startsWith("subagent_dispatch")) return emptyList()
    if (!tool.isExecuted) return emptyList()
    val text = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    if (text.isBlank()) return emptyList()
    return runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        buildList {
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { add(it) }
            obj["run_ids"]?.jsonArray?.forEach { el ->
                el.jsonPrimitive.contentOrNull?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())
}

private fun nameHue(name: String): Int {
    var h = 0
    for (c in name) h = (h * 31 + c.code) ushr 0
    return ((h % 360) + 360) % 360
}

private fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }

private fun fmtTokens(n: Long): String =
    if (n >= 1000) {
        val v = n / 1000f
        if (v >= 100) "${v.toInt()}k"
        else "%.1f".format(java.util.Locale.US, v).removeSuffix(".0") + "k"
    } else n.toString()

private fun statusColor(status: SubAgentStatus): Color = when (status) {
    SubAgentStatus.RUNNING -> StateGreen
    SubAgentStatus.STOPPING -> StateAmber
    SubAgentStatus.SUCCEEDED -> StateGreen
    SubAgentStatus.FAILED -> Color(0xFFF2B8B5)
    SubAgentStatus.TIMED_OUT -> StateOrange
    SubAgentStatus.PENDING, SubAgentStatus.CANCELLED -> Color(0xFFCAC4D0)
}

@Composable
private fun statusLabel(status: SubAgentStatus): String = when (status) {
    SubAgentStatus.PENDING -> stringResource(R.string.sub_agents_state_pending)
    SubAgentStatus.RUNNING -> stringResource(R.string.sub_agents_state_running)
    SubAgentStatus.STOPPING -> stringResource(R.string.sub_agents_state_stopping)
    SubAgentStatus.SUCCEEDED -> stringResource(R.string.sub_agents_state_succeeded)
    SubAgentStatus.FAILED -> stringResource(R.string.sub_agents_state_failed)
    SubAgentStatus.TIMED_OUT -> stringResource(R.string.sub_agents_state_timed_out)
    SubAgentStatus.CANCELLED -> stringResource(R.string.sub_agents_state_cancelled)
}

@Composable
private fun activityLine(run: SubAgentRun): String = when (run.status) {
    SubAgentStatus.PENDING -> stringResource(R.string.sub_agents_line_pending)
    SubAgentStatus.RUNNING -> run.progressNote ?: stringResource(R.string.sub_agents_line_working)
    SubAgentStatus.STOPPING -> stringResource(R.string.sub_agents_line_stopping)
    SubAgentStatus.SUCCEEDED -> stringResource(R.string.sub_agents_line_finished)
    SubAgentStatus.FAILED -> stringResource(R.string.sub_agents_line_error, run.error ?: "")
    SubAgentStatus.TIMED_OUT -> stringResource(R.string.sub_agents_line_timed_out)
    SubAgentStatus.CANCELLED -> stringResource(R.string.sub_agents_line_cancelled)
}

@Composable
private fun SubAgentAvatar(label: String, status: SubAgentStatus?, size: Int, withBadge: Boolean) {
    val hue = nameHue(label)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.hsl(hue.toFloat(), 0.45f, 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(label),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.36f).sp,
        )
        if (withBadge && status != null) {
            val badgeColor = statusColor(status)
            val badgeText = when (status) {
                SubAgentStatus.SUCCEEDED -> "✓"
                SubAgentStatus.FAILED -> "✕"
                SubAgentStatus.TIMED_OUT -> "!"
                SubAgentStatus.CANCELLED -> "–"
                else -> null
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.38f).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                if (badgeText != null) {
                    Text(badgeText, color = Color(0xFF141218), fontSize = (size * 0.2f).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentRunsCard(
    runIds: List<String>,
    modifier: Modifier = Modifier,
) {
    val registry: SubAgentRegistry = koinInject()
    val allRuns by registry.runs.collectAsStateWithLifecycle()
    val runs = runIds.mapNotNull { allRuns[it] }
    val navController = LocalNavController.current
    var sheetOpen by remember { mutableStateOf(false) }

    val active = runs.count { !it.status.isTerminal() }
    val running = runs.filter { it.status == SubAgentStatus.RUNNING }
    val sumIn = runs.sumOf { it.tokensIn }
    val sumOut = runs.sumOf { it.tokensOut }
    val sumTools = runs.sumOf { it.toolCalls }

    val subtitle = when {
        runs.isEmpty() -> stringResource(R.string.sub_agents_history_expired)
        active > 0 && running.isNotEmpty() ->
            stringResource(R.string.sub_agents_active_count, active) + " · " +
                (running.last().progressNote ?: stringResource(R.string.sub_agents_line_working))
        active > 0 -> stringResource(R.string.sub_agents_active_count, active)
        else -> stringResource(R.string.sub_agents_done_summary, runs.size, sumTools)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { sheetOpen = true },
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                // avatar stack
                Box {
                    runs.take(3).forEachIndexed { index, run ->
                        Box(Modifier.offset(x = (index * 26).dp)) {
                            SubAgentAvatar(run.label, run.status, 34, withBadge = false)
                        }
                    }
                    if (runs.size > 3) {
                        Box(Modifier.offset(x = (3 * 26).dp)) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+${runs.size - 3}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // reserve width for the stack (3 avatars + optional +N chip)
                    val stackWidth = when {
                        runs.isEmpty() -> 34
                        runs.size > 3 -> 3 * 26 + 34
                        else -> (runs.size - 1) * 26 + 34
                    }
                    Box(Modifier.size(width = stackWidth.dp, height = 34.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sub_agents_title, runs.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.sub_agents_tokens, fmtTokens(sumIn), fmtTokens(sumOut)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        HugeIcons.ArrowUp01,
                        contentDescription = stringResource(R.string.sub_agents_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (active > 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Text(
                text = stringResource(R.string.sub_agents_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.padding(bottom = 18.dp)) {
                items(runs, key = { it.id }) { run ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sheetOpen = false
                                run.conversationId
                                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                                    ?.let { navigateToChatPage(navController, it) }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        SubAgentAvatar(run.label, run.status, 40, withBadge = true)
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(run.label, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = statusColor(run.status).copy(alpha = 0.18f),
                                ) {
                                    Text(
                                        text = statusLabel(run.status),
                                        color = statusColor(run.status),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = activityLine(run),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.sub_agents_tokens, fmtTokens(run.tokensIn), fmtTokens(run.tokensOut)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.sub_agents_tool_calls, run.toolCalls),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
