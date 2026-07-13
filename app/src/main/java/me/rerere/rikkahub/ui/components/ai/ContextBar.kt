package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ContextBar(
    contextLength: Int?,
    tokensUsed: Long,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val error = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val hasContext = contextLength != null && contextLength > 0
    val fraction = if (hasContext) {
        (tokensUsed.toFloat() / contextLength!!.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100f).roundToInt()

    val transition = rememberInfiniteTransition(label = "context_bar")

    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "glow_alpha",
    )
    val glowWidth by transition.animateFloat(
        initialValue = 6f, targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "glow_width",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "shimmer",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor),
        ) {
            val density = LocalDensity.current
            val trackPx = constraints.maxWidth.toFloat()

            if (hasContext) {
                val fillPx = trackPx * fraction
                val fillDp = with(density) { fillPx.toDp() }

                Box(
                    modifier = Modifier
                        .width(fillDp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(primary, secondary, error),
                                start = Offset(0f, 0f),
                                end = Offset(trackPx, 0f),
                            ),
                        ),
                )

                if (fillPx > 0f) {
                    val sweepCenter = (shimmer * 2f - 0.5f) * trackPx
                    val sweepHalf = trackPx * 0.12f
                    Box(
                        modifier = Modifier
                            .width(fillDp)
                            .fillMaxHeight()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.35f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(sweepCenter - sweepHalf, 0f),
                                    end = Offset(sweepCenter + sweepHalf, 0f),
                                ),
                            ),
                    )
                }

                if (fillPx > 1f) {
                    val glowWidthDp = glowWidth.dp
                    val glowWidthPx = with(density) { glowWidthDp.toPx() }
                    val heightPx = with(density) { 5.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset(x = with(density) { (fillPx - glowWidthPx / 2f).toDp() })
                            .width(glowWidthDp)
                            .height(5.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        error.copy(alpha = glowAlpha),
                                        error.copy(alpha = glowAlpha * 0.3f),
                                        Color.Transparent,
                                    ),
                                    center = Offset(glowWidthPx / 2f, heightPx / 2f),
                                    radius = glowWidthPx / 2f,
                                ),
                            ),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(pulse)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(primary, secondary, primary),
                                start = Offset(0f, 0f),
                                end = Offset(trackPx, 0f),
                            ),
                        ),
                )
            }
        }

        Text(
            text = if (hasContext) {
                "${formatCompact(tokensUsed)} / ${formatCompact(contextLength!!.toLong())} ($percent%)"
            } else {
                "${formatCompact(tokensUsed)} tokens"
            },
            fontSize = 10.sp,
            color = labelColor,
        )
    }
}

private fun formatCompact(value: Long): String = when {
    value >= 1_000_000L -> {
        val major = value / 1_000_000L
        val minor = (value % 1_000_000L) / 100_000L
        if (minor == 0L) "${major}M" else "$major.$minor M"
    }
    value >= 1_000L -> {
        val major = value / 1_000L
        val minor = (value % 1_000L) / 100L
        if (minor == 0L) "${major}K" else "$major.$minor K"
    }
    else -> value.toString()
}
