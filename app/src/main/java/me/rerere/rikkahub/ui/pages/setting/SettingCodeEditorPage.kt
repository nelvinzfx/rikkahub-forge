package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Code Editor (Beta) settings sub-page. The on/off flag lives here instead of a
 * direct switch on the main settings list, so future editor options (hidden
 * files, grammar set, icon tint) can join this page later.
 */
@Composable
fun SettingCodeEditorPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.code_editor_title))
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
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = {
                            vm.updateSettings(settings.copy(codeEditorEnabled = !settings.codeEditorEnabled))
                        },
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        headlineContent = { Text(stringResource(R.string.code_editor_enable_title)) },
                        supportingContent = { Text(stringResource(R.string.code_editor_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.codeEditorEnabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(codeEditorEnabled = it))
                                },
                                size = SwitchSize.Small
                            )
                        },
                    )
                    item(
                        onClick = {
                            vm.updateSettings(settings.copy(codeEditorShowHidden = !settings.codeEditorShowHidden))
                        },
                        leadingContent = { Icon(HugeIcons.View, null) },
                        headlineContent = { Text(stringResource(R.string.code_editor_show_hidden)) },
                        supportingContent = { Text(stringResource(R.string.code_editor_show_hidden_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.codeEditorShowHidden,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(codeEditorShowHidden = it))
                                },
                                size = SwitchSize.Small
                            )
                        },
                    )
                }
            }
        }
    }
}
