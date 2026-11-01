package com.lsa.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.FolderSpec
import com.lsa.app.data.media.ItemSource
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.IndexPlan
import com.lsa.app.indexing.IndexProgress
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.indexing.IndexSession
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexRunnerTest {
    private lateinit var db: AppDatabase
    private lateinit var runner: IndexRunner
    private var entries = mutableListOf<SourceEntry>()
    private val calls = mutableListOf<String>()
    private val resumes = mutableListOf<Long>()
    private var failOn: String? = null
    private var skipOn: String? = null
    private val folder = listOf(FolderSpec("Pictures/a/"))
    private val kinds = setOf(ItemKind.IMAGE)

    private val source = object : ItemSource {
        override suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>) = entries.filter { e ->
            folders.any { f -> e.folderKey == f.relativePath || (f.recursive && e.folderKey.startsWith(f.relativePath)) }
        }
    }

    private fun entry(id: Long, mod: Long = 1, path: String = "Pictures/a/", name: String = "f$id.jpg") = SourceEntry(
        sourceKey = "ms:image:$id", kind = ItemKind.IMAGE, uri = Uri.parse("content://x/$id"), folderKey = path,
        displayName = name, mimeType = "image/jpeg", sizeBytes = 10, modifiedAt = mod,
    )

    private val vec = Quant.encode(floatArrayOf(1f, 0f))

    private fun session(model: String) = object : IndexSession {
        override val embeddingModelId = model
        override suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink) {
            calls += entry.sourceKey; resumes += resume
            if (entry.sourceKey == failOn) error("boom")
            if (entry.sourceKey == skipOn) throw SkipItem("unsupported")
            sink.segments(listOf(NewSegment(SegmentType.IMAGE, 0, embedding = vec)), progress = 1)
        }
    }

    private fun key(model: String): (ItemKind) -> String = { "$model|v1" }

    private suspend fun run(model: String, folders: List<FolderSpec> = folder): IndexProgress {
        val plan = runner.sync(folders, kinds, key(model))
        return runner.process(plan, session(model), key(model)) {}
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        runner = IndexRunner(db.itemDao(), source)
    }
    @After fun tearDown() = db.close()

    private suspend fun item(id: Long) = db.itemDao().getItemBySourceKey("ms:image:$id")

    @Test fun indexesNewThenSkipsUnchanged() = runBlocking {
        entries += listOf(entry(1), entry(2), entry(3))
        assertEquals(3, run("m1").done); assertEquals(3, calls.size)
        assertEquals(1, db.itemDao().segmentsOf(item(1)!!.itemId).size)
        calls.clear()
        run("m1")
        assertEquals("nothing re-indexed", 0, calls.size)
    }

    @Test fun reindexesChangedFilesAndPipelineChange() = runBlocking {
        entries += listOf(entry(1), entry(2))
        run("m1"); calls.clear()
        entries[0] = entry(1, mod = 99)
        run("m1")
        assertEquals(listOf("ms:image:1"), calls)
        calls.clear()
        run("m2")
        assertEquals(setOf("ms:image:1", "ms:image:2"), calls.toSet())
        assertEquals("old rows replaced, not duplicated", 2, db.itemDao().states().size)
        assertEquals("m2", item(2)!!.embeddingModelId)
    }

    @Test fun recordsFailuresAndPrunesDeletedFiles() = runBlocking {
        entries += listOf(entry(1), entry(2))
        failOn = "ms:image:2"
        val p = run("m1")
        assertEquals(1, p.failed)
        assertEquals(ItemStatus.FAILED, item(2)!!.status)
        assertTrue("failed item keeps no segments", db.itemDao().segmentsOf(item(2)!!.itemId).isEmpty())
        entries.removeAt(0)
        run("m1")
        assertNull(item(1)); assertNotNull(item(2))
    }

    @Test fun skippedItemsAreNotRetried() = runBlocking {
        entries += listOf(entry(1))
        skipOn = "ms:image:1"
        run("m1")
        assertEquals(ItemStatus.SKIPPED, item(1)!!.status); assertEquals("unsupported", item(1)!!.error)
        calls.clear()
        run("m1")
        assertTrue(calls.isEmpty())
    }

    @Test fun onlySelectedFoldersAndSubfolders() = runBlocking {
        entries += listOf(entry(1, path = "Pictures/a/"), entry(2, path = "Pictures/b/"), entry(3, path = "Pictures/b/deep/"))
        run("m1", listOf(FolderSpec("Pictures/b/", recursive = false)))
        assertEquals(listOf("ms:image:2"), calls)
        calls.clear()
        run("m1", listOf(FolderSpec("Pictures/b/", recursive = true)))
        assertEquals("only the subfolder item is new", listOf("ms:image:3"), calls)
    }

    @Test fun syncPrunesDeletedFilesWithoutAnyModel() = runBlocking {
        entries += listOf(entry(1), entry(2), entry(3))
        run("m1")
        entries.removeAll { it.sourceKey == "ms:image:2" }
        val plan = runner.sync(folder, kinds, key("m1")) // no session involved
        assertTrue(plan.todo.isEmpty())
        assertNull(item(2)); assertNotNull(item(1)); assertNotNull(item(3))
    }

    @Test fun movedFileKeepsSegmentsAndUpdatesLocation() = runBlocking {
        entries += listOf(entry(1))
        run("m1"); calls.clear()
        entries[0] = entry(1, name = "renamed.jpg")
        val plan = runner.sync(folder, kinds, key("m1"))
        assertTrue("no re-embedding for a rename", plan.todo.isEmpty())
        assertEquals("renamed.jpg", item(1)!!.displayName)
        assertEquals(1, db.itemDao().segmentsOf(item(1)!!.itemId).size)
    }

    @Test fun emptyListingDoesNotWipeTheIndex() = runBlocking {
        entries += listOf(entry(1), entry(2))
        run("m1")
        entries.clear() // e.g. permission revoked / storage unmounted
        val plan: IndexPlan = runner.sync(folder, kinds, key("m1"))
        assertTrue(plan.todo.isEmpty())
        assertNotNull(item(1)); assertNotNull(item(2))
    }

    @Test fun otherKindsAreLeftAlone() = runBlocking {
        entries += listOf(entry(1))
        run("m1")
        entries.clear()
        // Looking only at TEXT must neither delete the image nor be confused by the empty listing.
        runner.sync(folder, setOf(ItemKind.TEXT), key("m1"))
        assertNotNull(item(1))
    }

    @Test fun gateStopsEarlyAndResumesLater() = runBlocking {
        entries += listOf(entry(1), entry(2), entry(3))
        var allowed = 1
        val plan = runner.sync(folder, kinds, key("m1"))
        val p = runner.process(plan, session("m1"), key("m1"), gate = { allowed-- > 0 }) {}
        assertTrue(p.stoppedEarly); assertEquals(1, p.done)
        calls.clear()
        run("m1")
        assertEquals("only the remaining two", 2, calls.size)
    }

    @Test fun interruptedItemResumesFromItsCheckpoint() = runBlocking {
        entries += listOf(entry(1))
        val first = object : IndexSession {
            override val embeddingModelId = "m1"
            override suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink) {
                sink.segments(listOf(NewSegment(SegmentType.TEXT_CHUNK, 0, text = "a", embedding = vec)), progress = 5)
                throw CancellationException("stopped")
            }
        }
        try { runner.process(runner.sync(folder, kinds, key("m1")), first, key("m1")) {} } catch (_: CancellationException) {}
        assertEquals(ItemStatus.IN_PROGRESS, item(1)!!.status)
        assertEquals(5L, item(1)!!.progress)

        val second = object : IndexSession {
            override val embeddingModelId = "m1"
            override suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink) {
                resumes += resume
                sink.segments(listOf(NewSegment(SegmentType.TEXT_CHUNK, 1, text = "b", embedding = vec)), progress = 9)
            }
        }
        runner.process(runner.sync(folder, kinds, key("m1")), second, key("m1")) {}
        assertEquals(listOf(5L), resumes)
        assertEquals(ItemStatus.DONE, item(1)!!.status)
        assertEquals("segments from both runs kept", listOf("a", "b"), db.itemDao().segmentsOf(item(1)!!.itemId).map { it.text })
    }
}
