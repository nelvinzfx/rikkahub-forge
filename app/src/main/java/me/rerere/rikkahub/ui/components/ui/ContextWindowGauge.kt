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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.Conversation
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

const val DEFAULT_CONTEXT_LENGTH = 200_000

/**
 * Thin progress bar showing how much of the model's context window is in use,
 * with a continuous soda-fizz bubble animation inside the filled portion.
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

    // Single infinite animation drives all bubble phases. Each bubble derives
    // its position from this value + its own phase offset, so the loop wraps
    // seamlessly (sin(0) = sin(π) = 0 at the boundary — no visible jump).
    val transition = rememberInfiniteTransition(label = "fizz")
    val fizzTime by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fizz_time",
    )

    // Deterministic bubble specs — golden-ratio distribution gives organic spread
    // without kotlin.random.Random (stable across recompositions, no flicker).
    val goldenRatio = 0.61803398875f
    val bubbleSpecs = remember {
        List(16) { i ->
            BubbleSpec(
                xRatio = (i * goldenRatio) % 1f,
                sizeRatio = 0.3f + ((i * 0.13f) % 0.6f),
                phaseOffset = (i * 0.0625f) % 1f,
                driftPx = ((i * 0.17f) % 1f - 0.5f) * 2f,
            )
        }
    }

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

        // Bar with fizz bubbles
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
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
                // Filled portion (translucent base layer)
                drawRoundRect(
                    color = filledColor.copy(alpha = 0.35f),
                    size = Size(filledW, barH),
                    cornerRadius = cornerR,
                )

                // Fizz bubbles — tiny circles rising within the filled portion.
                // phase 0 → 1 maps to bottom → top; sin(phase * π) gives smooth
                // fade-in/fade-out so bubbles appear and disappear organically.
                bubbleSpecs.forEach { spec ->
                    val phase = (fizzTime + spec.phaseOffset) % 1f
                    val visibility = sin(phase * PI).toFloat()
                    if (visibility <= 0f) return@forEach

                    val bubbleR = barH * 0.35f * spec.sizeRatio
                    val xPos = filledW * spec.xRatio + spec.driftPx * phase
                    val yPos = barH * (1f - phase)

                    val clampedX = xPos.coerceIn(bubbleR, filledW - bubbleR)
                    val clampedY = yPos.coerceIn(bubbleR, barH - bubbleR)

                    drawCircle(
                        color = filledColor.copy(alpha = visibility * 0.5f),
                        radius = bubbleR,
                        center = Offset(clampedX, clampedY),
                    )
                }
            }
        }
    }
}

private data class BubbleSpec(
    val xRatio: Float,
    val sizeRatio: Float,
    val phaseOffset: Float,
    val driftPx: Float,
)

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
