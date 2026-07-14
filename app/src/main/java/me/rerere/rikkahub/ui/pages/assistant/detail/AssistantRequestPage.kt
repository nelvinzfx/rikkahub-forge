package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantRequestPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_request))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantRequestContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
internal fun AssistantRequestContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Auto-compaction settings
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Auto-Compaction") },
                    description = {
                        Text("Automatically compress conversation when context window fills up. Triggers after each assistant response when token usage exceeds the set percentage of the context window.")
                    },
                    tail = {
                        Switch(
                            checked = assistant.autoCompactionEnabled,
                            onCheckedChange = {
                                onUpdate(assistant.copy(autoCompactionEnabled = it))
                            }
                        )
                    }
                )

                if (assistant.autoCompactionEnabled) {
                    HorizontalDivider()

                    // Context window input
                    FormItem(
                        label = { Text("Context Window") },
                        description = { Text("The total context window of your model (in tokens). Used to calculate when to trigger compaction.") },
                    ) {
                        var contextWindowText by remember(assistant.autoCompactionContextWindow) {
                            mutableStateOf(assistant.autoCompactionContextWindow.toString())
                        }
                        OutlinedTextField(
                            value = contextWindowText,
                            onValueChange = { input ->
                                contextWindowText = input.filter { it.isDigit() }
                                contextWindowText.toIntOrNull()?.let { value ->
                                    if (value > 0) {
                                        onUpdate(assistant.copy(autoCompactionContextWindow = value))
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Trigger percentage slider
                    FormItem(
                        label = { Text("Trigger at") },
                        description = { Text("Start compaction when context reaches this percentage of the window.") },
                    ) {
                        Column {
                            Slider(
                                value = assistant.autoCompactionTriggerPercent.toFloat(),
                                onValueChange = {
                                    onUpdate(assistant.copy(autoCompactionTriggerPercent = it.toInt().coerceIn(50, 95)))
                                },
                                valueRange = 50f..95f,
                                steps = 8,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${assistant.autoCompactionTriggerPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        CustomHeaders(
            headers = assistant.customHeaders,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customHeaders = it
                    )
                )
            }
        )

        HorizontalDivider()

        CustomBodies(
            customBodies = assistant.customBodies,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customBodies = it
                    )
                )
            }
        )
    }
}
