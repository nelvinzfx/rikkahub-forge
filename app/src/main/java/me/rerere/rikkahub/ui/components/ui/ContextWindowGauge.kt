package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

const val DEFAULT_CONTEXT_LENGTH = 200_000

/**
 * Thin progress bar showing how much of the model's context window is in use,
 * with a continuous flowing-water gradient animation inside the filled portion.
 *
 * Used in the chat TopBar so the user can see context pressure at a glance.
 * When [contextLength] is unknown (null from provider), callers should pass
 * [DEFAULT_CONTEXT_LENGTH]; a future settings page will let users override it.
 */
@Composable
fun ContextWindowGauge(
    usedTokens: Long,
    contextLength: Int,
    modifier: Modifier = Modifier,
) {
    val safeContextLength = contextLength.coerceAtLeast(1)
    val percentage = (usedTokens.toFloat() / safeContextLength.toFloat()).coerceIn(0f, 1f)
    val percentInt = (percentage * 100f).roundToInt()

    val filledColor = when {
        percentage < 0.5f -> MaterialTheme.colorScheme.primary
        percentage < 0.8f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.error
    }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val subTextColor = LocalContentColor.current.copy(alpha = 0.5f)

    // Single infinite animation drives the flow. LinearEasing gives constant
    // velocity (like water current). shift goes 0 to waveLength per cycle;
    // at waveLength the TileMode.Repeated pattern is identical to shift=0,
    // so the Restart jump is completely invisible -- flawless infinity loop.
    val transition = rememberInfiniteTransition(label = "flow")
    val flowProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow_progress",
    )

    Column(modifier = modifier) {
        // Text row: percentage on the left, token count on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$percentInt%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = filledColor.copy(alpha = 0.7f),
            )
            Text(
                text = "${formatTokens(usedTokens)} / ${formatTokens(safeContextLength.toLong())}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = subTextColor,
            )
        }

        // Bar with flowing water gradient
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            val barW = size.width
            val barH = size.height
            val filledW = barW * percentage
            val cornerR = CornerRadius(barH / 2f, barH / 2f)

            // Background track
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = cornerR,
            )

            if (filledW > 1f) {
                // Base fill -- always visible, dim translucent layer
                drawRoundRect(
                    color = filledColor.copy(alpha = 0.2f),
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )

                // Flowing gradient -- repeating wave that shifts left to right.
                // 5-stop bell curve: dim -> rising -> bright white peak -> falling -> dim.
                // TileMode.Repeated tiles the wave; animating start/end by exactly
                // one waveLength per cycle makes the Restart boundary invisible.
                val waveLength = filledW / 2f
                val shift = flowProgress * waveLength

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            filledColor.copy(alpha = 0.05f),
                            filledColor.copy(alpha = 0.5f),
                            Color.White.copy(alpha = 0.3f),
                            filledColor.copy(alpha = 0.5f),
                            filledColor.copy(alpha = 0.05f),
                        ),
                        startX = shift,
                        endX = shift + waveLength,
                        tileMode = TileMode.Repeated,
                    ),
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )
            }
        }
    }
}

/**
 * Approximate the current context window usage by taking the token totals
 * from the most recent message that has [TokenUsage] data. The last
 * [promptTokens + completionTokens] is the best proxy for "how full the
 * context is right now" without making a separate token-counting API call.
 */
fun computeContextUsage(conversation: Conversation): Long {
    return conversation.currentMessages
        .lastOrNull { it.usage != null }
        ?.let { msg ->
            val u = msg.usage!!
            (u.totalTokens.takeIf { it > 0 } ?: (u.promptTokens + u.completionTokens)).toLong()
        } ?: 0L
}

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
