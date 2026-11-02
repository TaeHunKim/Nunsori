package com.lsa.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.data.db.ChatSessionEntity
import com.lsa.app.rememberContainer
import kotlinx.coroutines.launch

/** All chat sessions, pinned first then newest, with pin, rename and delete. */
@Composable
fun ChatListScreen(onOpen: (Long) -> Unit) {
    val container = rememberContainer()
    val dao = container.db.chatDao()
    val sessions by dao.observeSessions().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val scheduled by container.db.taskDao().observeScheduledSessions().collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            Text(
                stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.sessionId }) { s ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s.sessionId) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.pinned) {
                                Icon(Icons.Filled.PushPin, stringResource(R.string.chat_pinned), Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            if (s.sessionId in scheduled) {
                                Icon(Icons.Filled.Schedule, stringResource(R.string.chat_scheduled), Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.tertiary)
                            }
                            Text(s.title.ifEmpty { stringResource(R.string.chat_untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            android.text.format.DateUtils.getRelativeTimeSpanString(s.updatedAt).toString(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.chat_menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (s.pinned) R.string.chat_unpin else R.string.chat_pin)) },
                                onClick = { menu = false; scope.launch { dao.setPinned(s.sessionId, !s.pinned) } },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_rename)) }, onClick = { menu = false; renaming = s })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete)) }, onClick = { menu = false; deleting = s })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        ExtendedFloatingActionButton(
            onClick = { scope.launch { onOpen(container.chat.newSession()) } },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.chat_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    renaming?.let { s -> RenameDialog(s.title, onDismiss = { renaming = null }) { title -> scope.launch { dao.rename(s.sessionId, title) }; renaming = null } }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_body, s.title.ifEmpty { stringResource(R.string.chat_untitled) })) },
            confirmButton = { TextButton(onClick = { scope.launch { container.chat.deleteSession(s.sessionId) }; deleting = null }) { Text(stringResource(R.string.chat_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.dialog_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
