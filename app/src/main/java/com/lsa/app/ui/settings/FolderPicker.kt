package com.lsa.app.ui.settings

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsa.app.R
import com.lsa.app.data.media.FolderInfo
import com.lsa.app.ui.common.displayFolder
import java.io.File

/**
 * With "all files access" the user browses the real directory tree; without it only the folders MediaStore knows
 * to contain photos are offered.
 */
@Composable
fun FolderPickerDialog(
    allFilesAccess: Boolean,
    photoFolders: List<FolderInfo>,
    selected: Set<String>,
    onAdd: (relativePath: String, recursive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_picker_title)) },
        text = {
            if (allFilesAccess) Browser(selected, onAdd) else PhotoFolders(photoFolders, selected, onAdd)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}

@Composable
private fun Browser(selected: Set<String>, onAdd: (String, Boolean) -> Unit) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var current by remember { mutableStateOf(root) }
    val children = remember(current) {
        current.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && !(current == root && f.name == "Android") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
    }
    val rel = relative(root, current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { current.parentFile?.takeIf { current != root }?.let { current = it } }, enabled = current != root) {
                Icon(Icons.Filled.ArrowUpward, stringResource(R.string.folder_up))
            }
            Text(displayFolder(rel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        val already = rel in selected
        OutlinedButton(onClick = { onAdd(rel, true) }, enabled = !already) {
            Text(stringResource(if (already) R.string.folder_already_added else R.string.folder_select_this))
        }
        if (children.isEmpty()) {
            Text(stringResource(R.string.folder_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(children, key = { it.absolutePath }) { dir ->
                Row(Modifier.fillMaxWidth().clickable { current = dir }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Text(dir.name, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

private fun relative(root: File, dir: File): String {
    val r = dir.relativeTo(root).path
    return if (r.isEmpty() || r == ".") "" else "$r/"
}

@Composable
private fun PhotoFolders(folders: List<FolderInfo>, selected: Set<String>, onAdd: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.folder_picker_photos_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(folders, key = { it.relativePath }) { f ->
                val already = f.relativePath in selected
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !already) { onAdd(f.relativePath, false) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayFolder(f.relativePath))
                        Text(
                            pluralStringResource(R.plurals.image_count, f.imageCount, f.imageCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (already) Text(stringResource(R.string.folder_already_added), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
