package com.lsa.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.chat.ChatController
import com.lsa.app.chat.ChatPhase
import com.lsa.app.chat.ChatToolNames
import com.lsa.app.chat.PendingAttachment
import com.lsa.app.chat.ReadFileTool
import com.lsa.app.chat.SearchIndexTool
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ChatRole
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ml.ModelHost
import com.lsa.app.ml.llm.ChatEvent
import com.lsa.app.ml.llm.ChatModel
import com.lsa.app.ml.llm.ChatTool
import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.lsa.app.chat.schedule.TaskAlarms
import com.lsa.app.chat.schedule.TaskManager
import com.lsa.app.data.db.TaskStatus
import com.lsa.app.data.db.TaskResult

/** Records what the controller hands to the model and answers with canned events. */
private class FakeChatModel(override val supportsImages: Boolean = false, private val events: suspend (List<ChatTool>) -> List<ChatEvent> = { listOf(ChatEvent.Token("Hi there")) }) : ChatModel {
    class Call(val key: String, val history: List<ChatTurn>, val user: ChatTurn, val tools: List<String>)
    val calls = ArrayList<Call>()
    override fun reply(conversationKey: String, system: String, history: List<ChatTurn>, user: ChatTurn, tools: List<ChatTool>): Flow<ChatEvent> = flow {
        calls += Call(conversationKey, history, user, tools.map { it.name })
        events(tools).forEach { emit(it) }
    }
    override fun reset() {}
    override fun close() {}
}

/** Remembers which alarms would have been set instead of touching the system AlarmManager. */
private class FakeAlarms(ctx: Context) : TaskAlarms(ctx) {
    val armed = HashMap<Long, Long>()
    override fun canScheduleExact() = true
    override fun arm(taskId: Long, atMs: Long) { armed[taskId] = atMs }
    override fun cancel(taskId: Long) { armed.remove(taskId) }
}

