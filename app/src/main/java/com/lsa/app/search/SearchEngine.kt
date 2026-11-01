package com.lsa.app.search

import android.util.Log
import com.lsa.app.data.db.ImageDao
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ml.ModelResidency
import com.lsa.app.ml.embedding.VectorMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SearchHit(
    val mediaId: Long,
    val score: Float,
    val imageSim: Float?,
    val ocrSim: Float?,
    val keywordMatches: Int,
    val exactKeyword: Boolean,
    val snippet: String?,
)

data class SearchResult(val hits: List<SearchHit>, val semanticAvailable: Boolean)

class SearchEngine(
    private val dao: ImageDao,
    private val residency: ModelResidency,
    private val settings: SettingsStore,
) {
    private class Vec(val id: Long, val image: FloatArray?, val ocr: FloatArray?)

    private val cacheMutex = Mutex()
    private var cacheKey: String? = null
    private var vectors: List<Vec> = emptyList()
    private var texts: List<Pair<Long, String>> = emptyList()

    private suspend fun loadCache(modelId: String) = cacheMutex.withLock {
        val key = "$modelId|${dao.stamp()}"
        if (key != cacheKey) {
            vectors = dao.vectors(modelId).map {
                Vec(it.mediaId, it.imageEmb?.let(VectorMath::fromBytes), it.ocrEmb?.let(VectorMath::fromBytes))
            }
            texts = dao.ocrTexts().mapNotNull { r -> r.ocrText?.let { r.mediaId to it } }
            cacheKey = key
        }
    }

    suspend fun search(query: String, limit: Int = 50): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), true)
        val modelId = settings.embeddingModelId.first()
        loadCache(modelId)

        val qVec = residency.embedder()?.use { it.value.embedQuery(q) }

        val imageSims = HashMap<Long, Float>()
        val ocrSims = HashMap<Long, Float>()
        if (qVec != null) {
            for (v in vectors) {
                v.image?.let { imageSims[v.id] = VectorMath.dot(qVec, it) }
                v.ocr?.let { ocrSims[v.id] = VectorMath.dot(qVec, it) }
            }
        }
        val tokens = KeywordMatcher.tokens(q)
        val kw = HashMap<Long, KeywordMatch>()
        val snippets = HashMap<Long, String>()
        for ((id, text) in texts) {
            KeywordMatcher.match(text, tokens, q)?.let {
                kw[id] = it
                snippets[id] = KeywordMatcher.snippet(text, tokens)
            }
        }

        val img = Fusion.relative(imageSims, IMAGE_MARGIN, IMAGE_MIN_SPREAD, MIN_IMAGE_TOP)
        val ocr = Fusion.relative(ocrSims, OCR_MARGIN, OCR_MIN_SPREAD, MIN_OCR_TOP)
        val kwScore = kw.mapValues { (_, m) ->
            if (m.exact) 1f else 0.5f + 0.5f * (m.count.toFloat() / tokens.size.coerceAtLeast(1))
        }
        val ids = img.keys + ocr.keys + kwScore.keys
        val hits = ids.map { id ->
            SearchHit(
                mediaId = id,
                score = W_IMAGE * (img[id] ?: 0f) + W_OCR * (ocr[id] ?: 0f) + W_KEYWORD * (kwScore[id] ?: 0f),
                imageSim = imageSims[id].takeIf { id in img },
                ocrSim = ocrSims[id].takeIf { id in ocr },
                keywordMatches = kw[id]?.count ?: 0,
                exactKeyword = kw[id]?.exact == true,
                snippet = snippets[id],
            )
        }.sortedWith(compareByDescending<SearchHit> { it.exactKeyword }.thenByDescending { it.score })
            .take(limit)
        Log.d(TAG, "RESULT q='$q' tokens=$tokens top=${hits.map { it.mediaId }}")
        Log.d(TAG, "q='$q' tokens=$tokens " + (imageSims.keys + ocrSims.keys).joinToString { id ->
            "#$id[img=${imageSims[id]?.let { "%.3f".format(it) }}/${img[id]?.let { "%.2f".format(it) }} " +
                "ocr=${ocrSims[id]?.let { "%.3f".format(it) }}/${ocr[id]?.let { "%.2f".format(it) }} kw=${kw[id]?.count}]"
        })
        return SearchResult(hits, semanticAvailable = qVec != null)
    }

    companion object {
        private const val TAG = "LSA.Search"
        /** A signal counts if it is within this distance of that signal's best score. */
        const val IMAGE_MARGIN = 0.10f
        const val OCR_MARGIN = 0.08f
        /** Best score must exceed the median by this much, otherwise the signal is noise (e.g. unrelated OCR text). */
        const val IMAGE_MIN_SPREAD = 0.08f
        const val OCR_MIN_SPREAD = 0.08f
        /** Ignore a signal entirely when even its best score is this low. */
        const val MIN_IMAGE_TOP = 0.10f
        const val MIN_OCR_TOP = 0.30f
        const val W_IMAGE = 1.0f
        const val W_OCR = 0.8f
        const val W_KEYWORD = 1.2f
    }
}
