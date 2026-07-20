package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Shared color picker: HSL sliders plus a free-form text field that accepts hex
 * (#RGB/#ARGB/#RRGGBB/#AARRGGBB), rgb()/rgba() and hsl(). The field and the sliders
 * stay in sync. Used by the theme editor and the chat colors page.
 */
@Composable
fun ColorPickerRow(
    color: Color,
    onColorChange: (Color) -> Unit,
) {
    val hsl = remember(color) {
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(color) { mutableFloatStateOf(hsl[2]) }
    var hslCode by remember(color) { mutableStateOf(formatHslCode(hsl[0], hsl[1], hsl[2])) }
    var hslCodeError by remember(color) { mutableStateOf(false) }

    fun updateColor(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hslCode = formatHslCode(newHue, newSaturation, newLightness)
        hslCodeError = false
        onColorChange(Color(ColorUtils.HSLToColor(floatArrayOf(newHue, newSaturation, newLightness))))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                drawCircle(color = color)
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("H", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = hue,
                        onValueChange = {
                            updateColor(it, saturation, lightness)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = saturation,
                        onValueChange = {
                            updateColor(hue, it, lightness)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("L", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = lightness,
                        onValueChange = {
                            updateColor(hue, saturation, it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OutlinedTextField(
            value = hslCode,
            onValueChange = { value ->
                hslCode = value
                val parsedArgb = parseColorCode(value)
                hslCodeError = parsedArgb == null
                if (parsedArgb != null) {
                    ColorUtils.colorToHSL(parsedArgb, hsl)
                    hue = hsl[0]
                    saturation = hsl[1]
                    lightness = hsl[2]
                    onColorChange(Color(parsedArgb))
                }
            },
            label = { Text("Color") },
            placeholder = { Text("#AARRGGBB, rgba(…), hsl(…)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hslCodeError,
            supportingText = if (hslCodeError) {
                { Text("Try #FF6750A4, rgba(103,80,164,0.5) or hsl(267 36% 48%)") }
            } else {
                null
            },
        )
    }
}

/**
 * Picks a readable text color for the given background [argb] based on relative
 * luminance: near-black on light backgrounds, near-white on dark ones.
 */
fun autoContrastColor(argb: Int): Color =
    if (Color(argb).luminance() > 0.45f) Color(0xFF1B1B1F) else Color(0xFFF4F4F6)

/**
 * Parses a textual color into an ARGB Int. Accepts:
 *  - hex: #RGB, #ARGB, #RRGGBB, #AARRGGBB
 *  - functional rgb/rgba: rgb(103, 80, 164) / rgba(103, 80, 164, 0.5) — alpha 0..1
 *    (fraction) or 0..255
 *  - functional hsl: hsl(267 36% 48%) via [parseHslCode]
 * Returns null when the input matches none of these.
 */
private fun parseColorCode(value: String): Int? {
    val v = value.trim()
    if (v.startsWith("#")) return parseHexColor(v)
    val lower = v.lowercase()
    if (lower.startsWith("rgb")) return parseRgbFunction(lower)
    if (lower.startsWith("hsl")) return parseHslCode(v)?.let { ColorUtils.HSLToColor(it) }
    return null
}

private fun parseHexColor(v: String): Int? {
    val hex = v.removePrefix("#")
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return runCatching {
        when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            4 -> {
                val a = hex[0].toString().repeat(2).toInt(16)
                val r = hex[1].toString().repeat(2).toInt(16)
                val g = hex[2].toString().repeat(2).toInt(16)
                val b = hex[3].toString().repeat(2).toInt(16)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> (0xFF shl 24) or hex.toLong(16).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }.getOrNull()
}

private fun parseRgbFunction(v: String): Int? {
    val nums = hslNumberRegex.findAll(v).mapNotNull { it.value.toFloatOrNull() }.toList()
    if (nums.size !in 3..4) return null
    val r = nums[0].coerceIn(0f, 255f).toInt()
    val g = nums[1].coerceIn(0f, 255f).toInt()
    val b = nums[2].coerceIn(0f, 255f).toInt()
    val a = if (nums.size == 4) {
        val raw = nums[3]
        (if (raw <= 1f) raw * 255f else raw).toInt().coerceIn(0, 255)
    } else {
        0xFF
    }
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private val hslNumberRegex = Regex("""[-+]?\d*\.?\d+""")

private fun parseHslCode(value: String): FloatArray? {
    val values = buildList {
        for (match in hslNumberRegex.findAll(value)) {
            add(match.value.toFloatOrNull() ?: return null)
            if (size == 3) break
        }
    }

    if (values.size != 3) return null

    val hue = values[0].coerceIn(0f, 360f)
    val saturation = parseHslPercentOrFraction(values[1]) ?: return null
    val lightness = parseHslPercentOrFraction(values[2]) ?: return null

    return floatArrayOf(hue, saturation, lightness)
}

private fun parseHslPercentOrFraction(value: Float): Float? {
    if (!value.isFinite()) return null
    return if (value > 1f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }
}

private fun formatHslCode(hue: Float, saturation: Float, lightness: Float): String {
    return "hsl(${hue.roundToInt()} ${(saturation * 100).roundToInt()}% ${(lightness * 100).roundToInt()}%)"
}
