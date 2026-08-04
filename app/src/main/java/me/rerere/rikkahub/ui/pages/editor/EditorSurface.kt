package me.rerere.rikkahub.ui.pages.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Typeface
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CancelSquare
import me.rerere.rikkahub.R

/**
 * The sora CodeEditor wrapped for Compose. A single editor instance is kept
 * alive across tab switches; each tab owns its Content document, so undo
 * history and cursor state survive switching. Dirty tracking goes through
 * [EditorVM.onContentChanged] with a swap guard so programmatic setText during
 * tab switches is not counted as an edit.
 */
@Composable
fun EditorSurface(
    tab: EditorTab,
    darkTheme: Boolean,
    vm: EditorVM,
    onEditorChange: (CodeEditor?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editor by remember { mutableStateOf<CodeEditor?>(null) }

    AndroidView(
        factory = { ctx ->
            EditorTextMate.ensureInitialized(ctx)
            CodeEditor(ctx).apply {
                typefaceText = Typeface.createFromAsset(ctx.assets, "fonts/JetBrainsMono-Regular.ttf")
                EditorTextMate.applyTheme(this, darkTheme)
                setText(tab.content)
                EditorTextMate.languageFor(tab.name)?.let { setEditorLanguage(it) }
                isEditable = !tab.readOnly
                subscribeEvent(ContentChangeEvent::class.java) { _, _ -> vm.onContentChanged() }
                editor = this
                onEditorChange(this)
            }
        },
        update = { view ->
            EditorTextMate.applyTheme(view, darkTheme)
            editor = view
            onEditorChange(view)
        },
        onRelease = { view ->
            view.release()
            onEditorChange(null)
        },
        modifier = modifier,
    )

    LaunchedEffect(tab.uri) {
        editor?.let { ed ->
            vm.swapGuard.set(true)
            ed.setText(tab.content)
            ed.setEditorLanguage(EditorTextMate.languageFor(tab.name))
            ed.isEditable = !tab.readOnly
            vm.swapGuard.set(false)
        }
    }
}

@Composable
fun EditorTabsBar(
    tabs: List<EditorTab>,
    activeUri: String?,
    onSelect: (String) -> Unit,
    onClose: (EditorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconFont = rememberFileIconFont()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab.uri == activeUri
            Surface(
                onClick = { onSelect(tab.uri) },
                shape = MaterialTheme.shapes.small,
                color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = FileIcons.forFileName(tab.name),
                        fontFamily = iconFont,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    if (tab.dirty) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                    Icon(
                        imageVector = HugeIcons.CancelSquare,
                        contentDescription = stringResource(R.string.code_editor_close_tab),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onClose(tab) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
