package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/** One compaction surface: auto settings plus a manual trigger for the same engine. */
@Composable
fun ContextCompactionDialog(
    assistant: Assistant,
    contextLength: Int,
    usedTokens: Long,
    onUpdateAssistant: (Assistant) -> Unit,
    onDismiss: () -> Unit,
    onCompactNow: (additionalInstructions: String) -> Job,
) {
    var instructions by remember { mutableStateOf("") }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) onDismiss()
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_page_compaction_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.chat_page_compaction_usage,
                            usedTokens,
                            contextLength,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.chat_page_compaction_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_auto_title),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = stringResource(R.string.chat_page_compress_auto_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = assistant.autoCompactionEnabled,
                            onCheckedChange = {
                                onUpdateAssistant(assistant.copy(autoCompactionEnabled = it))
                            },
                        )
                    }

                    var contextWindowText by remember(assistant.autoCompactionContextWindow) {
                        mutableStateOf(assistant.autoCompactionContextWindow.toString())
                    }
                    OutlinedTextField(
                        value = contextWindowText,
                        onValueChange = { input ->
                            contextWindowText = input.filter(Char::isDigit)
                            contextWindowText.toIntOrNull()?.takeIf { it > 0 }?.let {
                                onUpdateAssistant(assistant.copy(autoCompactionContextWindow = it))
                            }
                        },
                        label = { Text(stringResource(R.string.chat_page_compaction_context_window)) },
                        supportingText = {
                            Text(stringResource(R.string.chat_page_compaction_context_window_desc))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var reserveText by remember(assistant.autoCompactionReserveTokens) {
                        mutableStateOf(assistant.autoCompactionReserveTokens.toString())
                    }
                    OutlinedTextField(
                        value = reserveText,
                        onValueChange = { input ->
                            reserveText = input.filter(Char::isDigit)
                            reserveText.toIntOrNull()?.takeIf { it > 0 }?.let {
                                onUpdateAssistant(assistant.copy(autoCompactionReserveTokens = it))
                            }
                        },
                        label = { Text(stringResource(R.string.chat_page_compress_auto_reserve_tokens)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var keepText by remember(assistant.autoCompactionKeepRecentTokens) {
                        mutableStateOf(assistant.autoCompactionKeepRecentTokens.toString())
                    }
                    OutlinedTextField(
                        value = keepText,
                        onValueChange = { input ->
                            keepText = input.filter(Char::isDigit)
                            keepText.toIntOrNull()?.takeIf { it >= 0 }?.let {
                                onUpdateAssistant(assistant.copy(autoCompactionKeepRecentTokens = it))
                            }
                        },
                        label = { Text(stringResource(R.string.chat_page_compress_auto_keep_recent_tokens)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                        placeholder = { Text(stringResource(R.string.chat_page_compress_additional_prompt_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = { currentJob?.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = { currentJob = onCompactNow(instructions) }) {
                    Text(stringResource(R.string.chat_page_compact_now))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
