package com.lsa.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.indexing.faces.FaceIndexer
import com.lsa.app.ml.ModelHost
import com.lsa.app.ml.faces.Face
import com.lsa.app.ml.faces.FaceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The face pass with a fake detector (no OndolSaem needed): which photos are scanned, grouping, "not this person",
 * turning face grouping off and on again (faces only, the search index stays).
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var faces: FaceIndexer
    private var savedGrouping = true
    private var savedThreshold = 0f
    private var savedKey = ""
    private val detected = mutableListOf<Int>() // photo sizes the detector saw

    /** Unit vector pointing at axis [person] with a little of axis 15: the same person scores ~1, different ~0. */
    private fun vectorOf(person: Int, wobble: Float) = FloatArray(16).also { it[person] = 1f; it[15] = wobble }.let { v ->
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(16) { v[it] / n }
    }

    /** The photo's width says who is in it: width = 100 + 10 * person (one face), 300 = persons 1 and 2. */
    private val detector = object : FaceDetector {
        override val modelId = "fake-face"
        override suspend fun detect(jpeg: ByteArray): List<Face> {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            detected += bmp.width
            val people = if (bmp.width == 300) listOf(1, 2) else listOf((bmp.width - 100) / 10)
            return people.mapIndexed { i, p -> Face(0.1f + i * 0.4f, 0.1f, 0.4f + i * 0.4f, 0.6f, 0.9f, vectorOf(p, 0.1f * detected.size)) }
        }
    }

    private suspend fun photo(name: String, width: Int): Long {
        val f = File(ctx.cacheDir, "faces-test/$name.png").apply { parentFile!!.mkdirs() }
        f.outputStream().use { Bitmap.createBitmap(width, 120, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "fs:${f.path}", kind = ItemKind.IMAGE, uri = Uri.fromFile(f).toString(), folderKey = "T/", displayName = "$name.png",
                mimeType = "image/png", sizeBytes = f.length(), modifiedAt = 1, title = null, durationMs = null, pageCount = null,
                width = width, height = 120, dateTaken = null, latitude = null, longitude = null, status = ItemStatus.DONE, error = null,
                embeddingModelId = "test-model", pipelineKey = "p", progress = 0, indexedAt = 1,
            ),
        )
        db.itemDao().insertSegments(listOf(SegmentEntity(itemId = id, type = SegmentType.IMAGE, ordinal = 0, startMs = null, endMs = null, page = null, text = null, embedding = byteArrayOf(1, 2, 3, 4, 5), thumbPath = null)))
        return id
    }

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        settings = SettingsStore(ctx)
        savedGrouping = settings.faceGrouping.first()
        savedThreshold = settings.faceThreshold.first(); savedKey = settings.faceGroupingKey.first()
        settings.setFaceGrouping(true)
        val host = ModelHost(ctx, settings, scope).apply { faceOverride = { detector } }
        faces = FaceIndexer(ctx, db.faceDao(), ThumbStore(ctx), host, settings)
    }

    @After fun tearDown() = runBlocking<Unit> {
        settings.setFaceGrouping(savedGrouping)
        settings.setFaceThreshold(savedThreshold); settings.setFaceGroupingKey(savedKey)
        db.close(); scope.cancel()
        File(ctx.cacheDir, "faces-test").deleteRecursively()
    }

    private suspend fun people() = db.faceDao().observePeople().first()

    @Test fun facesOfTheSamePersonEndUpInOneGroup() = runBlocking<Unit> {
        photo("a1", 110); photo("a2", 110); photo("b1", 120); photo("ab", 300) // a = person 1, b = person 2
        assertEquals(4, faces.pass { true })
        val groups = people()
        assertEquals("two people", 2, groups.size)
        assertEquals(listOf(3, 2), groups.map { it.photoCount })
        assertTrue("crops saved", groups.all { it.thumbPath != null })
        assertEquals("nothing left to scan", 0, faces.pass { true })
        assertEquals(4, detected.size)
    }

    @Test fun aNewPhotoJoinsItsPersonAndNamesStay() = runBlocking<Unit> {
        photo("a1", 110); photo("a2", 110)
        faces.pass { true }
        val a = people().single()
        db.faceDao().rename(a.personId, "Mina", 1)
        photo("a3", 110); photo("c1", 130)
        faces.pass { true }
        val mina = people().single { it.name == "Mina" }
        assertEquals(a.personId, mina.personId)
        assertEquals(3, mina.photoCount)
        assertEquals(2, people().size)
    }

    @Test fun notThisPersonMovesTheFaceToAnotherGroup() = runBlocking<Unit> {
        photo("a1", 110); photo("a2", 110); photo("a3", 110)
        faces.pass { true }
        val a = people().single()
        val face = db.faceDao().observeFacesOf(a.personId).first().first()
        db.faceDao().reject(face.faceId)
        faces.regroup()
        val after = db.faceDao().facesOfItem(face.itemId).single()
        assertNotEquals("never back into the group it was taken out of", a.personId, after.personId)
        assertEquals(2, people().first { it.personId == a.personId }.photoCount)
    }

    @Test fun offDeletesFacesAndOnScansFacesOnly() = runBlocking<Unit> {
        val id = photo("a1", 110); photo("b1", 120)
        faces.pass { true }
        settings.setFaceGrouping(false)
        faces.deleteAll()
        assertEquals(0, faces.pass { true })
        assertTrue(people().isEmpty())
        assertEquals("search index untouched", 1, db.itemDao().segmentsOf(id).size)
        settings.setFaceGrouping(true)
        assertEquals("faces scanned again", 2, faces.pass { true })
        assertEquals(2, people().size)
        assertEquals("still the same search segment", 1, db.itemDao().segmentsOf(id).size)
    }

    @Test fun theGateStopsThePassAndTheNextOneContinues() = runBlocking<Unit> {
        repeat(4) { photo("a$it", 110) }
        var allowed = 3 // checked once per batch and once per photo
        assertEquals(2, faces.pass { allowed-- > 0 })
        assertEquals(2, faces.pass { true })
    }

    @Test fun anotherThresholdGroupsEverythingAgainAndKeepsTheName() = runBlocking<Unit> {
        // one named group holding two people whose faces are 0.5 alike (mixed at a loose threshold)
        val a = FloatArray(16).also { it[0] = 1f }
        val b = com.lsa.app.indexing.faces.FaceClusterer.normalize(FloatArray(16).also { it[0] = 0.5f; it[1] = 0.866f })
        val dao = db.faceDao()
        val mina = dao.insertPerson(com.lsa.app.data.db.PersonEntity(name = "Mina", createdAt = 1, updatedAt = 1))
        val ids = listOf(a, a, b, b).mapIndexed { i, v ->
            val item = photo("p$i", 110)
            dao.insertFaces(listOf(com.lsa.app.data.db.FaceEntity(itemId = item, left = 0f, top = 0f, right = 1f, bottom = 1f, score = 0.9f - i / 10f,
                vector = com.lsa.app.ml.embedding.Quant.encode(v), personId = mina, thumbPath = null))).single()
        }
        dao.setCover(mina, ids[3], 1)
        assertEquals(2 to 1, faces.previewGroups(0.55f))
        assertEquals("a preview changes nothing", 1, people().size)

        faces.regroupAll(0.55f)
        val split = people()
        assertEquals(2, split.size)
        val named = split.single { it.name == "Mina" }
        assertEquals("the name keeps its id", mina, named.personId)
        assertEquals(listOf(ids[0], ids[1]), dao.observeFacesOf(mina).first().map { it.faceId }.sorted())
        assertEquals("a cover that left the group is reset", null, dao.person(mina)!!.coverFaceId)
        assertEquals(0.55f, settings.faceThreshold.first())
        assertEquals("g2|t0.55", settings.faceGroupingKey.first())

        faces.regroupAll(0.40f)
        assertEquals(listOf(mina), people().map { it.personId })
        assertEquals(4, people().single().faceCount)

        // an install from before 0.11.0 has no grouping key: the next pass groups everything again
        settings.setFaceGroupingKey("")
        faces.ensureGrouping()
        assertEquals("g2|t0.40", settings.faceGroupingKey.first())
        assertEquals(listOf(mina), people().map { it.personId })
    }
}
