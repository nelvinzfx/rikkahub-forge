package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTypographyTest {
    @Test
    fun `selected font family covers the complete material type scale`() {
        val selected = FontFamily.Serif
        val typography = typographyForFontFamily(selected)

        val styles = listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
            typography.displayLargeEmphasized,
            typography.displayMediumEmphasized,
            typography.displaySmallEmphasized,
            typography.headlineLargeEmphasized,
            typography.headlineMediumEmphasized,
            typography.headlineSmallEmphasized,
            typography.titleLargeEmphasized,
            typography.titleMediumEmphasized,
            typography.titleSmallEmphasized,
            typography.bodyLargeEmphasized,
            typography.bodyMediumEmphasized,
            typography.bodySmallEmphasized,
            typography.labelLargeEmphasized,
            typography.labelMediumEmphasized,
            typography.labelSmallEmphasized,
        )

        styles.forEach { style -> assertEquals(selected, style.fontFamily) }
    }

    @Test
    fun `explicit code font remains an override`() {
        val typography = typographyForFontFamily(FontFamily.Serif)

        val codeStyle = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

        assertEquals(FontFamily.Monospace, codeStyle.fontFamily)
    }
}
