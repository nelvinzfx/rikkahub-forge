package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.ui.modifier.shimmer

private const val WRITE_SUMMARY_MAX_CHARS = 12 * 1024
private const val WRITE_SUMMARY_MAX_LINES = 20
private const val WRITE_PREVIEW_MAX_CHARS = 64 * 1024
private const val WRITE_PREVIEW_MAX_LINES = 400
private const val EDIT_SUMMARY_MAX_FILES = 6
private const val EDIT_SUMMARY_MAX_DIFF_LINES = 12
private const val EDIT_SUMMARY_MAX_DIFF_CHARS = 12 * 1024
private const val EDIT_PREVIEW_MAX_DIFF_CHARS = 48 * 1024
private const val EDIT_PREVIEW_MAX_DIFF_LINES = 600
private const val EDIT_ERROR_MAX_LINES = 20
private const val EDIT_DIAGNOSTIC_MAX_CHARS = 24 * 1024
private const val EDIT_DIAGNOSTIC_MAX_LINES = 200

internal object TermuxWriteFileToolUI : TermuxWriteToolUI("termux_write_file")
internal object TermuxAppendFileToolUI : TermuxWriteToolUI("termux_append_file")

internal open class TermuxWriteToolUI(
    final override val toolName: String,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    private fun model(context: ToolUIContext): TermuxWriteUIModel? =
        parseTermuxWriteUIModel(
            toolName,
            context.arguments,
            context.content,
            rawInput = context.tool.input,
        )

    override fun status(context: ToolUIContext): ToolStepStatus {
        val model = model(context) ?: return ToolStepStatus.NONE
        return when {
            model.error != null -> ToolStepStatus.ERROR
            model.bytesWritten != null -> ToolStepStatus.SUCCESS
            else -> ToolStepStatus.NONE
        }
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = model(context)?.path ?: context.arguments.getStringContent("path")
        val append = toolName == "termux_append_file"
        return when {
            append && path != null -> stringResource(R.string.tool_ui_termux_append_file, path)
            append -> stringResource(R.string.tool_ui_termux_append_file_default)
            path != null -> stringResource(R.string.tool_ui_write_file, path)
            else -> stringResource(R.string.tool_ui_write_file_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = model(context) != null

    override fun hasInlineParams(context: ToolUIContext): Boolean = false

    override fun hasInlineResult(context: ToolUIContext): Boolean = false

    @Composable
    override fun Summary(context: ToolUIContext) {
        val model = remember(context) { model(context) } ?: return
        val preview = remember(model.content) {
            boundedTextPreview(model.content, WRITE_SUMMARY_MAX_CHARS, WRITE_SUMMARY_MAX_LINES)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TermuxBadgeRow(model.badges)
            WriteResultLine(model)
            LineNumberedSyntaxPreview(
                text = preview.text,
                path = model.path,
                loading = context.loading,
            )
            PreviewOverflowFooter(
                visible = preview.truncated,
                shownLines = physicalLineCount(preview.text),
                totalLines = physicalLineCount(model.content),
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val model = remember(context) { model(context) }
        if (model == null) {
            BoundedFallbackPreview(context)
            return
        }
        val preview = remember(model.content) {
            boundedTextPreview(model.content, WRITE_PREVIEW_MAX_CHARS, WRITE_PREVIEW_MAX_LINES)
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = model.actualPath ?: model.path,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            TermuxBadgeRow(model.badges)
            WriteResultLine(model)
            HighlightCodeBlock(
                code = preview.text,
                language = languageOf(model.path),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal object TermuxEditFileToolUI : TermuxEditToolUI("termux_edit_file")
internal object TermuxEditFilesToolUI : TermuxEditToolUI("termux_edit_files")

internal open class TermuxEditToolUI(
    final override val toolName: String,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    private fun metadataDiff(context: ToolUIContext): String? =
        context.tool.output.firstNotNullOfOrNull { it.metadataAs<DiffMetadata>()?.diff }

    private fun model(context: ToolUIContext): TermuxEditUIModel? =
        parseTermuxEditUIModel(toolName, context.arguments, context.content, metadataDiff(context))

    override fun status(context: ToolUIContext): ToolStepStatus {
        val model = model(context) ?: return ToolStepStatus.NONE
        return when {
            model.error != null -> ToolStepStatus.ERROR
            model.files.any { it.error != null } -> ToolStepStatus.ERROR
            TermuxUIBadge.ROLLBACK_FAILED in model.badges -> ToolStepStatus.ERROR
            model.state == "applied" || model.state == "dry_run" || model.state == "no_change" ->
                ToolStepStatus.SUCCESS
            else -> ToolStepStatus.NONE
        }
    }

    @Composable
    override fun LabelExtras(context: ToolUIContext) {
        val model = remember(context) { model(context) } ?: return
        val diff = model.diff ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        if (stats.additions == 0 && stats.deletions == 0) return
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (stats.additions > 0) DiffStatPill("+${stats.additions}", DiffAddedColor)
            if (stats.deletions > 0) DiffStatPill("-${stats.deletions}", DiffRemovedColor)
        }
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val model = model(context)
        if (model?.single == false) {
            return stringResource(R.string.tool_ui_termux_edit_files, model.files.size)
        }
        val path = model?.files?.singleOrNull()?.path ?: context.arguments.getStringContent("path")
        return if (path != null) {
            stringResource(R.string.tool_ui_edit_file, path)
        } else {
            stringResource(R.string.tool_ui_edit_file_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = model(context) != null

    override fun hasInlineParams(context: ToolUIContext): Boolean = false

    override fun hasInlineResult(context: ToolUIContext): Boolean = false

    @Composable
    override fun Summary(context: ToolUIContext) {
        val model = remember(context) { model(context) } ?: return
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TermuxBadgeRow(model.badges)
            if (model.single) {
                model.files.singleOrNull()?.let { EditFileSummaryRow(it) }
            } else {
                model.files.take(EDIT_SUMMARY_MAX_FILES).forEach { EditFileSummaryRow(it) }
                if (model.files.size > EDIT_SUMMARY_MAX_FILES) {
                    Text(
                        text = stringResource(
                            R.string.tool_ui_termux_more_files,
                            model.files.size - EDIT_SUMMARY_MAX_FILES,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            model.diff?.let { diff ->
                val preview = remember(diff) {
                    boundedTextPreview(diff, EDIT_SUMMARY_MAX_DIFF_CHARS, EDIT_SUMMARY_MAX_DIFF_LINES)
                }
                if (preview.text.isNotEmpty()) {
                    DiffView(
                        diff = preview.text,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = EDIT_SUMMARY_MAX_DIFF_LINES,
                        showFileHeader = false,
                    )
                }
            }
            listOfNotNull(model.error, model.detail).joinToString("\n").takeIf(String::isNotEmpty)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val model = remember(context) { model(context) }
        if (model == null) {
            BoundedFallbackPreview(context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (model.single) {
                    model.files.singleOrNull()?.let { it.actualPath ?: it.path } ?: toolName
                } else {
                    stringResource(R.string.tool_ui_termux_edit_files, model.files.size)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            TermuxBadgeRow(model.badges)
            ErrorDetails(model.error, model.detail)
            val hasPerFileDiffs = model.files.any { it.diff != null }
            val diffSource = if (hasPerFileDiffs) model.files.map { it.diff } else listOf(model.diff)
            val boundedDiffs = remember(diffSource) {
                boundedDiffPreviews(diffSource, EDIT_PREVIEW_MAX_DIFF_CHARS, EDIT_PREVIEW_MAX_DIFF_LINES)
            }
            val diagnosticGroups = remember(model.files) {
                model.files.map { file -> listOfNotNull(file.error) + file.diagnostics }
            }
            val boundedDiagnostics = remember(diagnosticGroups) {
                boundedJoinedPreviews(diagnosticGroups, EDIT_DIAGNOSTIC_MAX_CHARS, EDIT_DIAGNOSTIC_MAX_LINES)
            }
            model.files.forEachIndexed { index, file ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditFileSummaryRow(file)
                    if (hasPerFileDiffs) {
                        boundedDiffs.previews[index]?.let { BoundedDiffPreview(it) }
                    }
                    boundedDiagnostics.previews[index]?.let { preview ->
                        ErrorDetails(null, preview.text.takeIf(String::isNotEmpty))
                    }
                }
            }
            if (!hasPerFileDiffs) {
                boundedDiffs.previews.singleOrNull()?.let { BoundedDiffPreview(it) }
            }
        }
    }
}

@Composable
private fun BoundedFallbackPreview(context: ToolUIContext) {
    val arguments = remember(context.arguments) {
        boundedTextPreview(context.arguments.toString(), 32 * 1024, 300)
    }
    val result = remember(context.content) {
        context.content?.toString()?.let { boundedTextPreview(it, 32 * 1024, 300) }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(context.tool.toolName, style = MaterialTheme.typography.titleMedium)
        HighlightCodeBlock(arguments.text, "json", Modifier.fillMaxWidth())
        result?.let { HighlightCodeBlock(it.text, "json", Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun BoundedDiffPreview(preview: BoundedTextPreview) {
    if (preview.text.isNotEmpty()) {
        DiffView(diff = preview.text, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorDetails(error: String?, detail: String?) {
    val text = listOfNotNull(error, detail).joinToString("\n").takeIf(String::isNotEmpty) ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = EDIT_ERROR_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EditFileSummaryRow(file: TermuxEditFileUIModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = file.actualPath ?: file.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        file.diff?.let { diff ->
            val stats = remember(diff) { parseDiffStats(diff) }
            Text(text = "+${stats.additions}", style = MaterialTheme.typography.labelSmall, color = DiffAddedColor)
            Text(text = "-${stats.deletions}", style = MaterialTheme.typography.labelSmall, color = DiffRemovedColor)
        }
        TermuxBadgeRow(file.badges)
    }
}

@Composable
private fun WriteResultLine(model: TermuxWriteUIModel) {
    val line = when {
        model.error != null -> listOfNotNull(
            model.error,
            model.detail,
            model.currentSha256?.let { "sha256: $it" },
            model.recovery,
        ).joinToString("\n")
        model.bytesWritten != null && model.totalBytes != null -> stringResource(
            R.string.tool_ui_termux_bytes_written,
            formatTermuxBytes(model.bytesWritten),
            formatTermuxBytes(model.totalBytes),
        )
        model.bytesWritten != null -> stringResource(
            R.string.tool_ui_termux_bytes,
            formatTermuxBytes(model.bytesWritten),
        )
        else -> null
    }
    line?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = if (model.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (model.error != null) 8 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LineNumberedSyntaxPreview(
    text: String,
    path: String?,
    loading: Boolean,
) {
    val lineCount = remember(text) { physicalLineCount(text) }
    val gutterWidth = remember(lineCount) { lineCount.toString().length }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .shimmer(isLoading = loading),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = (1..lineCount).joinToString("\n") { it.toString().padStart(gutterWidth) },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                softWrap = false,
            )
            Text(
                text = "  ",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
            HighlightText(
                code = text,
                language = languageOf(path),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PreviewOverflowFooter(
    visible: Boolean,
    shownLines: Int,
    totalLines: Int,
) {
    if (!visible) return
    val more = (totalLines - shownLines).coerceAtLeast(0)
    Text(
        text = stringResource(R.string.tool_ui_termux_preview_more, more, totalLines),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DiffStatPill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private val HIDDEN_BADGES = setOf(TermuxUIBadge.ERROR, TermuxUIBadge.TRUNCATED)

@Composable
private fun TermuxBadgeRow(badges: List<TermuxUIBadge>) {
    val visible = badges.filterNot { it in HIDDEN_BADGES }
    if (visible.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.distinct().forEach { TermuxBadge(it) }
    }
}

@Composable
private fun TermuxBadge(badge: TermuxUIBadge) {
    val (label, foreground, background) = when (badge) {
        TermuxUIBadge.APPLIED -> Triple(R.string.tool_ui_termux_applied, DiffAddedColor, DiffAddedColor.copy(alpha = 0.14f))
        TermuxUIBadge.DRY_RUN -> Triple(R.string.tool_ui_termux_dry_run, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        TermuxUIBadge.NO_CHANGE -> Triple(R.string.tool_ui_termux_no_change, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        TermuxUIBadge.ROLLED_BACK -> Triple(R.string.tool_ui_termux_rolled_back, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        TermuxUIBadge.ROLLBACK_FAILED -> Triple(R.string.tool_ui_termux_rollback_failed, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        TermuxUIBadge.ERROR, TermuxUIBadge.TRUNCATED -> return
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private fun formatTermuxBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
