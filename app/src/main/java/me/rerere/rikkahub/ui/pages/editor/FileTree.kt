package me.rerere.rikkahub.ui.pages.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R

@Composable
fun rememberFileIconFont(): FontFamily = remember {
    FontFamily(Font(R.font.file_icons, FontWeight.Normal))
}

/**
 * nvim-tree style flattened file list: folders first (natural sort), depth
 * indent, chevron rotates on expand, nerd font glyphs tinted from the theme.
 */
@Composable
fun FileTreeList(
    nodes: List<FileNode>,
    expanded: Set<String>,
    loadingDirs: Set<String>,
    onRowClick: (FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconFont = rememberFileIconFont()
    LazyColumn(modifier = modifier) {
        items(nodes, key = { it.uri }) { node ->
            FileTreeRow(
                node = node,
                isExpanded = node.uri in expanded,
                isLoading = node.uri in loadingDirs,
                iconFont = iconFont,
                onClick = { onRowClick(node) },
            )
        }
    }
}

@Composable
private fun FileTreeRow(
    node: FileNode,
    isExpanded: Boolean,
    isLoading: Boolean,
    iconFont: FontFamily,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (10 + node.depth * 16).dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (node.isDir) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier
                    .size(13.dp)
                    .rotate(if (isExpanded) 90f else 0f),
                tint = if (isLoading) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.size(13.dp))
        }
        Text(
            text = when {
                node.isDir && isExpanded -> FileIcons.FOLDER_EXPANDED.toString()
                node.isDir -> FileIcons.FOLDER.toString()
                else -> FileIcons.forFileName(node.name).toString()
            },
            fontFamily = iconFont,
            fontSize = 15.sp,
            color = if (node.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
