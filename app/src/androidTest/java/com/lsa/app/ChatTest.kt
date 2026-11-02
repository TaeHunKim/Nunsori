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
import com.lsa.app.ml.ModelResidency
import com.lsa.app.ml.llm.ChatEvent
import com.lsa.app.ml.llm.ChatModel
import com.lsa.app.ml.llm.ChatTool
import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.models.ModelCatalog
import com.lsa.app.models.ModelManager
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

@RunWith(AndroidJUnit4::class)
class ChatTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var modelsDir: File
    private lateinit var manager: ModelManager
    private lateinit var residency: ModelResidency
    private lateinit var search: SearchEngine
    private lateinit var controller: ChatController
    private var model = FakeChatModel()
    private val chatSpec = ModelCatalog.byId("chat-qwen25-1.5b")!!

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        settings = SettingsStore(ctx)
        settings.setChatModelId(chatSpec.id); settings.setChatContextTokens(4096); settings.setEmbeddingModelId("test-model")
        modelsDir = File(ctx.cacheDir, "chat-test-models").also { it.deleteRecursively() }
        manager = ModelManager(ctx, settings, modelsDir = modelsDir)
        installModel(true)
        residency = ModelResidency(ctx, manager, settings, scope)
        residency.chatFactory = { _, _, _ -> model }
        search = SearchEngine(db.itemDao(), { null }, settings)
        controller = ChatController(ctx, db.chatDao(), db.itemDao(), residency, search, settings, scope)
    }

    @After fun tearDown() = runBlocking {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        db.close(); modelsDir.deleteRecursively()
        settings.setChatModelId(SettingsStore.DEFAULT_CHAT); settings.setEmbeddingModelId(SettingsStore.DEFAULT_EMBEDDING)
    }

    private fun installModel(present: Boolean) {
        val f = manager.file(chatSpec)!!
        if (present) { f.parentFile!!.mkdirs(); f.writeText("x") } else f.delete()
    }

    private suspend fun awaitIdle() = withTimeout(10_000) { while (controller.busy) kotlinx.coroutines.delay(20) }

    private suspend fun sendAndWait(sessionId: Long, text: String, attachments: List<PendingAttachment> = emptyList()) {
        controller.send(sessionId, text, attachments); awaitIdle()
    }

    private suspend fun addItem(name: String, kind: ItemKind, vararg segs: SegmentEntity, place: String? = null): Long {
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:$name", kind = kind, uri = "content://$name", folderKey = "F/", displayName = name, mimeType = null, sizeBytes = 1, modifiedAt = 1,
                title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null, latitude = null, longitude = null,
                status = ItemStatus.DONE, error = null, embeddingModelId = "test-model", pipelineKey = "p", progress = 0, indexedAt = 1, place = place,
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
        settings.setChatContextTokens(2048)
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
}