@RunWith(AndroidJUnit4::class)
class ChatTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var residency: ModelHost
    /** Whether the (fake) chat model is available, as OndolSaem would report it. */
    private var modelPresent = true
    private lateinit var search: SearchEngine
    private lateinit var controller: ChatController
    private var model = FakeChatModel()
    private lateinit var tasks: TaskManager
    private lateinit var actionManager: com.lsa.app.chat.actions.ActionManager
    private lateinit var alarms: FakeAlarms
    private var now = 1_800_000_000_000L
    private var lowBattery = false
    private var judgeFirstOn = true
    private val notified = ArrayList<Triple<Long, String, Boolean>>()
    private val enqueued = ArrayList<Long>()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        settings = SettingsStore(ctx)
        settings.setChatModelId("chat-qwen25-1.5b"); settings.setEmbeddingModelId("test-model")
        residency = ModelHost(ctx, settings, scope)
        residency.chatOverride = { if (modelPresent) model else null }
        residency.contextTokensOverride = 4096
        search = SearchEngine(db.itemDao(), { null }, settings)
        alarms = FakeAlarms(ctx)
        actionManager = com.lsa.app.chat.actions.ActionManager(db.actionDao(), clock = { now })
        tasks = TaskManager(
            ctx, db.taskDao(), db.chatDao(), db.itemDao(), search, alarms, { controller }, clock = { now },
            batteryLow = { lowBattery }, notifier = { t, text, ok -> notified += Triple(t.taskId, text, ok) }, enqueue = { enqueued += it },
            judgeFirst = { judgeFirstOn }, actionDao = db.actionDao(),
        )
        controller = ChatController(ctx, db.chatDao(), db.itemDao(), residency, search, settings, scope, clock = { now }, taskManager = tasks, actions = actionManager)
    }

    @After fun tearDown() = runBlocking {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        db.close()
        settings.setChatModelId(SettingsStore.DEFAULT_CHAT); settings.setEmbeddingModelId(SettingsStore.DEFAULT_EMBEDDING)
    }

    private fun installModel(present: Boolean) { modelPresent = present }

    private suspend fun awaitIdle() = withTimeout(10_000) { while (controller.busy) kotlinx.coroutines.delay(20) }

    private suspend fun sendAndWait(sessionId: Long, text: String, attachments: List<PendingAttachment> = emptyList()) {
        controller.send(sessionId, text, attachments); awaitIdle()
    }

    private suspend fun addItem(name: String, kind: ItemKind, vararg segs: SegmentEntity, place: String? = null, indexedAt: Long = 1): Long {
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:$name", kind = kind, uri = "content://$name", folderKey = "F/", displayName = name, mimeType = null, sizeBytes = 1, modifiedAt = 1,
                title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null, latitude = null, longitude = null,
                status = ItemStatus.DONE, error = null, embeddingModelId = "test-model", pipelineKey = "p", progress = 0, indexedAt = indexedAt, place = place,
            ),
        )
        db.itemDao().insertSegments(segs.map { it.copy(itemId = id) })
        return id
    }

    private fun seg(type: SegmentType, ordinal: Int, text: String?, page: Int? = null, startMs: Long? = null) =
        SegmentEntity(itemId = 0, type = type, ordinal = ordinal, startMs = startMs, endMs = startMs?.plus(1000), page = page, text = text, embedding = null, thumbPath = null)

    // ---- sessions and turns

    @Test fun aTurnStoresTheQuestionAndTheAnswerAndTitlesTheSession() = runBlocking {
        val id = controller.newSession()
        sendAndWait(id, "What is in the launch plan for the second quarter and who owns it?")
        val msgs = db.chatDao().messages(id)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), msgs.map { it.role })
        assertEquals("Hi there", msgs.last().text)
        assertEquals("the title is the start of the first question", "What is in the launch plan for the secon", db.chatDao().session(id)!!.title)
        assertNull(controller.run.value)
    }

    @Test fun earlierTurnsAreReplayedToTheModel() = runBlocking {
        val id = controller.newSession()
        sendAndWait(id, "first question"); sendAndWait(id, "second question")
        val second = model.calls.last()
        assertEquals(listOf("first question", "Hi there"), second.history.map { it.text })
        assertTrue(second.user.text.endsWith("second question"))
        assertEquals("same conversation key while the tools are unchanged", model.calls.first().key, second.key)
    }

    @Test fun historyIsTrimmedToTheContextBudget() = runBlocking {
        residency.contextTokensOverride = 2048
        val id = controller.newSession()
        val big = "word ".repeat(1500) // about 2500 tokens: never fits next to the new question
        sendAndWait(id, big); sendAndWait(id, "short follow-up")
        assertTrue("the oversized earlier turn is left out", model.calls.last().history.isEmpty())
    }

    @Test fun toolCallsAreRecordedBetweenQuestionAndAnswer() = runBlocking {
        model = FakeChatModel { listOf(ChatEvent.ToolRan(ChatToolNames.SEARCH, """{"query":"x"}""", "[]"), ChatEvent.Token("done")) }
        val id = controller.newSession()
        sendAndWait(id, "look it up")
        assertEquals(listOf(ChatRole.USER, ChatRole.TOOL, ChatRole.ASSISTANT), db.chatDao().messages(id).map { it.role })
        assertEquals(ChatToolNames.SEARCH, db.chatDao().messages(id)[1].toolName)
        assertTrue("tool rows are not replayed", model.calls.single().history.isEmpty())
    }

    @Test fun onlyEnabledToolsAreOffered() = runBlocking {
        val id = controller.newSession(setOf(ChatToolNames.READ))
        sendAndWait(id, "hi")
        assertEquals(listOf(ChatToolNames.READ), model.calls.single().tools)
        db.chatDao().setTools(id, "", false)
        sendAndWait(id, "again")
        assertTrue(model.calls.last().tools.isEmpty())
        assertFalse("a different tool set is a different conversation", model.calls.first().key == model.calls.last().key)
    }

    @Test fun tooManyToolCallsStopTheTurn() = runBlocking {
        model = FakeChatModel { List(10) { ChatEvent.ToolRan(ChatToolNames.READ, "{}", "r") } }
        val id = controller.newSession()
        sendAndWait(id, "loop")
        val msgs = db.chatDao().messages(id)
        assertTrue(msgs.count { it.role == ChatRole.TOOL } <= ChatController.MAX_TOOL_CALLS + 1)
        assertEquals(ChatRole.ASSISTANT, msgs.last().role)
    }

    @Test fun missingModelFailsWithoutStoringAnything() = runBlocking {
        installModel(false)
        val id = controller.newSession()
        controller.send(id, "hello"); awaitIdle()
        assertEquals(ChatPhase.FAILED, controller.run.value?.phase)
        assertEquals(ChatController.NO_MODEL, controller.run.value?.error)
        assertTrue(db.chatDao().messages(id).isEmpty())
        controller.dismissError(); assertNull(controller.run.value)
    }

    @Test fun deletingASessionDeletesItsMessages() = runBlocking {
        val id = controller.newSession()
        sendAndWait(id, "hello")
        controller.deleteSession(id)
        assertNull(db.chatDao().session(id)); assertTrue(db.chatDao().messages(id).isEmpty())
    }

    @Test fun renameChangesTheTitle() = runBlocking {
        val id = controller.newSession()
        sendAndWait(id, "hello")
        db.chatDao().rename(id, "Launch notes")
        assertEquals("Launch notes", db.chatDao().observeSession(id).first()!!.title)
    }

    @Test fun everyTurnCarriesTheCurrentTime() = runBlocking {
        val id = controller.newSession()
        sendAndWait(id, "what did I shoot yesterday?")
        val sent = model.calls.single().user.text
        assertTrue(sent, Regex("""^\[Current time: \d{4}-\d{2}-\d{2} \(\w+\) \d{2}:\d{2} UTC[+-]\d{2}:\d{2}, \S+]\n""").containsMatchIn(sent) && sent.endsWith("yesterday?"))
        assertEquals("the stored question is only what the user typed", "what did I shoot yesterday?", db.chatDao().messages(id).first().text)
    }

    // ---- attachments

    @Test fun attachedFilesAreGivenToTheModelAsExcerpts() = runBlocking {
        val doc = addItem("plan.docx", ItemKind.DOC, seg(SegmentType.TEXT_CHUNK, 0, "Launch depends on design approval.", page = 1))
        val id = controller.newSession()
        sendAndWait(id, "summarize", listOf(PendingAttachment.Item(doc)))
        val sent = model.calls.single().user.text
        assertTrue(sent, sent.contains("[Attached file: plan.docx (item_id=$doc, document)]") && sent.contains("design approval") && sent.endsWith("summarize"))
        val stored = db.chatDao().messages(id).first()
        assertEquals("the stored question is only what the user typed", "summarize", stored.text)
        assertEquals(doc, JSONArray(stored.attachments).getJSONObject(0).getLong("itemId"))
    }

    @Test fun autoSearchAddsExcerptsWithoutToolCalling() = runBlocking {
        addItem("budget.md", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "The quokka budget was approved."))
        val id = controller.newSession(emptySet())
        db.chatDao().setTools(id, "", true)
        sendAndWait(id, "quokka")
        val sent = model.calls.single().user.text
        assertTrue(sent, sent.contains("[Search results") && sent.contains("budget.md") && sent.contains("approved"))
        assertTrue(model.calls.single().tools.isEmpty())
    }

    // ---- tools

    @Test fun searchToolReturnsMatchesAsJson() = runBlocking {
        val id = addItem("notes.md", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "The quokka migration plan"), place = "Dubai UAE")
        val out = SearchIndexTool(search, db.itemDao()).execute(JSONObject().put("query", "quokka"))
        val hit = JSONArray(out).getJSONObject(0)
        assertEquals(id, hit.getLong("item_id")); assertEquals("document", hit.getString("kind")); assertEquals("notes.md", hit.getString("name"))
        assertTrue(hit.getString("snippet").contains("quokka"))
        assertEquals("No matching files.", SearchIndexTool(search, db.itemDao()).execute(JSONObject().put("query", "zzzznothing")))
        assertTrue(SearchIndexTool(search, db.itemDao()).execute(JSONObject()).startsWith("Error"))
        val filtered = SearchIndexTool(search, db.itemDao()).execute(JSONObject().put("query", "quokka").put("kinds", "audio"))
        assertEquals("kind filter excludes the document", "No matching files.", filtered)
        val listed = JSONArray(SearchIndexTool(search, db.itemDao()).execute(JSONObject().put("kinds", "document")))
        assertEquals("no query: lists by kind", id, listed.getJSONObject(0).getLong("item_id"))
        assertEquals("offset pages past the list", "No matching files.", SearchIndexTool(search, db.itemDao()).execute(JSONObject().put("kinds", "document").put("offset", 5)))
    }

    /** Faces of a named person in [itemIds] (face grouping), for the `person` arguments. */
    private suspend fun person(name: String, vararg itemIds: Long): Long {
        val pid = db.faceDao().insertPerson(com.lsa.app.data.db.PersonEntity(name = name, createdAt = 1, updatedAt = 1))
        db.faceDao().insertFaces(itemIds.map { com.lsa.app.data.db.FaceEntity(itemId = it, left = 0f, top = 0f, right = 1f, bottom = 1f, score = 1f, vector = byteArrayOf(0, 0, 0, 0, 1), personId = pid, thumbPath = null) })
        return pid
    }

    @Test fun personArgumentsFindAndMoveTheirPhotos() = runBlocking {
        val withMina = addItem("beach.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, "sunny beach"))
        val other = addItem("beach2.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, "sunny beach again"))
        val party = addItem("party.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, "birthday"))
        person("Mina", withMina, party)
        val people = com.lsa.app.chat.PeopleDirectory(db.faceDao())
        val search = SearchIndexTool(search, db.itemDao(), people)
        val listed = JSONArray(search.execute(JSONObject().put("person", "mina")))
        assertEquals("only Mina's photos", setOf(withMina, party), (0 until listed.length()).map { listed.getJSONObject(it).getLong("item_id") }.toSet())
        val beach = JSONArray(search.execute(JSONObject().put("query", "beach").put("person", "Mina's")))
        assertEquals("query and person together", listOf(withMina), (0 until beach.length()).map { beach.getJSONObject(it).getLong("item_id") })
        assertTrue(search.execute(JSONObject().put("person", "Obama")).contains("Known people: Mina"))
        assertTrue("other photo exists", other > 0)

        val session = db.chatDao().insertSession(com.lsa.app.data.db.ChatSessionEntity(title = "t", enabledTools = "move_files", autoSearch = false, createdAt = 1, updatedAt = 1))
        val move = com.lsa.app.chat.actions.MoveFilesTool(actionManager, db.itemDao(), session, people)
        val result = JSONObject(move.execute(JSONObject().put("person", "Mina").put("destination", "Pictures/Mina")))
        val payload = JSONObject(db.actionDao().get(result.getLong("action_id"))!!.payload)
        assertEquals("Pictures/Mina", payload.getString("destination"))
        assertEquals(2, payload.getJSONArray("files").length())
        assertTrue(move.execute(JSONObject().put("destination", "Pictures/X")).startsWith("Error"))
    }

    @Test fun readToolPagesThroughLongText() = runBlocking {
        val long = "abcdefghij".repeat(100) // 1000 chars
        val id = addItem("long.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, long))
        val tool = ReadFileTool(db.itemDao())
        val first = JSONObject(tool.execute(JSONObject().put("item_id", id).put("max_chars", 200)))
        assertEquals(200, first.getString("text").length); assertEquals(200, first.getInt("next_offset")); assertEquals(1000, first.getInt("total_chars"))
        val last = JSONObject(tool.execute(JSONObject().put("item_id", id).put("offset", 900).put("max_chars", 200)))
        assertEquals(100, last.getString("text").length); assertTrue(last.isNull("next_offset"))
        assertTrue(tool.execute(JSONObject().put("item_id", 9999)).startsWith("Error"))
    }

    @Test fun readToolSelectsTranscriptTimeRanges() = runBlocking {
        val id = addItem(
            "talk.mp3", ItemKind.AUDIO,
            seg(SegmentType.TRANSCRIPT, 0, "intro words", startMs = 0), seg(SegmentType.TRANSCRIPT, 1, "the budget part", startMs = 120_000),
        )
        val out = JSONObject(ReadFileTool(db.itemDao()).execute(JSONObject().put("item_id", id).put("from_sec", 100).put("to_sec", 200)))
        assertEquals("[02:00] the budget part", out.getString("text")); assertEquals("audio", out.getString("kind"))
        assertNotNull(out.optString("date"))
    }

    // ---- scheduled tasks

    private fun dailyArgs() = JSONObject().put("trigger", "time").put("repeat", "daily").put("at", "09:00").put("instruction", "Summarize yesterday's photos")

    @Test fun aProposedTaskDoesNothingUntilTheUserConfirms() = runBlocking {
        val chat = controller.newSession()
        val out = JSONObject(tasks.propose(chat, dailyArgs()))
        val id = out.getLong("task_id")
        assertEquals("waiting_for_user_confirmation", out.getString("status"))
        assertEquals(TaskStatus.PENDING_CONFIRM, db.taskDao().get(id)!!.status)
        assertTrue("no alarm yet", alarms.armed.isEmpty())
        assertTrue(tasks.confirm(id))
        val t = db.taskDao().get(id)!!
        assertEquals(TaskStatus.ACTIVE, t.status); assertEquals(now, t.activatedAt)
        assertTrue(t.nextRunAt!! > now); assertEquals(t.nextRunAt, alarms.armed[id])
        assertFalse("a second confirm is ignored", tasks.confirm(id))
        tasks.delete(id)
        assertNull(db.taskDao().get(id)); assertTrue(alarms.armed.isEmpty())
    }

    @Test fun badScheduleArgumentsComeBackAsErrors() = runBlocking {
        val chat = controller.newSession()
        assertTrue(tasks.propose(chat, JSONObject().put("trigger", "time").put("instruction", "x").put("repeat", "every_minutes").put("every_minutes", 1)).startsWith("Error"))
        assertTrue(tasks.propose(chat, JSONObject().put("trigger", "time").put("instruction", "x").put("repeat", "weekly").put("at", "09:00")).startsWith("Error"))
        assertTrue(tasks.propose(chat, JSONObject().put("trigger", "later").put("instruction", "x")).startsWith("Error"))
        assertTrue(tasks.propose(chat, JSONObject().put("trigger", "time").put("instruction", " ")).startsWith("Error"))
        assertEquals(0, db.taskDao().ofSession(chat).size)
    }

    @Test fun theScheduleToolCallLeavesACardLinkedToItsTask() = runBlocking {
        val chat = controller.newSession()
        model = FakeChatModel { tools ->
            val tool = tools.first { it.name == ChatToolNames.SCHEDULE }
            val args = dailyArgs()
            listOf(ChatEvent.ToolRan(ChatToolNames.SCHEDULE, args.toString(), tool.execute(args)), ChatEvent.Token("Please confirm."))
        }
        sendAndWait(chat, "every day at 9 summarize yesterday's photos")
        val card = db.chatDao().messages(chat).first { it.role == ChatRole.TOOL }
        assertNotNull(card.taskId)
        assertEquals(TaskStatus.PENDING_CONFIRM, db.taskDao().get(card.taskId!!)!!.status)
    }

    @Test fun aScheduledRunPostsToItsChatAndNotifiesWithoutTheScheduleTool() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, dailyArgs())).getLong("task_id").also { tasks.confirm(it) }
        model = FakeChatModel { listOf(ChatEvent.Token("Three photos from yesterday.")) }
        tasks.run(id)
        val msgs = db.chatDao().messages(chat)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), msgs.map { it.role })
        assertTrue("both messages belong to the task", msgs.all { it.taskId == id })
        assertEquals("Three photos from yesterday.", msgs.last().text)
        val offered = model.calls.single().tools
        assertTrue("search and read are offered", ChatToolNames.SEARCH in offered && ChatToolNames.READ in offered)
        assertTrue("a task cannot schedule tasks", offered.none { it.endsWith("scheduled_task") || it == ChatToolNames.SCHEDULE })
        assertTrue(model.calls.single().user.text.contains("scheduled task"))
        assertEquals(listOf(Triple(id, "Three photos from yesterday.", true)), notified)
        val t = db.taskDao().get(id)!!
        assertEquals(TaskResult.OK, t.lastResult); assertEquals(TaskStatus.ACTIVE, t.status)
        assertTrue("the next day is armed", t.nextRunAt!! > now && alarms.armed[id] == t.nextRunAt)
        assertEquals("the chat keeps its own title", "", db.chatDao().session(chat)!!.title)
    }

    @Test fun aOneTimeTaskFinishesAfterItsRun() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, JSONObject().put("trigger", "time").put("repeat", "once").put("in_minutes", 5).put("instruction", "say hi"))).getLong("task_id")
        tasks.confirm(id)
        now += 6 * 60_000
        tasks.run(id)
        assertEquals(TaskStatus.DONE, db.taskDao().get(id)!!.status)
        assertNull("nothing left to fire", db.taskDao().get(id)!!.nextRunAt)
    }

    @Test fun aLowBatterySkipsARepeatingRunWithANote() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, dailyArgs())).getLong("task_id").also { tasks.confirm(it) }
        lowBattery = true
        tasks.run(id)
        assertTrue("no model call", model.calls.isEmpty())
        assertEquals(TaskResult.SKIPPED, db.taskDao().get(id)!!.lastResult)
        assertEquals(1, db.chatDao().messages(chat).size)
    }

    @Test fun aFailedRunIsRecordedAndNotified() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, dailyArgs())).getLong("task_id").also { tasks.confirm(it) }
        installModel(false)
        tasks.run(id)
        assertEquals(TaskResult.FAILED, db.taskDao().get(id)!!.lastResult)
        assertEquals(false, notified.single().third)
    }

    @Test fun fileTasksSeeOnlyNewMatchingFilesOfTheirKinds() = runBlocking {
        val chat = controller.newSession()
        judgeFirstOn = false // this test is about the match itself
        addItem("old-budget.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "the budget for last year"), indexedAt = now - 10_000)
        val args = JSONObject().put("trigger", "file").put("instruction", "Summarize it").put("query", "budget").put("kinds", "document")
        val id = JSONObject(tasks.propose(chat, args)).getLong("task_id").also { tasks.confirm(it) }
        assertTrue("file tasks have no alarm", alarms.armed.isEmpty())
        now += 5_000
        val fresh = addItem("new-budget.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "the budget for next year"), indexedAt = now)
        addItem("budget.png", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, "budget"), indexedAt = now)
        addItem("holiday.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "beach trip photos"), indexedAt = now)
        tasks.onIndexed(passStart = 0)
        assertEquals("only the new document that matches", fresh.toString(), db.taskDao().get(id)!!.pendingItemIds)
        assertEquals(listOf(id), enqueued)

        // the model first checks the candidate (no tools), then runs the task (with tools)
        model = FakeChatModel { tools -> if (tools.isEmpty()) listOf(ChatEvent.Token("$fresh")) else listOf(ChatEvent.Token("Summary.")) }
        tasks.run(id)
        assertEquals(2, model.calls.size)
        assertTrue(model.calls.last().user.text.contains("new-budget.txt"))
        assertEquals("the queue is emptied", "", db.taskDao().get(id)!!.pendingItemIds)
        tasks.onIndexed(passStart = now + 1)
        assertEquals("nothing new, nothing queued", 1, enqueued.size)
    }

    @Test fun deletingAChatDeletesItsTasks() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, dailyArgs())).getLong("task_id")
        controller.deleteSession(chat)
        assertNull(db.taskDao().get(id))
    }

    @Test fun updatingATaskChangesItsScheduleAndReArmsTheAlarm() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, dailyArgs())).getLong("task_id").also { tasks.confirm(it) }
        val before = db.taskDao().get(id)!!.nextRunAt
        val out = JSONObject(tasks.update(chat, JSONObject().put("task_id", id).put("at", "18:30")))
        assertEquals(id, out.getLong("task_id"))
        val t = db.taskDao().get(id)!!
        assertEquals(18 * 60 + 30, t.atMinuteOfDay); assertEquals("still daily", com.lsa.app.data.db.TaskRepeat.DAILY, t.repeat)
        assertEquals("Summarize yesterday's photos", t.instruction)
        assertEquals(t.nextRunAt, alarms.armed[id]); assertTrue(t.nextRunAt != before)
        // weekly with days
        tasks.update(chat, JSONObject().put("task_id", id).put("repeat", "weekly").put("weekdays", "mon,fri"))
        val w = db.taskDao().get(id)!!
        assertEquals(com.lsa.app.data.db.TaskRepeat.WEEKLY, w.repeat); assertEquals((1 shl 0) or (1 shl 4), w.weekdayMask); assertEquals(18 * 60 + 30, w.atMinuteOfDay)
    }

    @Test fun updatingOnlyTheInstructionKeepsTheScheduleAndFileTasksChangeTheirQuery() = runBlocking {
        val chat = controller.newSession()
        val once = JSONObject(tasks.propose(chat, JSONObject().put("trigger", "time").put("repeat", "once").put("in_minutes", 30).put("instruction", "a"))).getLong("task_id").also { tasks.confirm(it) }
        val at = db.taskDao().get(once)!!.nextRunAt
        tasks.update(chat, JSONObject().put("task_id", once).put("instruction", "b"))
        assertEquals("b", db.taskDao().get(once)!!.instruction); assertEquals(at, db.taskDao().get(once)!!.nextRunAt)
        val file = JSONObject(tasks.propose(chat, JSONObject().put("trigger", "file").put("instruction", "f").put("query", "x"))).getLong("task_id")
        tasks.update(chat, JSONObject().put("task_id", file).put("query", "receipt").put("kinds", "image"))
        assertEquals("receipt", db.taskDao().get(file)!!.fileQuery); assertEquals("IMAGE", db.taskDao().get(file)!!.fileKinds)
    }

    @Test fun aTaskOfAnotherChatCannotBeUpdated() = runBlocking {
        val a = controller.newSession(); val b = controller.newSession()
        val id = JSONObject(tasks.propose(a, dailyArgs())).getLong("task_id")
        assertTrue(tasks.update(b, JSONObject().put("task_id", id).put("at", "10:00")).startsWith("Error"))
        assertEquals(9 * 60, db.taskDao().get(id)!!.atMinuteOfDay)
    }

    private fun fileArgs() = JSONObject().put("trigger", "file").put("instruction", "Summarize it").put("query", "budget").put("kinds", "document")

    @Test fun aCandidateTheModelRejectsPostsNothing() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, fileArgs())).getLong("task_id").also { tasks.confirm(it) }
        now += 1_000
        addItem("budget-joke.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "a budget airline joke"), indexedAt = now)
        tasks.onIndexed(passStart = 0)
        assertEquals(listOf(id), enqueued)
        model = FakeChatModel { listOf(ChatEvent.Token("NONE")) }
        tasks.run(id)
        assertEquals("only the check ran", 1, model.calls.size)
        assertTrue(db.chatDao().messages(chat).isEmpty()); assertTrue(notified.isEmpty())
        assertEquals("", db.taskDao().get(id)!!.pendingItemIds)
        assertNull("a silent rejection is not a run", db.taskDao().get(id)!!.lastResult)
    }

    @Test fun aFileTaskNeedsAQueryUnlessEveryFileIsMeant() = runBlocking {
        val chat = controller.newSession()
        val noQuery = JSONObject().put("trigger", "file").put("instruction", "x").put("kinds", "image")
        assertTrue(tasks.propose(chat, noQuery).startsWith("Error"))
        assertEquals(0, db.taskDao().ofSession(chat).size)
        val id = JSONObject(tasks.propose(chat, noQuery.put("any_file", true))).getLong("task_id")
        assertEquals("", db.taskDao().get(id)!!.fileQuery)
        assertTrue(tasks.update(chat, JSONObject().put("task_id", id).put("query", "")).startsWith("Error"))
        assertTrue(!tasks.update(chat, JSONObject().put("task_id", id).put("query", "receipt")).startsWith("Error"))
    }

    @Test fun judgeFirstLetsTheModelSeeTextFilesTheWordsMissAndTheSettingTurnsItOff() = runBlocking {
        val chat = controller.newSession()
        val args = JSONObject().put("trigger", "file").put("instruction", "List the dates").put("query", "schedule").put("kinds", "document")
        val id = JSONObject(tasks.propose(chat, args)).getLong("task_id").also { tasks.confirm(it) }
        now += 1_000
        val invitation = addItem("invitation.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "Wedding on December 5 at 2pm, Grand Hall"), indexedAt = now)
        tasks.onIndexed(passStart = 0)
        assertEquals("no word and no embedder, but the model gets to judge it", listOf(id), enqueued)
        assertEquals(invitation.toString(), db.taskDao().get(id)!!.pendingItemIds)
        enqueued.clear(); db.taskDao().setPending(id, ""); judgeFirstOn = false
        tasks.onIndexed(passStart = 0)
        assertTrue("with the setting off only the match counts", enqueued.isEmpty())
    }

    @Test fun anyAlternativeOfAFileTaskQueryCanMatch() = runBlocking {
        val chat = controller.newSession()
        judgeFirstOn = false
        val args = JSONObject().put("trigger", "file").put("instruction", "x").put("query", "schedule|wedding|appointment")
        val id = JSONObject(tasks.propose(chat, args)).getLong("task_id").also { tasks.confirm(it) }
        now += 1_000
        val hit = addItem("a.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "Our wedding is in spring"), indexedAt = now)
        addItem("b.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, "grocery list"), indexedAt = now)
        tasks.onIndexed(passStart = 0)
        assertEquals(hit.toString(), db.taskDao().get(id)!!.pendingItemIds)
    }

    @Test fun aCalendarProposalBecomesACardAndNothingHappensUntilApproved() = runBlocking {
        val chat = controller.newSession()
        model = FakeChatModel { tools ->
            val t = tools.single { it.name == ChatToolNames.CALENDAR }
            val r = t.execute(JSONObject().put("title", "Wedding").put("start", "2026-12-05 14:00").put("location", "Seoul"))
            listOf(ChatEvent.ToolRan(t.name, "{}", r), ChatEvent.Token("Please approve the card."))
        }
        controller.send(chat, "add the wedding to my calendar"); awaitIdle()
        val tool = db.chatDao().messages(chat).single { it.role == ChatRole.TOOL }
        val action = db.actionDao().get(tool.actionId!!)!!
        assertEquals(com.lsa.app.data.db.ActionStatus.PROPOSED, action.status)
        assertEquals(com.lsa.app.data.db.ActionKind.CALENDAR_EVENT, action.kind)
        val r = actionManager.approve(action.actionId)
        assertTrue(r is com.lsa.app.chat.actions.ApprovalResult.Launch)
        val intent = (r as com.lsa.app.chat.actions.ApprovalResult.Launch).intent
        assertEquals(android.content.Intent.ACTION_INSERT, intent.action)
        assertEquals("Wedding", intent.getStringExtra(android.provider.CalendarContract.Events.TITLE))
        actionManager.markDone(action.actionId)
        assertEquals(com.lsa.app.data.db.ActionStatus.DONE, db.actionDao().get(action.actionId)!!.status)
        assertTrue("a handled card cannot be approved again", actionManager.approve(action.actionId) is com.lsa.app.chat.actions.ApprovalResult.Failed)
    }

    @Test fun aDismissedProposalStaysDismissedAndTheToolIsOffWhenTheSwitchIsOff() = runBlocking {
        val chat = controller.newSession(setOf(ChatToolNames.SEARCH))
        model = FakeChatModel { tools ->
            assertTrue(tools.none { it.name == ChatToolNames.CALENDAR || it.name == ChatToolNames.CONTACT })
            listOf(ChatEvent.Token("ok"))
        }
        controller.send(chat, "hi"); awaitIdle()
        val id = JSONObject(actionManager.propose(chat, com.lsa.app.data.db.ActionKind.CONTACT, JSONObject().put("name", "Kim"))).getLong("action_id")
        actionManager.dismiss(id)
        actionManager.markDone(id)
        assertEquals(com.lsa.app.data.db.ActionStatus.DISMISSED, db.actionDao().get(id)!!.status)
    }

    @Test fun aScheduledRunCanOnlyProposeAndTheNotificationCountsWaitingCards() = runBlocking {
        val chat = controller.newSession()
        val id = JSONObject(tasks.propose(chat, JSONObject().put("trigger", "time").put("repeat", "once").put("in_minutes", 5).put("instruction", "Add my dentist visit to the calendar")))
            .getLong("task_id").also { tasks.confirm(it) }
        model = FakeChatModel { tools ->
            val t = tools.single { it.name == ChatToolNames.CALENDAR }
            val r = t.execute(JSONObject().put("title", "Dentist").put("start", "2026-12-05 09:00"))
            listOf(ChatEvent.ToolRan(t.name, "{}", r), ChatEvent.Token("Proposed."))
        }
        tasks.run(id)
        val tool = db.chatDao().messages(chat).single { it.role == ChatRole.TOOL }
        assertNotNull("the card is linked", tool.actionId)
        assertEquals(com.lsa.app.data.db.ActionStatus.PROPOSED, db.actionDao().get(tool.actionId!!)!!.status)
        assertTrue("the notification says an approval is waiting", notified.single().second.contains("1"))
    }

    @Test fun aScheduledRunDoesNotGetActionToolsThatTheChatSwitchedOff() = runBlocking {
        val chat = controller.newSession(setOf(ChatToolNames.SEARCH, ChatToolNames.READ))
        val id = JSONObject(tasks.propose(chat, JSONObject().put("trigger", "time").put("repeat", "once").put("in_minutes", 5).put("instruction", "x")))
            .getLong("task_id").also { tasks.confirm(it) }
        model = FakeChatModel { tools ->
            assertEquals(setOf(ChatToolNames.SEARCH, ChatToolNames.READ), tools.map { it.name }.toSet())
            listOf(ChatEvent.Token("ok"))
        }
        tasks.run(id)
        assertEquals(1, model.calls.size)
    }
}
