package com.lsa.app.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lsa.app.data.db.ChatDao
import com.lsa.app.data.db.ChatMessageEntity
import com.lsa.app.data.db.ChatRole
import com.lsa.app.data.db.ChatSessionEntity
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.ml.ModelResidency
import com.lsa.app.ml.llm.ChatEvent
import com.lsa.app.ml.llm.ChatTool
import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.ml.llm.TurnRole
import com.lsa.app.search.SearchEngine
import com.lsa.app.search.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Something the user attached to a message before sending. */
sealed interface PendingAttachment {
    data class Item(val itemId: Long) : PendingAttachment
    data class Photo(val uri: Uri) : PendingAttachment
}

enum class ChatPhase { LOADING, GENERATING, FAILED }

/** What the chat is doing right now (only one answer is generated at a time). */
data class ChatRun(
    val sessionId: Long,
    val phase: ChatPhase,
    /** The answer so far. */
    val partial: String = "",
    val error: String? = null,
    /** Earlier turns were left out of the model's context because they did not fit. */
    val trimmed: Boolean = false,
)

/** Runs chat turns: stores messages, builds the model's context, calls the model and records tool calls. */
class ChatController(
    private val context: Context,
    private val chat: ChatDao,
    private val items: ItemDao,
    private val residency: ModelResidency,
    private val search: SearchEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _run = MutableStateFlow<ChatRun?>(null)
    val run: StateFlow<ChatRun?> = _run.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    private val queued = HashMap<Long, List<Long>>()

    /** Files chosen elsewhere (Search) to be attached to the next message of [sessionId]. */
    fun queueAttachments(sessionId: Long, itemIds: List<Long>) { synchronized(queued) { queued[sessionId] = itemIds } }

    fun takeQueuedAttachments(sessionId: Long): List<Long> = synchronized(queued) { queued.remove(sessionId) ?: emptyList() }

    suspend fun newSession(tools: Set<String> = ChatToolNames.ALL.toSet()): Long {
        val now = clock()
        return chat.insertSession(ChatSessionEntity(title = "", enabledTools = tools.joinToString(","), autoSearch = false, createdAt = now, updatedAt = now))
    }

    suspend fun deleteSession(id: Long) {
        if (_run.value?.sessionId == id) stop()
        chat.deleteSession(id)
        File(context.filesDir, "chat/$id").deleteRecursively()
    }

    fun send(sessionId: Long, text: String, attachments: List<PendingAttachment> = emptyList()) {
        if (busy || (text.isBlank() && attachments.isEmpty())) return
        job = scope.launch { runTurn(sessionId, text.trim(), attachments) }
    }

    fun stop() { job?.cancel() }

    fun dismissError() { if (_run.value?.phase == ChatPhase.FAILED) _run.value = null }

    private suspend fun runTurn(sessionId: Long, text: String, pending: List<PendingAttachment>) {
        var answer = StringBuilder()
        try {
            val session = chat.session(sessionId) ?: return
            _run.value = ChatRun(sessionId, ChatPhase.LOADING)
            val lease = residency.chat() ?: run {
                _run.value = ChatRun(sessionId, ChatPhase.FAILED, error = NO_MODEL)
                return
            }
            lease.use { l ->
                val model = l.value
                val contextTokens = settings.chatContextTokens.first()
                val custom = settings.chatCustomPrompt.first().trim()

                // Everything the model needs besides the question: attached files, search results, photos.
                val attachmentsJson = JSONArray()
                val extra = StringBuilder()
                val images = ArrayList<ByteArray>()
                for (a in pending) when (a) {
                    is PendingAttachment.Item -> {
                        attachmentsJson.put(JSONObject().put("itemId", a.itemId))
                        extra.append(excerptOf(a.itemId))
                    }
                    is PendingAttachment.Photo -> {
                        val file = savePhoto(sessionId, a.uri) ?: continue
                        attachmentsJson.put(JSONObject().put("photo", file.name))
                        if (model.supportsImages) images += file.readBytes() else extra.append(PHOTO_UNSUPPORTED)
                    }
                }
                if (session.autoSearch && text.isNotBlank()) extra.append(autoSearchBlock(text))
                val modelText = if (extra.isEmpty()) text else "$extra\n$text"

                val firstMessage = chat.messageCount(sessionId) == 0
                chat.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = ChatRole.USER, text = text,
                        attachments = attachmentsJson.takeIf { it.length() > 0 }?.toString(), createdAt = clock(),
                    ),
                )
                if (firstMessage) chat.rename(sessionId, titleOf(text))
                chat.touch(sessionId, clock())

                // History = stored user/assistant turns before this one, trimmed to the context budget.
                val stored = chat.messages(sessionId).dropLast(1).filter { it.role != ChatRole.TOOL }
                    .map { ChatTurn(if (it.role == ChatRole.USER) TurnRole.USER else TurnRole.ASSISTANT, it.text) }
                val budget = (contextTokens * ContextBudget.HISTORY_SHARE).toInt() - ContextBudget.estimateTokens(modelText) - SYSTEM_TOKENS -
                    ContextBudget.estimateTokens(custom)
                val history = ContextBudget.trim(stored, budget.coerceAtLeast(0))

                val tools = enabledTools(session)
                // the custom prompt is part of the key: a changed prompt starts a fresh conversation state
                val key = "$sessionId|${tools.joinToString { it.name }}|${custom.hashCode()}"
                _run.value = ChatRun(sessionId, ChatPhase.GENERATING, trimmed = history.dropped > 0)
                var toolCalls = 0
                model.reply(key, systemPrompt(custom), history.turns, ChatTurn(TurnRole.USER, modelText, images), tools).collect { e ->
                    when (e) {
                        is ChatEvent.Token -> {
                            answer.append(e.text)
                            _run.value = ChatRun(sessionId, ChatPhase.GENERATING, answer.toString(), trimmed = history.dropped > 0)
                        }
                        is ChatEvent.ToolRan -> {
                            chat.insertMessage(
                                ChatMessageEntity(
                                    sessionId = sessionId, role = ChatRole.TOOL, text = ContextBudget.clip(e.result, MAX_STORED_RESULT),
                                    toolName = e.name, toolArgs = e.args, createdAt = clock(),
                                ),
                            )
                            if (++toolCalls > MAX_TOOL_CALLS) throw TooManyToolCalls()
                        }
                    }
                }
            }
            saveAnswer(sessionId, answer.toString())
            _run.value = null
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { saveAnswer(sessionId, answer.toString()); _run.value = null }
            throw e
        } catch (e: TooManyToolCalls) {
            saveAnswer(sessionId, answer.toString().ifBlank { TOO_MANY_CALLS })
            _run.value = null
        } catch (e: Exception) {
            Log.w(TAG, "chat turn failed", e)
            saveAnswer(sessionId, answer.toString())
            _run.value = ChatRun(sessionId, ChatPhase.FAILED, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private class TooManyToolCalls : Exception()

    private suspend fun saveAnswer(sessionId: Long, text: String) {
        if (text.isBlank()) return
        chat.insertMessage(ChatMessageEntity(sessionId = sessionId, role = ChatRole.ASSISTANT, text = text.trim(), createdAt = clock()))
        chat.touch(sessionId, clock())
    }

    private fun enabledTools(session: ChatSessionEntity): List<ChatTool> {
        val on = session.enabledTools.split(',').toSet()
        return buildList {
            if (ChatToolNames.SEARCH in on) add(SearchIndexTool(search, items))
            if (ChatToolNames.READ in on) add(ReadFileTool(items))
        }
    }

    private fun titleOf(text: String) = text.replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "…" }

    private suspend fun excerptOf(itemId: Long): String {
        val item = items.getItem(itemId) ?: return ""
        val body = ContextBudget.clip(ReadFileTool.fullText(items.segmentsOf(itemId)), ATTACHMENT_CHARS)
        return "[Attached file: ${item.displayName} (item_id=$itemId, ${kindName(item.kind)})]\n" +
            (body.ifEmpty { "(no indexed text; ask for details or use the image itself)" }) + "\n\n"
    }

    private suspend fun autoSearchBlock(question: String): String {
        val hits = search.search(question, SearchFilter(), AUTO_SEARCH_RESULTS).hits
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("[Search results from the user's files for this question]\n")
        for (h in hits) {
            val item = items.getItem(h.itemId) ?: continue
            val segs = items.segmentsOf(h.itemId)
            val text = ReadFileTool.fullText(segs.filter { it.page == h.best?.page || h.best?.page == null })
            sb.append("- ${item.displayName} (item_id=${h.itemId}): ").append(ContextBudget.clip(text.replace(Regex("\\s+"), " "), AUTO_SEARCH_CHARS)).append('\n')
        }
        return sb.append('\n').toString()
    }

    private suspend fun savePhoto(sessionId: Long, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "chat/$sessionId").also { it.mkdirs() }
            val bmp = BitmapLoader.decode(context, uri, PHOTO_SIDE)
            try {
                File(dir, "${UUID.randomUUID()}.jpg").also { it.writeBytes(BitmapLoader.toJpeg(bmp, PHOTO_SIDE)) }
            } finally { bmp.recycle() }
        }.onFailure { Log.w(TAG, "could not save the photo", it) }.getOrNull()
    }

    private fun systemPrompt(custom: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock()))
        return "You are the assistant of a private, on-device file search app. Today is $today. " +
            "Answer from the user's own files when the question is about them: use the search_index tool to find files and read_file to read them, " +
            "if those tools are available. Name the files (and page or time) your answer is based on. " +
            "If nothing relevant is found, say so instead of guessing. Keep answers concise and reply in the language the user writes in." +
            (if (custom.isEmpty()) "" else "\n\nThe user's own instructions (follow them unless they conflict with the rules above):\n$custom")
    }

    companion object {
        private const val TAG = "LSA.Chat"
        const val NO_MODEL = "no_model"
        const val MAX_TOOL_CALLS = 4
        private const val MAX_STORED_RESULT = 4000
        private const val ATTACHMENT_CHARS = 3000
        private const val AUTO_SEARCH_RESULTS = 3
        private const val AUTO_SEARCH_CHARS = 800
        private const val SYSTEM_TOKENS = 350
        private const val PHOTO_SIDE = 1024
        private const val PHOTO_UNSUPPORTED = "[A photo is attached, but this chat model cannot see images.]\n\n"
        private const val TOO_MANY_CALLS = "I stopped because I called tools too many times without an answer."
    }
}
