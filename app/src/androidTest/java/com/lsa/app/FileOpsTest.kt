package com.lsa.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lsa.app.chat.ChatToolNames
import com.lsa.app.chat.actions.ActionManager
import com.lsa.app.chat.actions.ApprovalResult
import com.lsa.app.chat.actions.DeleteFilesTool
import com.lsa.app.chat.actions.FileOps
import com.lsa.app.chat.actions.MoveFilesTool
import com.lsa.app.data.db.ActionKind
import com.lsa.app.data.db.ActionStatus
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ChatSessionEntity
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.media.FolderSpec
import kotlinx.coroutines.runBlocking
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

/** Move / trash / delete of plain files against a temporary "storage root", with the index kept in step. */
@RunWith(AndroidJUnit4::class)
class FileOpsTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private var access = true
    private var indexed = listOf(FolderSpec("Documents/", true))
    private lateinit var ops: FileOps
    private lateinit var actions: ActionManager
    private var session = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        root = File(ctx.cacheDir, "fileops-root").also { it.deleteRecursively(); it.mkdirs() }
        ops = FileOps(ctx, db.itemDao(), folders = { indexed }, root = root, allowed = { access })
        actions = ActionManager(db.actionDao(), ops)
        session = db.chatDao().insertSession(ChatSessionEntity(title = "t", enabledTools = "", autoSearch = false, createdAt = 1, updatedAt = 1))
    }

    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private suspend fun addFile(folder: String, name: String): Long {
        val dir = File(root, folder).also { it.mkdirs() }
        val f = File(dir, name).also { it.writeText("hello $name") }
        return db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "fs:${f.absolutePath}", kind = ItemKind.TEXT, uri = Uri.fromFile(f).toString(), folderKey = "$folder/", displayName = name, mimeType = null,
                sizeBytes = 1, modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null, latitude = null,
                longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = "m", pipelineKey = "p", progress = 0, indexedAt = 1,
            ),
        )
    }

    @Test fun trashMovesAPlainFileToTheHiddenTrashAndDropsItFromTheIndex() = runBlocking {
        val id = addFile("Documents", "a.txt")
        val r = ops.trash(listOf(id)).single()
        assertTrue(r.reason, r.ok)
        assertFalse(File(root, "Documents/a.txt").exists())
        assertEquals(1, File(root, ".LSA_Trash").listFiles { f -> f.name.endsWith("a.txt") }!!.size)
        assertNull(db.itemDao().getItem(id))
    }

    @Test fun deleteRemovesTheFileAndTheRow() = runBlocking {
        val id = addFile("Documents", "a.txt")
        assertTrue(ops.delete(listOf(id)).single().ok)
        assertFalse(File(root, "Documents/a.txt").exists())
        assertNull(db.itemDao().getItem(id))
    }

    @Test fun moveKeepsTheIndexRowAndRenamesOnAClash() = runBlocking {
        val id = addFile("Documents", "a.txt")
        File(root, "Documents/Taxes").mkdirs(); File(root, "Documents/Taxes/a.txt").writeText("other")
        val r = ops.move(listOf(id), "Documents/Taxes").single()
        assertTrue(r.reason, r.ok)
        val item = db.itemDao().getItem(id)!!
        assertEquals("Documents/Taxes/", item.folderKey)
        assertEquals("a (1).txt", item.displayName)
        assertEquals("fs:" + File(root, "Documents/Taxes/a (1).txt").absolutePath, item.sourceKey)
        assertTrue(File(root, "Documents/Taxes/a (1).txt").exists())
        assertEquals("the other file is untouched", "other", File(root, "Documents/Taxes/a.txt").readText())
    }

    @Test fun movingOutOfTheIndexedFoldersDropsTheRow() = runBlocking {
        val id = addFile("Documents", "a.txt")
        assertTrue(ops.move(listOf(id), "Archive").single().ok)
        assertTrue(File(root, "Archive/a.txt").exists())
        assertNull("no longer in an indexed folder", db.itemDao().getItem(id))
    }

    @Test fun withoutAllFilesAccessNothingChanges() = runBlocking {
        val id = addFile("Documents", "a.txt")
        access = false
        val r = ops.trash(listOf(id)).single()
        assertFalse(r.ok); assertEquals(FileOps.NEEDS_ACCESS, r.reason)
        assertTrue(File(root, "Documents/a.txt").exists()); assertNotNull(db.itemDao().getItem(id))
    }

    @Test fun badDestinationsAreRefused() = runBlocking {
        val id = addFile("Documents", "a.txt")
        for (bad in listOf("", "../x", "Android/data", ".hidden/x", "/storage/emulated/0/x")) assertFalse(bad, ops.move(listOf(id), bad).single().ok)
        assertTrue(File(root, "Documents/a.txt").exists())
    }

    @Test fun aMoveCardMovesOnlyAfterApprovalAndOnlyOnce() = runBlocking {
        val id = addFile("Documents", "a.txt")
        val res = MoveFilesTool(actions, db.itemDao(), session).execute(JSONObject().put("item_ids", "$id").put("destination", "Documents/Done"))
        val actionId = JSONObject(res).getLong("action_id")
        assertTrue("proposing moves nothing", File(root, "Documents/a.txt").exists())
        assertEquals(ActionKind.MOVE_FILES, db.actionDao().get(actionId)!!.kind)
        assertTrue(actions.approve(actionId) is ApprovalResult.Done)
        assertTrue(File(root, "Documents/Done/a.txt").exists())
        assertEquals(ActionStatus.DONE, db.actionDao().get(actionId)!!.status)
        assertTrue(actions.approve(actionId) is ApprovalResult.Failed)
    }

    @Test fun aDeleteCardGoesToTheTrashUnlessPermanentIsChosen() = runBlocking {
        val a = addFile("Documents", "a.txt"); val b = addFile("Documents", "b.txt")
        val tool = DeleteFilesTool(actions, db.itemDao(), session)
        val c1 = JSONObject(tool.execute(JSONObject().put("item_ids", "$a"))).getLong("action_id")
        val c2 = JSONObject(tool.execute(JSONObject().put("item_ids", "$b"))).getLong("action_id")
        assertTrue(actions.approve(c1) is ApprovalResult.Done)
        assertEquals("trashed, still recoverable", 1, File(root, ".LSA_Trash").listFiles { f -> f.name.endsWith("a.txt") }!!.size)
        assertTrue(actions.approve(c2, permanent = true) is ApprovalResult.Done)
        assertTrue("permanent leaves nothing in the trash", File(root, ".LSA_Trash").listFiles { f -> f.name.endsWith("b.txt") }!!.isEmpty())
    }

    @Test fun anApprovalWithoutAccessKeepsTheCardOpen() = runBlocking {
        val id = addFile("Documents", "a.txt")
        val actionId = JSONObject(MoveFilesTool(actions, db.itemDao(), session).execute(JSONObject().put("item_ids", "$id").put("destination", "X"))).getLong("action_id")
        access = false
        val r = actions.approve(actionId)
        assertTrue(r is ApprovalResult.Failed && r.reason == FileOps.NEEDS_ACCESS)
        assertEquals(ActionStatus.PROPOSED, db.actionDao().get(actionId)!!.status)
        access = true
        assertTrue(actions.approve(actionId) is ApprovalResult.Done)
    }

    @Test fun toolsRejectUnknownIdsAndBadArguments() = runBlocking {
        val move = MoveFilesTool(actions, db.itemDao(), session)
        assertTrue(move.execute(JSONObject().put("item_ids", "999").put("destination", "X")).startsWith("Error"))
        assertTrue(move.execute(JSONObject().put("item_ids", "").put("destination", "X")).startsWith("Error"))
        val id = addFile("Documents", "a.txt")
        assertTrue(move.execute(JSONObject().put("item_ids", "$id").put("destination", "../x")).startsWith("Error"))
        assertEquals(ChatToolNames.MOVE, move.name)
    }

    /** A real MediaStore image owned by this test app: moving changes RELATIVE_PATH (id kept), trashing uses the system trash. */
    private suspend fun addMediaImage(name: String): Pair<Long, Uri> {
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LsaTestFrom/")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        val mediaId = android.content.ContentUris.parseId(uri)
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "ms:image:$mediaId", kind = ItemKind.IMAGE, uri = uri.toString(), folderKey = "Pictures/LsaTestFrom/", displayName = name, mimeType = "image/png",
                sizeBytes = 1, modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 4, height = 4, dateTaken = null, latitude = null,
                longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = "m", pipelineKey = "p", progress = 0, indexedAt = 1,
            ),
        )
        return id to uri
    }

    @Test fun aMediaStoreImageMovesByRelativePathAndKeepsItsRow() = runBlocking {
        val storage = android.os.Environment.getExternalStorageDirectory()
        indexed = listOf(FolderSpec("Pictures/", true))
        val msOps = FileOps(ctx, db.itemDao(), folders = { indexed }, root = storage, allowed = { true })
        val (id, uri) = addMediaImage("lsa_move_${System.nanoTime()}.png")
        try {
            val r = msOps.move(listOf(id), "Pictures/LsaTestTo").single()
            assertTrue(r.reason, r.ok)
            assertEquals("Pictures/LsaTestTo/", db.itemDao().getItem(id)!!.folderKey)
            ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { c ->
                c.moveToFirst(); assertEquals("Pictures/LsaTestTo/", c.getString(0))
            }
        } finally { ctx.contentResolver.delete(uri, null, null) }
    }

    @Test fun aMediaStoreImageGoesToTheSystemTrashWhereItCanBeRestored() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT < 30) return@runBlocking
        val msOps = FileOps(ctx, db.itemDao(), folders = { indexed }, root = android.os.Environment.getExternalStorageDirectory(), allowed = { true })
        val (id, uri) = addMediaImage("lsa_trash_${System.nanoTime()}.png")
        try {
            val r = msOps.trash(listOf(id)).single()
            assertTrue(r.reason, r.ok)
            assertNull(db.itemDao().getItem(id))
            val trashed = ctx.contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_TRASHED),
                android.os.Bundle().apply { putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE) }, null,
            )!!.use { c -> c.moveToFirst() && c.getInt(0) == 1 }
            assertTrue("kept in the system trash", trashed)
        } finally { ctx.contentResolver.delete(uri, android.os.Bundle().apply { putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE) }) }
    }
}
