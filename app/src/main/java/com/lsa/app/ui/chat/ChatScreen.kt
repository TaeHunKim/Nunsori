package com.lsa.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.lsa.app.R
import com.lsa.app.chat.ChatController
import com.lsa.app.chat.ChatPhase
import com.lsa.app.chat.ChatToolNames
import com.lsa.app.chat.PendingAttachment
import com.lsa.app.data.db.ChatMessageEntity
import com.lsa.app.data.db.ChatRole
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.ItemDetailSheet
import com.lsa.app.ui.common.WarningCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun ChatScreen(sessionId: Long, onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: ChatViewModel = viewModel(key = "chat-$sessionId", factory = viewModelFactory { initializer { ChatViewModel(container, sessionId) } })
    val session by vm.session.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val images by vm.supportsImages.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addPhoto) }

    LaunchedEffect(messages.size, run?.partial?.length) {
        val last = messages.size + (if (run != null) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                session?.title?.ifEmpty { null } ?: stringResource(R.string.chat_untitled), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.chat_rename)) }
        }
        // Which tools the model may use in this session.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = session?.enabledTools?.split(',')?.toSet().orEmpty()
            FilterChip(on.contains(ChatToolNames.SEARCH), { vm.toggleTool(ChatToolNames.SEARCH) }, { Text(stringResource(R.string.chat_tool_search)) })
            FilterChip(on.contains(ChatToolNames.READ), { vm.toggleTool(ChatToolNames.READ) }, { Text(stringResource(R.string.chat_tool_read)) })
            FilterChip(session?.autoSearch == true, { vm.toggleAutoSearch() }, { Text(stringResource(R.string.chat_auto_search)) })
        }
        if (!modelReady) WarningCard(stringResource(R.string.chat_model_missing), stringResource(R.string.go_settings), onOpenSettings)

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && run == null) item { Text(stringResource(R.string.chat_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
            items(messages, key = { it.msgId }) { m -> MessageRow(sessionId, m, names, onOpenItem = { detailId = it }) }
            run?.let { r ->
                item(key = "run") {
                    when (r.phase) {
                        ChatPhase.LOADING -> StatusRow(stringResource(R.string.chat_loading_model))
                        ChatPhase.GENERATING -> {
                            if (r.partial.isEmpty()) StatusRow(stringResource(R.string.chat_thinking)) else Bubble(r.partial, mine = false)
                            if (r.trimmed) Text(stringResource(R.string.chat_trimmed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ChatPhase.FAILED -> WarningCard(
                            if (r.error == ChatController.NO_MODEL) stringResource(R.string.chat_model_missing) else stringResource(R.string.chat_error, r.error.orEmpty()),
                            stringResource(R.string.chat_dismiss), vm::dismissError,
                        )
                    }
                }
            }
        }

        // Pending attachments, then the composer.
        if (vm.pending.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.pending.forEach { a ->
                    val label = when (a) {
                        is PendingAttachment.Item -> vm.pendingLabels[a.itemId] ?: "#${a.itemId}"
                        is PendingAttachment.Photo -> stringResource(R.string.chat_photo)
                    }
                    InputChip(
                        selected = false, onClick = {}, label = { Text(label, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.chat_remove_attachment), Modifier.size(18.dp).clickable { vm.removePending(a) }) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.AttachFile, stringResource(R.string.chat_attach_photo))
            }
            OutlinedTextField(
                vm.draft, { vm.draft = it }, Modifier.weight(1f), maxLines = 4,
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            )
            if (busy) {
                IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, stringResource(R.string.chat_stop)) }
            } else {
                IconButton(onClick = vm::send, enabled = modelReady && (vm.draft.isNotBlank() || vm.pending.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send))
                }
            }
        }
        if (vm.pending.any { it is PendingAttachment.Photo } && !images) {
            Text(stringResource(R.string.chat_no_vision), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    if (renaming) RenameDialog(session?.title.orEmpty(), onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    detailId?.let { ItemDetailSheet(it) { detailId = null } }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) { androidx.compose.foundation.text.selection.SelectionContainer { com.lsa.app.ui.common.Markdown(text, Modifier.padding(12.dp)) } }
    }
}

@Composable
private fun MessageRow(sessionId: Long, m: ChatMessageEntity, names: Map<Long, String>, onOpenItem: (Long) -> Unit) {
    when (m.role) {
        ChatRole.USER -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChatViewModel.attachmentsOf(m).forEach { a ->
                if (a.photoFile != null) {
                    AsyncImage(File(androidx.compose.ui.platform.LocalContext.current.filesDir, "chat/$sessionId/${a.photoFile}"), null, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                } else if (a.itemId != null) {
                    AssistChip(onClick = { onOpenItem(a.itemId) }, label = { Text(names[a.itemId] ?: "#${a.itemId}", maxLines = 1) })
                }
            }
            if (m.text.isNotEmpty()) Bubble(m.text, mine = true)
        }
        ChatRole.ASSISTANT -> Bubble(m.text, mine = false)
        ChatRole.TOOL -> ToolCard(m, onOpenItem)
    }
}

/** A tool call the model made: what it asked and, for searches, the files it found (tap to open). */
@Composable
private fun ToolCard(m: ChatMessageEntity, onOpenItem: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val args = remember(m.toolArgs) { runCatching { JSONObject(m.toolArgs ?: "{}") }.getOrDefault(JSONObject()) }
    val found = remember(m.text) {
        runCatching { JSONArray(m.text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    }
    val summary = when (m.toolName) {
        ChatToolNames.SEARCH -> stringResource(R.string.chat_tool_ran_search, args.optString("query"), found.size)
        ChatToolNames.READ -> stringResource(R.string.chat_tool_ran_read, runCatching { JSONObject(m.text).optString("name") }.getOrDefault("").ifEmpty { "#" + args.optLong("item_id") })
        else -> m.toolName.orEmpty()
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary, style = MaterialTheme.typography.labelMedium)
            if (open) {
                found.forEach { o ->
                    Text(
                        "${o.optString("name")}${o.optString("page").takeIf { it.isNotEmpty() }?.let { " · p.$it" } ?: ""}${o.optString("time").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenItem(o.optLong("item_id")) }.padding(vertical = 2.dp),
                    )
                }
                if (found.isEmpty()) Text(m.text.take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
