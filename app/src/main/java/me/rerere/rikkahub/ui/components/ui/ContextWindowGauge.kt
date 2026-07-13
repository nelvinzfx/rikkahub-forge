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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.roundToInt

const val DEFAULT_CONTEXT_LENGTH = 200_000

/**
 * Thin progress bar showing how much of the model's context window is in use,
 * with a light ripple liquid shimmer animation (Var 6 style).
 *
 * A solid base fill carries the color, while a semi-transparent white highlight
 * slides across it from left to right -- like light reflecting on water.
 * The shimmer travels from off-screen left to off-screen right; at both ends
 * it is fully outside the fill area, so the restart boundary is invisible.
 *
 * Used in the chat TopBar so the user can see context pressure at a glance.
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

    // Shimmer slides from off-screen left to off-screen right in 1.5s.
    // LinearEasing = constant velocity. At progress 0 the shimmer is at
    // -shimmerW (invisible, left of fill). At progress 1 it is at +filledW
    // (invisible, right of fill). The restart from 1 back to 0 is invisible
    // because the shimmer is off-screen at both points -- flawless loop.
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

        // Bar with light ripple shimmer
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
                // Base fill -- solid color (the liquid)
                drawRoundRect(
                    color = filledColor,
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )

                // Shimmer highlight -- transparent -> white(0.6) -> transparent.
                // Slides across the fill from left to right. The gradient is
                // 50% of the fill width (matching CSS background-size: 50%),
                // positioned so that at progress=0 it is fully off-screen left
                // and at progress=1 it is fully off-screen right.
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
