package com.lsa.app

import android.content.Context
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
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.search.SearchEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchEngineTest {
    private lateinit var db: AppDatabase
    private var queryVec: FloatArray? = floatArrayOf(1f, 0f, 0f, 0f)
    private lateinit var engine: SearchEngine
    /** Tests must not depend on whatever model the app on this device happens to have selected. */
    private val model = "test-model"

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val settings = SettingsStore(ctx)
        runBlocking { settings.setEmbeddingModelId(model) }
        engine = SearchEngine(db.itemDao(), { queryVec }, settings)
    }
    @After fun tearDown() { db.close(); runBlocking { SettingsStore(ApplicationProvider.getApplicationContext<Context>()).setEmbeddingModelId(SettingsStore.DEFAULT_EMBEDDING) } }

    private fun v(vararg x: Float) = Quant.encode(com.lsa.app.ml.embedding.VectorMath.normalize(x))

    private suspend fun add(name: String, kind: ItemKind, vararg segs: SegmentEntity): Long {
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:$name", kind = kind, uri = "content://$name", folderKey = "F/", displayName = name, mimeType = null,
                sizeBytes = 1, modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null,
                latitude = null, longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = model, pipelineKey = "p",
                progress = 0, indexedAt = 1,
            ),
        )
        db.itemDao().insertSegments(segs.map { it.copy(itemId = id) })
        return id
    }

    private fun seg(type: SegmentType, ordinal: Int, emb: ByteArray?, text: String? = null, page: Int? = null, startMs: Long? = null) =
        SegmentEntity(itemId = 0, type = type, ordinal = ordinal, startMs = startMs, endMs = null, page = page, text = text, embedding = emb, thumbPath = null)

    /** Noise items whose vectors are far from the query in every space. */
    private suspend fun noise(n: Int) {
        for (i in 1..n) add("noise$i", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.05f * i, 1f, 0.1f, 0f)))
    }

    @Test fun clearVisualWinnerRanksFirstAndNoiseIsDropped() = runBlocking {
        val cat = add("cat", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0.1f, 0f, 0f)))
        noise(5)
        val r = engine.search("cat")
        assertEquals(cat, r.hits.first().itemId)
        assertTrue(r.semanticAvailable)
        assertTrue("only the clear match survives the gate", r.hits.size <= 2)
    }

    @Test fun segmentsOfOneItemCollapseIntoOneHitWithBestLocation() = runBlocking {
        val doc = add(
            "report.pdf", ItemKind.DOC,
            seg(SegmentType.TEXT_CHUNK, 0, v(0f, 1f, 0f, 0f), page = 1),
            seg(SegmentType.TEXT_CHUNK, 1, v(1f, 0f, 0f, 0f), page = 7),
            seg(SegmentType.PAGE_IMAGE, 2, v(0.9f, 0.1f, 0f, 0f), page = 7),
        )
        noise(5)
        val r = engine.search("budget")
        val hit = r.hits.single { it.itemId == doc }
        assertEquals("page of the best segment", 7, hit.best!!.page)
        assertNotNull(hit.textSim); assertNotNull(hit.visualSim)
        assertEquals(1, r.hits.count { it.itemId == doc })
    }

    @Test fun keywordMatchWorksWithoutAnyModel() = runBlocking {
        queryVec = null
        val m = add("memo.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "회의록 2026 프로젝트 일정"))
        add("other.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "grocery list"))
        val r = engine.search("회의록")
        assertFalse(r.semanticAvailable)
        assertEquals(listOf(m), r.hits.map { it.itemId })
        assertTrue(r.hits.first().exactKeyword)
    }

    @Test fun exactKeywordIsPinnedAboveSemanticMatches() = runBlocking {
        val vis = add("pic", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0f, 0f, 0f)))
        val kw = add("inv.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0f, 1f, 0f, 0f)), seg(SegmentType.OCR_TEXT, 1, v(0f, 0f, 1f, 0f), text = "Invoice #20481"))
        noise(5)
        val r = engine.search("invoice")
        assertEquals(kw, r.hits.first().itemId)
        assertTrue(r.hits.any { it.itemId == vis })
    }

    @Test fun audioSegmentsAreScoredInTheirOwnGroup() = runBlocking {
        val clip = add("talk.m4a", ItemKind.AUDIO, seg(SegmentType.AUDIO_CLIP, 0, v(1f, 0f, 0f, 0f), startMs = 30_000))
        for (i in 1..5) add("a$i", ItemKind.AUDIO, seg(SegmentType.AUDIO_CLIP, 0, v(0.05f * i, 1f, 0f, 0f), startMs = 0))
        val r = engine.search("meeting")
        val hit = r.hits.first()
        assertEquals(clip, hit.itemId)
        assertNotNull(hit.audioSim); assertEquals(30_000L, hit.best!!.startMs)
    }

    @Test fun likeWildcardsInQueriesAreTreatedLiterally() = runBlocking {
        queryVec = null
        add("a", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "discount 50% off"))
        add("b", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "snake_case name"))
        assertEquals(1, engine.search("50%").hits.size)
        assertEquals(1, engine.search("snake_case").hits.size)
        assertEquals("'%' must not match everything", 0, engine.search("zzz%").hits.size)
    }

    @Test fun onlyItemsOfTheSelectedModelAreSearched() = runBlocking {
        val other = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:old", kind = ItemKind.IMAGE, uri = "u", folderKey = "F/", displayName = "old", mimeType = null, sizeBytes = 1,
                modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null, latitude = null,
                longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = "some-other-model", pipelineKey = "p", progress = 0, indexedAt = 1,
            ),
        )
        db.itemDao().insertSegments(listOf(seg(SegmentType.IMAGE, 0, v(1f, 0f, 0f, 0f)).copy(itemId = other)))
        noise(5)
        assertTrue(engine.search("x").hits.none { it.itemId == other })
    }

    @Test fun transcriptHitInAVideoIsNarrowedToTheMatchingClip() = runBlocking {
        val video = add(
            "scenes.mp4", ItemKind.VIDEO,
            seg(SegmentType.VIDEO_CLIP, 0, v(0f, 1f, 0f, 0f), startMs = 0).copy(endMs = 6_000),
            seg(SegmentType.VIDEO_CLIP, 1, v(1f, 0f, 0f, 0f), startMs = 6_000).copy(endMs = 12_000),
            seg(SegmentType.VIDEO_CLIP, 2, v(0f, 0f, 1f, 0f), startMs = 12_000).copy(endMs = 18_000),
            seg(SegmentType.TRANSCRIPT, 3, null, text = "a cat then a delicious pizza with basil", startMs = 0).copy(endMs = 8_500),
        )
        for (i in 1..5) add("clip$i", ItemKind.VIDEO, seg(SegmentType.VIDEO_CLIP, 0, v(0.05f * i, 0.1f, 1f, 0f), startMs = 0).copy(endMs = 5_000))
        val hit = engine.search("pizza").hits.first { it.itemId == video }
        assertEquals("the pizza clip, not the start of the 8.5 s transcript chunk", 6_000L, hit.best!!.startMs)
        assertEquals(SegmentType.VIDEO_CLIP, hit.best!!.type)
    }
}
