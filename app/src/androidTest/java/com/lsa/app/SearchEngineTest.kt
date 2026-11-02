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
import com.lsa.app.search.MetaMatch
import com.lsa.app.search.SearchEngine
import com.lsa.app.search.SearchFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private suspend fun add(name: String, kind: ItemKind, vararg segs: SegmentEntity, place: String? = null, dateTaken: Long? = null): Long {
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:$name", kind = kind, uri = "content://$name", folderKey = "F/", displayName = name, mimeType = null,
                sizeBytes = 1, modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = dateTaken,
                latitude = null, longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = model, pipelineKey = "p",
                progress = 0, indexedAt = 1, place = place,
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

    // ---- metadata, filters, agreement

    @Test fun aPlaceQueryFindsPhotosByTheirLocationAlone() = runBlocking {
        val dubai = add("IMG_1.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.1f, 1f, 0f, 0f)), place = "Dubai 두바이 United Arab Emirates 아랍에미리트 연합")
        val seoul = add("IMG_2.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.1f, 1f, 0.2f, 0f)), place = "Seoul 서울특별시 South Korea 대한민국")
        noise(5)
        val hit = engine.search("dubai").hits.first()
        assertEquals(dubai, hit.itemId); assertEquals(MetaMatch.PLACE, hit.metaMatch); assertTrue(hit.exactKeyword)
        assertEquals("Korean names match as substrings", dubai, engine.search("두바이").hits.first().itemId)
        assertNull("the Seoul photo never matches on place", engine.search("두바이").hits.firstOrNull { it.itemId == seoul }?.metaMatch)
    }

    @Test fun placePlusMeaningOutranksPlaceAlone() = runBlocking {
        val both = add("a.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0.1f, 0f, 0f)), place = "Dubai")
        val placeOnly = add("b.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.1f, 1f, 0f, 0f)), place = "Dubai")
        noise(5)
        val hits = engine.search("dubai").hits
        assertEquals(listOf(both, placeOnly), hits.map { it.itemId }.take(2))
        assertNotNull(hits.first().visualSim)
    }

    @Test fun fileNamesAreMetadataToo() = runBlocking {
        val id = add("quarterly_budget.xlsx", ItemKind.DOC, seg(SegmentType.TEXT_CHUNK, 0, v(0f, 1f, 0f, 0f), text = "numbers"))
        noise(3)
        val hit = engine.search("budget").hits.first { it.itemId == id }
        assertEquals(MetaMatch.NAME, hit.metaMatch)
    }

    @Test fun kindFilterRestrictsResultsBeforeScoring() = runBlocking {
        val doc = add("plan.docx", ItemKind.DOC, seg(SegmentType.TEXT_CHUNK, 0, null, text = "launch plan"))
        val img = add("slide.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, null, text = "launch plan"))
        assertEquals(setOf(doc, img), engine.search("launch").hits.map { it.itemId }.toSet())
        assertEquals(listOf(img), engine.search("launch", SearchFilter(kinds = setOf(ItemKind.IMAGE))).hits.map { it.itemId })
        assertEquals(listOf(doc), engine.search("launch", SearchFilter(kinds = setOf(ItemKind.TEXT, ItemKind.DOC))).hits.map { it.itemId })
    }

    @Test fun dateFilterUsesTakenDateThenModifiedDate() = runBlocking {
        val old = add("old.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, null, text = "receipt"), dateTaken = 1_000L)
        val recent = add("new.jpg", ItemKind.IMAGE, seg(SegmentType.OCR_TEXT, 0, null, text = "receipt"), dateTaken = 5_000L)
        val noDate = add("nodate.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "receipt")) // modifiedAt = 1
        fun ids(f: SearchFilter) = runBlocking { engine.search("receipt", f).hits.map { it.itemId }.toSet() }
        assertEquals(setOf(recent), ids(SearchFilter(fromMs = 2_000L)))
        assertEquals(setOf(old, recent), ids(SearchFilter(fromMs = 1_000L, toMs = 5_000L)))
        assertEquals("boundaries are inclusive; the file without a taken date falls back to modifiedAt", setOf(noDate), ids(SearchFilter(toMs = 1L)))
    }

    @Test fun filtersWithoutAQueryBrowseNewestFirst() = runBlocking {
        val a = add("a.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0f, 0f, 0f)), dateTaken = 100L)
        val b = add("b.jpg", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0f, 1f, 0f, 0f)), dateTaken = 300L)
        add("c.mp3", ItemKind.AUDIO, seg(SegmentType.AUDIO_CLIP, 0, v(0f, 0f, 1f, 0f)), dateTaken = 200L)
        assertEquals(listOf(b, a), engine.search("", SearchFilter(kinds = setOf(ItemKind.IMAGE))).hits.map { it.itemId })
        assertTrue("no query and no filter returns nothing", engine.search("").hits.isEmpty())
    }

    /** Vectors that all sit near the same far-off direction, with a little spread, so the index has a baseline. */
    private suspend fun baseline(n: Int) {
        for (i in 1..n) add("base$i", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.01f * (i % 7), 1f, (i % 5) * 0.02f, 0f)))
    }

    @Test fun matchItemsJudgesEachNewItemAgainstTheIndexNotAgainstTheBestFile() = runBlocking {
        baseline(30)
        val strong = add("strong", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0.1f, 0f, 0f)))
        val unrelated = add("unrelated", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.03f, 1f, 0.05f, 0f)))
        val m = engine.matchItems("cat", setOf(strong, unrelated))
        assertTrue(strong in m); assertFalse("an unrelated new file is not a match", unrelated in m)
    }

    @Test fun anExistingBetterMatchDoesNotHideANewGoodOne() = runBlocking {
        baseline(30)
        add("old-cat", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0f, 0f, 0f)))
        val fresh = add("new-cat", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(1f, 0.5f, 0f, 0f)))
        assertTrue(fresh in engine.matchItems("cat", setOf(fresh)))
    }

    @Test fun matchItemsWhenNothingInTheIndexMatchesStillRejectsUnrelatedFiles() = runBlocking {
        baseline(30)
        val unrelated = add("unrelated", ItemKind.IMAGE, seg(SegmentType.IMAGE, 0, v(0.02f, 1f, 0.04f, 0f)))
        assertTrue(engine.matchItems("cat", setOf(unrelated)).isEmpty())
    }

    @Test fun matchItemsKeywordsNeedEveryWordAndWorkWithoutAModel() = runBlocking {
        queryVec = null
        val both = add("a.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "quarterly budget review"))
        val one = add("b.txt", ItemKind.TEXT, seg(SegmentType.TEXT_CHUNK, 0, null, text = "the budget airline"))
        val m = engine.matchItems("budget review", setOf(both, one))
        assertEquals(setOf(both), m.keys); assertTrue(m.getValue(both).keyword)
        val byName = add("budget-review.pdf", ItemKind.DOC)
        assertTrue(byName in engine.matchItems("budget review", setOf(byName)))
    }
}
