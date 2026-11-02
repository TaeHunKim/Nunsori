package com.lsa.app.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.chat.ChatController
import com.lsa.app.chat.ChatRun
import com.lsa.app.chat.ChatToolNames
import com.lsa.app.chat.PendingAttachment
import com.lsa.app.data.db.ChatMessageEntity
import com.lsa.app.data.db.ChatSessionEntity
import com.lsa.app.models.ModelCatalog
import com.lsa.app.models.ModelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

/** A file or photo attached to a message, as shown in the UI. */
data class AttachmentChip(val itemId: Long? = null, val photoFile: String? = null, val label: String)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer, val sessionId: Long) : ViewModel() {
    private val dao = c.db.chatDao()
    private val controller: ChatController = c.chat

    val session: StateFlow<ChatSessionEntity?> = dao.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val messages: StateFlow<List<ChatMessageEntity>> = dao.observeMessages(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val run: StateFlow<ChatRun?> = controller.run.map { r -> r?.takeIf { it.sessionId == sessionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** True while any chat session is generating (one model, one answer at a time). */
    val busy: StateFlow<Boolean> = controller.run.map { it != null && it.phase != com.lsa.app.chat.ChatPhase.FAILED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val taskDao = c.db.taskDao()
    /** Scheduled tasks of this chat (confirmed ones: active, paused, finished). */
    val tasks: StateFlow<List<com.lsa.app.data.db.ScheduledTaskEntity>> = taskDao.observeOfSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun task(id: Long) = taskDao.observe(id)
    fun confirmTask(id: Long) { viewModelScope.launch { c.taskManager.confirm(id) } }
    fun deleteTask(id: Long) { viewModelScope.launch { c.taskManager.delete(id) } }
    fun action(id: Long) = c.db.actionDao().observe(id)
    /** Approve a card: [start] hands the intent to a system screen and returns false when no app can take it. */
    fun approveAction(id: Long, start: (android.content.Intent) -> Boolean) {
        viewModelScope.launch {
            when (val r = c.actions.intentFor(id)) {
                is com.lsa.app.chat.actions.ApprovalResult.Launch -> if (start(r.intent)) c.actions.markDone(id) else c.actions.markFailed(id, "no_app")
                is com.lsa.app.chat.actions.ApprovalResult.Done -> c.actions.markDone(id, r.note)
                is com.lsa.app.chat.actions.ApprovalResult.Failed -> Unit
            }
        }
    }
    fun dismissAction(id: Long) { viewModelScope.launch { c.actions.dismiss(id) } }
    fun pauseTask(id: Long, paused: Boolean) { viewModelScope.launch { c.taskManager.setPaused(id, paused) } }

    val modelReady: StateFlow<Boolean> = combine(c.settings.chatModelId, c.modelManager.states) { id, st ->
        ModelCatalog.byId(id)?.let { st[id] is ModelState.Installed } == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val supportsImages: StateFlow<Boolean> = c.settings.chatModelId.map { ModelCatalog.byId(it)?.vision == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    var draft by mutableStateOf("")
    /** Attachments waiting for the next send. */
    var pending by mutableStateOf<List<PendingAttachment>>(emptyList())
        private set
    var pendingLabels by mutableStateOf<Map<Long, String>>(emptyMap())
        private set

    /** Names of files referenced by the messages and pending attachments. */
    val names: StateFlow<Map<Long, String>> = messages.flatMapLatest { msgs ->
        kotlinx.coroutines.flow.flow {
            val ids = msgs.flatMap { attachmentsOf(it).mapNotNull { a -> a.itemId } }.distinct()
            emit(if (ids.isEmpty()) emptyMap() else c.db.itemDao().summaries(ids).associate { it.itemId to it.displayName })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // Files chosen in Search ("Ask in chat") arrive as pending attachments.
        val queued = controller.takeQueuedAttachments(sessionId)
        if (queued.isNotEmpty()) viewModelScope.launch {
            pending = queued.map { PendingAttachment.Item(it) }
            pendingLabels = c.db.itemDao().summaries(queued).associate { it.itemId to it.displayName }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() && pending.isEmpty()) return
        controller.send(sessionId, text, pending)
        draft = ""; pending = emptyList()
    }

    fun stop() = controller.stop()
    fun dismissError() = controller.dismissError()
    fun addPhoto(uri: Uri) { pending = pending + PendingAttachment.Photo(uri) }
    fun removePending(a: PendingAttachment) { pending = pending - a }

    fun toggleTool(name: String) = viewModelScope.launch {
        val s = dao.session(sessionId) ?: return@launch
        val on = s.enabledTools.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!on.add(name)) on.remove(name)
        dao.setTools(sessionId, on.joinToString(","), s.autoSearch)
    }

    fun toggleAutoSearch() = viewModelScope.launch {
        val s = dao.session(sessionId) ?: return@launch
        dao.setTools(sessionId, s.enabledTools, !s.autoSearch)
    }

    fun rename(title: String) = viewModelScope.launch { dao.rename(sessionId, title.trim().ifEmpty { return@launch }) }

    companion object {
        val TOOLS = ChatToolNames.ALL

        fun attachmentsOf(m: ChatMessageEntity): List<AttachmentChip> {
            val json = m.attachments ?: return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    if (o.has("itemId")) AttachmentChip(itemId = o.getLong("itemId"), label = "") else AttachmentChip(photoFile = o.getString("photo"), label = "")
                }
            }.getOrDefault(emptyList())
        }
    }
}
