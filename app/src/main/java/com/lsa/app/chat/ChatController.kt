package com.lsa.app.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lsa.app.data.db.ChatDao
import com.lsa.app.data.db.ChatMessageEntity
import com.lsa.app.data.db.ChatRole
import com.lsa.app.data.db.ChatSessionEntity
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ScheduledTaskEntity
import com.lsa.app.chat.actions.ActionManager
import com.lsa.app.chat.actions.AddCalendarEventTool
import com.lsa.app.chat.actions.AddContactTool
import com.lsa.app.chat.actions.DeleteFilesTool
import com.lsa.app.chat.actions.MoveFilesTool
import com.lsa.app.chat.schedule.TaskManager
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.BitmapLoader
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private val residency: com.lsa.app.ml.ModelHost,
    private val search: SearchEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val taskManager: TaskManager? = null,
    private val actions: ActionManager? = null,
    /** Named people from face grouping (search_index / move_files / delete_files `person`). */
    private val people: PeopleDirectory? = null,
) {
    private val _run = MutableStateFlow<ChatRun?>(null)
    val run: StateFlow<ChatRun?> = _run.asStateFlow()
    private var job: Job? = null

    /** One answer at a time: user turns and scheduled runs take turns on this lock. */
    private val turnLock = Mutex()
    @Volatile private var scheduledRunning = false

    val busy: Boolean get() = job?.isActive == true || scheduledRunning

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
        job = scope.launch { turnLock.withLock { runTurn(sessionId, text.trim(), attachments) } }
    }

    fun stop() { job?.cancel() }

    fun dismissError() { if (_run.value?.phase == ChatPhase.FAILED) _run.value = null }

    /** The outcome of one turn: the answer text and the error, if any. */
    data class TurnResult(val answer: String, val error: String? = null)

    /**
     * Runs one scheduled task in its chat and returns the answer. Waits for a running answer to finish first.
     * [extraContext] carries what triggered it (for file tasks: the new files).
     */
    suspend fun runScheduled(task: ScheduledTaskEntity, extraContext: String = ""): TurnResult = turnLock.withLock {
        scheduledRunning = true
        try {
            runTurn(task.sessionId, task.instruction, emptyList(), task, extraContext)
        } finally { scheduledRunning = false }
    }

    /**
     * Asks the chat model which of [files] (lines `- item_id=N name: excerpt`) satisfy [condition]; returns the raw reply,
     * or null when no model could be loaded. Used to check scheduled "new file" tasks before anything is posted; the chat
     * itself is untouched.
     */
    suspend fun judgeFiles(condition: String, files: String): String? = turnLock.withLock {
        scheduledRunning = true
        try {
            val lease = residency.chat() ?: return@withLock null
            lease.use { l ->
                val sb = StringBuilder()
                val prompt = (if (" / " in condition) "Condition: files about or related to any of these: $condition." else "Condition: files about or related to \"$condition\".") + "\n\nFiles:\n$files"
                l.value.reply("judge|${clock()}", JUDGE_SYSTEM, emptyList(), ChatTurn(TurnRole.USER, prompt), emptyList()).collect { e ->
                    if (e is ChatEvent.Token) sb.append(e.text)
                }
                sb.toString()
            }
        } finally { scheduledRunning = false }
    }

    private suspend fun runTurn(
        sessionId: Long, text: String, pending: List<PendingAttachment>,
        task: ScheduledTaskEntity? = null, extraContext: String = "",
    ): TurnResult {
        var answer = StringBuilder()
        try {
            val session = chat.session(sessionId) ?: return TurnResult("", "no_chat")
            _run.value = ChatRun(sessionId, ChatPhase.LOADING)
            val lease = residency.chat() ?: run {
                _run.value = ChatRun(sessionId, ChatPhase.FAILED, error = NO_MODEL)
                return TurnResult("", NO_MODEL)
            }
            // Hold the embedding model for the whole turn: otherwise the idle policy can unload it while the LLM is
            // still thinking and the search tool (or auto search) would have to reload it, or fail, mid-answer.
            val needsEmbedder = task != null || ChatToolNames.SEARCH in session.enabledTools.split(',') || session.autoSearch
            val embedderLease = if (needsEmbedder) runCatching { residency.embedder() }.getOrNull() else null
            (embedderLease ?: AutoCloseable { }).use { lease.use { l ->
                val model = l.value
                val contextTokens = residency.chatContextTokens() // set in OndolSaem with the chat model
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
                if (task != null) extra.append(SCHEDULED_NOTE).append(if (actions != null) SCHEDULED_ACTIONS_NOTE else "").append(extraContext).append('\n')
                else if (session.autoSearch && text.isNotBlank()) extra.append(autoSearchBlock(text))
                // The clock changes every turn, so it rides with the message instead of the (cached) system prompt.
                val modelText = "[Current time: ${nowStamp()}]\n" + if (extra.isEmpty()) text else "$extra\n$text"

                val firstMessage = chat.messageCount(sessionId) == 0
                chat.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = ChatRole.USER, text = text,
                        attachments = attachmentsJson.takeIf { it.length() > 0 }?.toString(), taskId = task?.taskId, createdAt = clock(),
                    ),
                )
                if (firstMessage && task == null) chat.rename(sessionId, titleOf(text))
                chat.touch(sessionId, clock())

                // History = stored user/assistant turns before this one, trimmed to the context budget.
                val stored = chat.messages(sessionId).dropLast(1).filter { it.role != ChatRole.TOOL }
                    .map { ChatTurn(if (it.role == ChatRole.USER) TurnRole.USER else TurnRole.ASSISTANT, it.text) }
                val tools = if (task != null) scheduledTools(session) else enabledTools(session)
                // named people go into the system prompt when the model can search by person
                val names = if (tools.any { it.name == ChatToolNames.SEARCH }) runCatching { people?.names() }.getOrNull().orEmpty().take(MAX_NAMES) else emptyList()
                val budget = (contextTokens * ContextBudget.HISTORY_SHARE).toInt() - ContextBudget.estimateTokens(modelText) - SYSTEM_TOKENS -
                    ContextBudget.estimateTokens(custom) - ContextBudget.estimateTokens(names.joinToString())
                val history = ContextBudget.trim(stored, budget.coerceAtLeast(0))

                // the custom prompt is part of the key: a changed prompt starts a fresh conversation state
                // and so is the date: the system prompt names today, so a new day must not reuse yesterday's state
                // ... and so are the known names
                val key = "$sessionId|${tools.joinToString { it.name }}|${custom.hashCode()}|${todayString()}|${names.hashCode()}"
                _run.value = ChatRun(sessionId, ChatPhase.GENERATING, trimmed = history.dropped > 0)
                var toolCalls = 0
                model.reply(key, systemPrompt(custom, tools.any { it.name == ChatToolNames.CALENDAR }, tools.any { it.name == ChatToolNames.MOVE }, names), history.turns, ChatTurn(TurnRole.USER, modelText, images), tools).collect { e ->
                    when (e) {
                        is ChatEvent.Token -> {
                            answer.append(e.text)
                            _run.value = ChatRun(sessionId, ChatPhase.GENERATING, answer.toString(), trimmed = history.dropped > 0)
                        }
                        is ChatEvent.ToolRan -> {
                            chat.insertMessage(
                                ChatMessageEntity(
                                    sessionId = sessionId, role = ChatRole.TOOL, text = ContextBudget.clip(e.result, MAX_STORED_RESULT),
                                    toolName = e.name, toolArgs = e.args, taskId = task?.taskId ?: scheduledIdOf(e.name, e.result), actionId = actionIdOf(e.name, e.result), createdAt = clock(),
                                ),
                            )
                            if (++toolCalls > MAX_TOOL_CALLS) throw TooManyToolCalls()
                        }
                    }
                }
            } }
            saveAnswer(sessionId, answer.toString(), task?.taskId)
            _run.value = null
            return TurnResult(answer.toString().trim())
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { saveAnswer(sessionId, answer.toString(), task?.taskId); _run.value = null }
            throw e
        } catch (e: TooManyToolCalls) {
            val text = answer.toString().ifBlank { TOO_MANY_CALLS }
            saveAnswer(sessionId, text, task?.taskId)
            _run.value = null
            return TurnResult(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "chat turn failed", e)
            saveAnswer(sessionId, answer.toString(), task?.taskId)
            val msg = e.message ?: e.javaClass.simpleName
            // a scheduled run has nobody to dismiss the error; the failure is recorded on the task instead
            _run.value = if (task != null) null else ChatRun(sessionId, ChatPhase.FAILED, error = msg)
            return TurnResult(answer.toString().trim(), msg)
        }
    }

    /** The `task_id` a schedule_task call returned, so its card can be found from the stored tool message. */
    private fun scheduledIdOf(tool: String, result: String): Long? =
        if (tool != ChatToolNames.SCHEDULE && tool != ChatToolNames.UPDATE_TASK) null else runCatching { JSONObject(result).optLong("task_id", -1) }.getOrNull()?.takeIf { it > 0 }

    /** The `action_id` of a proposal (calendar event, contact), so its card can be found from the stored tool message. */
    private fun actionIdOf(tool: String, result: String): Long? =
        if (tool !in ChatToolNames.ACTION_TOOLS) null else runCatching { JSONObject(result).optLong("action_id", -1) }.getOrNull()?.takeIf { it > 0 }

    /** Search and read, plus the calendar/contact/file tools when the chat has them on: they only leave cards for the user to approve. */
    private fun scheduledTools(session: ChatSessionEntity): List<ChatTool> {
        val on = session.enabledTools.split(',').toSet()
        return buildList {
            add(SearchIndexTool(search, items, people)); add(ReadFileTool(items))
            if (actions != null) {
                if (ChatToolNames.CALENDAR in on) { add(AddCalendarEventTool(actions, session.sessionId)); add(AddContactTool(actions, session.sessionId)) }
                if (ChatToolNames.MOVE in on) { add(MoveFilesTool(actions, items, session.sessionId, people)); add(DeleteFilesTool(actions, items, session.sessionId, people)) }
            }
        }
    }

    private class TooManyToolCalls : Exception()

    private suspend fun saveAnswer(sessionId: Long, text: String, taskId: Long? = null) {
        if (text.isBlank()) return
        chat.insertMessage(ChatMessageEntity(sessionId = sessionId, role = ChatRole.ASSISTANT, text = text.trim(), taskId = taskId, createdAt = clock()))
        chat.touch(sessionId, clock())
    }

    private fun enabledTools(session: ChatSessionEntity): List<ChatTool> {
        val on = session.enabledTools.split(',').toSet()
        return buildList {
            if (ChatToolNames.SEARCH in on) add(SearchIndexTool(search, items, people))
            if (ChatToolNames.READ in on) add(ReadFileTool(items))
            if (ChatToolNames.SCHEDULE in on && taskManager != null) {
                add(ScheduleTaskTool(taskManager, session.sessionId))
                add(ListScheduledTasksTool(taskManager, session.sessionId))
                add(UpdateScheduledTaskTool(taskManager, session.sessionId))
                add(CancelScheduledTaskTool(taskManager, session.sessionId))
            }
            if (ChatToolNames.CALENDAR in on && actions != null) {
                add(AddCalendarEventTool(actions, session.sessionId))
                add(AddContactTool(actions, session.sessionId))
            }
            if (ChatToolNames.MOVE in on && actions != null) {
                add(MoveFilesTool(actions, items, session.sessionId, people))
                add(DeleteFilesTool(actions, items, session.sessionId, people))
            }
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

    private fun todayString() = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(Date(clock()))

    private fun nowStamp(): String {
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("yyyy-MM-dd (EEEE) HH:mm 'UTC'XXX", Locale.US).apply { timeZone = tz }
        return "${fmt.format(Date(clock()))}, ${tz.id}"
    }

    private fun systemPrompt(custom: String, withActions: Boolean, withFiles: Boolean, people: List<String> = emptyList()): String {
        return "You are the assistant of a private, on-device file search app. Today is ${todayString()} " +
            "(time zone ${TimeZone.getDefault().id}); work out words like today, yesterday or a year ago from it, " +
            "and give search_index date_from/date_to as YYYY-MM-DD (a query is optional when you filter by date or kind, e.g. to list yesterday's photos). " +
            "When the user asks you to do something later or repeatedly (every day at 9, every Monday, in 30 minutes, whenever a new file about X is added), " +
            "call schedule_task with the instruction written so it can run on its own (for a file task, query lists the user's own words first and then 2 to 5 concrete words a matching file would contain, separated by |, e.g. \"schedule|wedding invitation|invitation|appointment\"; never the whole instruction); it only takes effect after the user confirms the card shown in the chat, so tell them to confirm it. To change an existing task the user asks about, use list_scheduled_tasks and update_scheduled_task instead of creating a new one. " +
            (if (withActions) "When a file shows an event or a person worth saving (an invitation, ticket, appointment, business card) and the user wants it in their calendar or contacts, call add_calendar_event or add_contact with the details; the user approves a card first, so never say it is already added. " else "") +
            (if (withFiles) "To move or delete files the user asks about, first find them with search_index and then call move_files or delete_files with their item_ids (never guess ids); the user approves a card first, deleting goes to the trash unless they ask for permanent deletion, and you must not say the files are already moved or deleted. " else "") +
            (if (people.isEmpty()) "" else "People the user named in their photos: ${people.joinToString()}. When the user mentions one of them, pass that name as person to search_index" +
                (if (withFiles) ", move_files or delete_files (person instead of item_ids moves or deletes every photo of that person)" else "") + ". ") +
            "Answer from the user's own files when the question is about them: use the search_index tool to find files and read_file to read them, " +
            "if those tools are available. Name the files (and page or time) your answer is based on. " +
            "If nothing relevant is found, say so instead of guessing. Keep answers concise and reply in the language the user writes in." +
            (if (custom.isEmpty()) "" else "\n\nThe user's own instructions (follow them unless they conflict with the rules above):\n$custom")
    }

    companion object {
        private const val TAG = "LSA.Chat"
        const val NO_MODEL = "no_model"
        const val MAX_TOOL_CALLS = 8
        /** Named people listed in the system prompt at most (the 4K default window). */
        private const val MAX_NAMES = 30
        private const val MAX_STORED_RESULT = 4000
        private const val ATTACHMENT_CHARS = 3000
        private const val AUTO_SEARCH_RESULTS = 3
        private const val AUTO_SEARCH_CHARS = 800
        private const val SYSTEM_TOKENS = 350
        private const val PHOTO_SIDE = 1024
        private const val PHOTO_UNSUPPORTED = "[A photo is attached, but this chat model cannot see images.]\n\n"
        private const val JUDGE_SYSTEM = "You check files against a condition. Reply with only the item_id numbers of the files that clearly match, " +
            "separated by commas, or the word NONE. No other words."
        private const val SCHEDULED_NOTE = "[This is a scheduled task running now without the user present. Carry out the instruction and write the result as a message to the user.]\n"
        private const val SCHEDULED_ACTIONS_NOTE = "[Tools that add events or contacts or move or delete files only create cards the user approves later; never say they are done.]\n"
        private const val TOO_MANY_CALLS = "I stopped because I called tools too many times without an answer."
    }
}
