package com.lsa.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ImageEntity
import com.lsa.app.data.db.IndexStatus
import com.lsa.app.data.media.MediaImage
import com.lsa.app.data.media.MediaSource
import com.lsa.app.indexing.IndexPlan
import com.lsa.app.indexing.IndexProgress
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.indexing.IndexSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexRunnerTest {
    private lateinit var db: AppDatabase
    private lateinit var runner: IndexRunner
    private var items = mutableListOf<MediaImage>()
    private val calls = mutableListOf<Long>()
    private var failOn: Long? = null
    private val folder = listOf("Pictures/a/")

    private val media = object : MediaSource {
        override suspend fun listImages(folders: Collection<String>) = items.filter { it.relativePath in folders }
    }

    private fun img(id: Long, mod: Long = 1, path: String = "Pictures/a/", name: String = "f$id.jpg") =
        MediaImage(id, Uri.parse("content://x/$id"), path, name, "image/jpeg", 10, 1, 1, null, mod)

    private fun entity(m: MediaImage, model: String, status: String = IndexStatus.DONE) = ImageEntity(
        m.mediaId, m.uri.toString(), m.relativePath, m.displayName, m.mimeType, m.sizeBytes, m.width, m.height,
        m.dateTaken, m.dateModified, null, null, "txt", "fake", byteArrayOf(1), null, model, 1, 0L, status, null,
    )

    private fun session(model: String) = object : IndexSession {
        override val embeddingModelId = model
        override suspend fun index(item: MediaImage): ImageEntity {
            calls += item.mediaId
            if (item.mediaId == failOn) error("boom")
            return entity(item, model)
        }
        override fun failed(item: MediaImage, t: Throwable) = entity(item, model, IndexStatus.FAILED).copy(error = t.message)
    }

    private suspend fun run(model: String, folders: List<String> = folder): IndexProgress {
        val plan = runner.sync(folders, model)
        return runner.process(plan, session(model)) {}
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        runner = IndexRunner(db.imageDao(), media)
    }
    @After fun tearDown() = db.close()

    @Test fun indexesNewThenSkipsUnchanged() = runBlocking {
        items += listOf(img(1), img(2), img(3))
        assertEquals(3, run("m1").done); assertEquals(3, calls.size)
        calls.clear()
        run("m1")
        assertEquals("nothing re-indexed", 0, calls.size)
    }

    @Test fun reindexesChangedFilesAndModelSwitch() = runBlocking {
        items += listOf(img(1), img(2))
        run("m1"); calls.clear()
        items[0] = img(1, mod = 99)
        run("m1")
        assertEquals(listOf(1L), calls)
        calls.clear()
        run("m2")
        assertEquals(setOf(1L, 2L), calls.toSet())
    }

    @Test fun recordsFailuresAndPrunesDeletedFiles() = runBlocking {
        items += listOf(img(1), img(2))
        failOn = 2
        val p = run("m1")
        assertEquals(1, p.failed)
        assertEquals(IndexStatus.FAILED, db.imageDao().get(2)!!.status)
        items.removeAt(0)
        run("m1")
        assertEquals(null, db.imageDao().get(1))
        assertNotNull(db.imageDao().get(2))
    }

    @Test fun onlyEnabledFoldersAreIndexed() = runBlocking {
        items += listOf(img(1, path = "Pictures/a/"), img(2, path = "Pictures/b/"))
        run("m1", listOf("Pictures/b/"))
        assertEquals(listOf(2L), calls)
    }

    @Test fun syncPrunesDeletedFilesWithoutAnyModel() = runBlocking {
        items += listOf(img(1), img(2), img(3))
        run("m1")
        items.removeAll { it.mediaId == 2L }
        val plan = runner.sync(folder, "m1") // no session involved
        assertTrue(plan.todo.isEmpty())
        assertEquals(null, db.imageDao().get(2))
        assertNotNull(db.imageDao().get(1)); assertNotNull(db.imageDao().get(3))
    }

    @Test fun movedFileKeepsEmbeddingsAndUpdatesPath() = runBlocking {
        items += listOf(img(1))
        run("m1"); calls.clear()
        items[0] = img(1, path = "Pictures/a/", name = "renamed.jpg")
        val plan = runner.sync(folder, "m1")
        assertTrue("no re-embedding for a rename", plan.todo.isEmpty())
        assertEquals("renamed.jpg", db.imageDao().get(1)!!.displayName)
        assertTrue(calls.isEmpty())
    }

    @Test fun emptyListingDoesNotWipeTheIndex() = runBlocking {
        items += listOf(img(1), img(2))
        run("m1")
        items.clear() // e.g. permission revoked / storage unmounted
        val plan: IndexPlan = runner.sync(folder, "m1")
        assertTrue(plan.todo.isEmpty())
        assertNotNull(db.imageDao().get(1)); assertNotNull(db.imageDao().get(2))
    }

    @Test fun gateStopsEarlyAndResumesLater() = runBlocking {
        items += listOf(img(1), img(2), img(3))
        var allowed = 1
        val plan = runner.sync(folder, "m1")
        val p = runner.process(plan, session("m1"), gate = { allowed-- > 0 }) {}
        assertTrue(p.stoppedEarly); assertEquals(1, p.done)
        calls.clear()
        run("m1")
        assertEquals("only the remaining two", 2, calls.size)
    }
}
