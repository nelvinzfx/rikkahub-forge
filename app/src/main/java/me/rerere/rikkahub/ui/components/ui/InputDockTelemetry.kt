package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

const val DEFAULT_CONTEXT_LENGTH = 200_000

/**
 * Cache usage summed over a conversation's stored per-message usage records.
 *
 * promptTokens == 0 means no response in this conversation ever reported usage
 * (or the provider never reports token usage at all), and the dock renders a
 * dash instead of a misleading 0%.
 */
data class ConversationCacheUsage(
    val cachedTokens: Long,
    val promptTokens: Long,
) {
    val rate: Float?
        get() = if (promptTokens > 0) cachedTokens.toFloat() / promptTokens else null
}

/**
 * Cache hit rate for the conversation's active branch: sum of cached prompt
 * tokens over all prompt tokens reported by every stored response. Old
 * conversations get the number retroactively because usage is persisted in
 * each message; regenerated branches only count the selected variant.
 */
fun computeCacheHitRate(conversation: Conversation): ConversationCacheUsage {
    var cached = 0L
    var prompt = 0L
    conversation.currentMessages.forEach { message ->
        val usage = message.usage ?: return@forEach
        prompt += usage.promptTokens
        cached += usage.cachedTokens
    }
    return ConversationCacheUsage(cachedTokens = cached, promptTokens = prompt)
}

/**
 * Compact telemetry pill pinned above the chat input: cache hit rate on the
 * left, context window pressure on the right with a slim fill bar below.
 *
 * Replaces the old ContextWindowGauge that lived under the top bar, keeping
 * its liquid shimmer (Var 6 style): a translucent white highlight sweeps
 * across the filled span so the gauge reads as flowing water. The whole block
 * sits on a rounded surfaceContainerLow backdrop so the mono data text never
 * fights with surrounding chat content, and the bar is inset inside that
 * backdrop instead of stretching to the screen edges. The context side
 * carries the pressure color (neutral under 60%, amber over 60%, error over
 * 85%).
 */
@Composable
fun InputDockTelemetry(
    usedTokens: Long,
    contextLength: Int,
    cachedTokens: Long,
    promptTokens: Long,
    modifier: Modifier = Modifier,
) {
    val safeContextLength = contextLength.coerceAtLeast(1)
    val ctxFraction = (usedTokens.toFloat() / safeContextLength).coerceIn(0f, 1f)
    val ctxPercentInt = (ctxFraction * 100f).roundToInt()

    val cacheRate = if (promptTokens > 0L) cachedTokens.toFloat() / promptTokens else null
    val cachePercent = cacheRate?.let { rate ->
        val pct = rate * 100f
        // 99.96% must not round up to a lying "100.0%"
        if (pct > 99.949f && pct < 100f) 99.9f else pct
    }

    val ctxColor = when {
        ctxFraction >= 0.85f -> MaterialTheme.colorScheme.error
        ctxFraction >= 0.60f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val telemetryStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )

    // Liquid shimmer (Var 6): the highlight slides from off-screen left to
    // off-screen right in 1s at constant velocity. At progress 0 it is at
    // -shimmerW (invisible, left of fill), at progress 1 it is at +filledW
    // (invisible, right of fill), so the restart boundary is invisible.
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_pos",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (cachePercent == null) {
                    "cache -"
                } else {
                    "cache " + "%.1f".format(cachePercent) + "% · " +
                        formatTokens(cachedTokens) + " / " + formatTokens(promptTokens)
                },
                style = telemetryStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "ctx " + ctxPercentInt + "% · " +
                    formatTokens(usedTokens) + " / " + formatTokens(safeContextLength.toLong()),
                style = telemetryStyle,
                color = ctxColor,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(3.dp)
        ) {
            val barW = size.width
            val barH = size.height
            val filledW = barW * ctxFraction
            val cornerR = CornerRadius(barH / 2f, barH / 2f)

            // Background track
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = cornerR,
            )

            if (filledW > 1f) {
                // Base fill, the liquid
                drawRoundRect(
                    color = ctxColor,
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )

                // Shimmer highlight: transparent -> white(0.6) -> transparent,
                // sweeping across the fill. The gradient is 50% of the fill
                // width, positioned fully off-screen at both ends of the sweep.
                val shimmerW = filledW * 0.5f
                val travelRange = filledW + shimmerW
                val shimmerStart = -shimmerW + shimmerProgress * travelRange
                val shimmerEnd = shimmerStart + shimmerW

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.6f),
                            Color.Transparent,
                        ),
                        startX = shimmerStart,
                        endX = shimmerEnd,
                    ),
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )
            }
        }
    }
}

/** Latest provider-measured context plus locally estimated unmeasured messages. */
fun computeContextUsage(conversation: Conversation): Long =
    TokenBudgetTracker.projectedContextTokens(conversation)

private fun formatTokens(tokens: Long): String = when {
    tokens < 1000 -> tokens.toString()
    tokens < 1_000_000 -> {
        val v = tokens / 1000.0
        if (v == v.toInt().toDouble()) "${v.toInt()}k"
        else "${"%.1f".format(v)}k"
    }
    else -> {
        val v = tokens / 1_000_000.0
        if (v == v.toInt().toDouble()) "${v.toInt()}m"
        else "${"%.1f".format(v)}m"
    }
}
