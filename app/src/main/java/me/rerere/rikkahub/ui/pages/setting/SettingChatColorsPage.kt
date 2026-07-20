package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ColorPickerRow
import me.rerere.rikkahub.ui.components.ui.autoContrastColor
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Chat-specific color customization. Lives outside the theme editor on purpose: these
 * colors are personal preference, not theme properties — switching themes keeps them.
 * Every field is nullable; null means "theme default" (and for text colors, an
 * auto-contrast of the matching bubble color when only the bubble is customized).
 */
@Composable
fun SettingChatColorsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scheme = MaterialTheme.colorScheme
    val chatColors = settings.chatColors

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_chat_colors_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ChatColorSection(
                        titleRes = R.string.setting_theme_page_chat_background_color,
                        argb = chatColors.backgroundArgb,
                        fallback = scheme.background,
                        onChange = { v ->
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(backgroundArgb = v)))
                        },
                        onReset = {
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(backgroundArgb = null)))
                        },
                    )
                    ChatColorSection(
                        titleRes = R.string.setting_theme_page_user_bubble_color,
                        argb = chatColors.userBubbleArgb,
                        fallback = scheme.primaryContainer,
                        onChange = { v ->
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(userBubbleArgb = v)))
                        },
                        onReset = {
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(userBubbleArgb = null)))
                        },
                    )
                    ChatColorSection(
                        titleRes = R.string.setting_theme_page_user_bubble_text_color,
                        argb = chatColors.userBubbleTextArgb,
                        fallback = chatColors.userBubbleArgb?.let { autoContrastColor(it.toInt()) }
                            ?: scheme.onPrimaryContainer,
                        onChange = { v ->
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(userBubbleTextArgb = v)))
                        },
                        onReset = {
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(userBubbleTextArgb = null)))
                        },
                    )
                    ChatColorSection(
                        titleRes = R.string.setting_theme_page_assistant_bubble_color,
                        argb = chatColors.assistantBubbleArgb,
                        fallback = scheme.surfaceContainerHigh,
                        onChange = { v ->
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(assistantBubbleArgb = v)))
                        },
                        onReset = {
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(assistantBubbleArgb = null)))
                        },
                    )
                    ChatColorSection(
                        titleRes = R.string.setting_theme_page_assistant_bubble_text_color,
                        argb = chatColors.assistantBubbleTextArgb,
                        fallback = chatColors.assistantBubbleArgb?.let { autoContrastColor(it.toInt()) }
                            ?: scheme.onSurface,
                        onChange = { v ->
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(assistantBubbleTextArgb = v)))
                        },
                        onReset = {
                            vm.updateSettings(settings.copy(chatColors = chatColors.copy(assistantBubbleTextArgb = null)))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatColorSection(
    titleRes: Int,
    argb: Long?,
    fallback: Color,
    onChange: (Long) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (argb != null) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.setting_chat_colors_reset))
                }
            }
        }
        ColorPickerRow(
            color = argb?.let { Color(it.toInt()) } ?: fallback,
            onColorChange = { onChange(it.toArgb().toLong() and 0xFFFFFFFFL) },
        )
    }
}
