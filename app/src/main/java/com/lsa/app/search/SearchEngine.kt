package com.lsa.app.search

import android.util.Log
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.db.SignalGroup
import com.lsa.app.data.db.VectorRow
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The segment of an item that matched best. */
data class SegmentRef(val segId: Long, val type: SegmentType, val startMs: Long?, val endMs: Long?, val page: Int?)

data class SearchHit(
    val itemId: Long,
    val score: Float,
    val best: SegmentRef?,
    val visualSim: Float?,
    val textSim: Float?,
    val audioSim: Float?,
    val keywordMatches: Int,
    val exactKeyword: Boolean,
    val snippet: String?,
)

data class SearchResult(val hits: List<SearchHit>, val semanticAvailable: Boolean)

/**
 * Hybrid search over segments: one semantic signal per [SignalGroup] plus keywords, combined per item.
 * Each signal is judged against its own distribution (see [Fusion]) because cosine ranges differ by modality.
 */
class SearchEngine(
    private val dao: ItemDao,
    /** Embeds a query with the selected model, or null when it is not installed. */
    private val queryEmbedder: suspend (String) -> FloatArray?,
    private val settings: SettingsStore,
) {
    private val cacheMutex = Mutex()
    private var cacheKey: String? = null
    private var vectors: List<VectorRow> = emptyList()

    private suspend fun loadCache(modelId: String) = cacheMutex.withLock {
        val key = "$modelId|${dao.stamp()}"
        if (key != cacheKey) {
            vectors = dao.vectors(modelId)
            cacheKey = key
        }
    }

    private class Acc(val itemId: Long) {
        val groupScore = FloatArray(SignalGroup.entries.size)
        val groupSim = arrayOfNulls<Float>(SignalGroup.entries.size)
        var best: SegmentRef? = null
        var bestScore = 0f
        var kwScore = 0f
        var kwMatches = 0
        var exact = false
        var snippet: String? = null
        var kwRef: SegmentRef? = null
        /** Best matching video clip, to narrow down where in a video a transcript hit happened. */
        var clipRef: SegmentRef? = null
        var clipScore = 0f
    }

    suspend fun search(query: String, limit: Int = 50): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), true)
        loadCache(settings.embeddingModelId.first())

        val qVec = queryEmbedder(q)
        val acc = HashMap<Long, Acc>()
        fun accOf(itemId: Long) = acc.getOrPut(itemId) { Acc(itemId) }

        if (qVec != null) {
            val sims = SignalGroup.entries.map { HashMap<Long, Float>() }
            val byId = HashMap<Long, VectorRow>(vectors.size)
            for (v in vectors) {
                sims[v.type.group.ordinal][v.segId] = Quant.dot(qVec, v.embedding)
                byId[v.segId] = v
            }
            for (g in SignalGroup.entries) {
                val rel = Fusion.relative(sims[g.ordinal], g.margin, g.minSpread, g.minTop)
                for ((segId, r) in rel) {
                    val v = byId.getValue(segId)
                    val a = accOf(v.itemId)
                    val weighted = r * v.type.factor
                    if (weighted > a.groupScore[g.ordinal]) {
                        a.groupScore[g.ordinal] = weighted
                        a.groupSim[g.ordinal] = sims[g.ordinal].getValue(segId)
                    }
                    if (v.type == SegmentType.VIDEO_CLIP && weighted > a.clipScore) {
                        a.clipScore = weighted; a.clipRef = SegmentRef(v.segId, v.type, v.startMs, v.endMs, v.page)
                    }
                    if (weighted * g.weight > a.bestScore) {
                        a.bestScore = weighted * g.weight
                        a.best = SegmentRef(v.segId, v.type, v.startMs, v.endMs, v.page)
                    }
                }
            }
        }

        val tokens = KeywordMatcher.tokens(q)
        if (tokens.isNotEmpty()) {
            val seen = HashSet<Long>()
            for (t in tokens) {
                for (row in dao.textCandidates("%" + escapeLike(t) + "%", KEYWORD_CANDIDATES)) {
                    if (!seen.add(row.segId)) continue
                    val m = KeywordMatcher.match(row.text, tokens, q) ?: continue
                    val score = if (m.exact) 1f else 0.5f + 0.5f * (m.count.toFloat() / tokens.size)
                    val a = accOf(row.itemId)
                    if (score > a.kwScore) {
                        a.kwScore = score
                        a.kwMatches = m.count
                        a.exact = m.exact
                        a.snippet = KeywordMatcher.snippet(row.text, tokens)
                        a.kwRef = SegmentRef(row.segId, row.type, row.startMs, row.endMs, row.page)
                    }
                }
            }
        }

        val hits = acc.values.map { a ->
            var score = W_KEYWORD * a.kwScore
            for (g in SignalGroup.entries) score += g.weight * a.groupScore[g.ordinal]
            SearchHit(
                itemId = a.itemId,
                score = score,
                // A keyword match pins the location; otherwise the best semantic segment.
                best = narrowToClip(if (a.kwScore >= 1f || a.best == null) a.kwRef ?: a.best else a.best, a.clipRef),
                visualSim = a.groupSim[SignalGroup.VISUAL.ordinal],
                textSim = a.groupSim[SignalGroup.TEXT.ordinal],
                audioSim = a.groupSim[SignalGroup.AUDIO.ordinal],
                keywordMatches = a.kwMatches,
                exactKeyword = a.exact,
                snippet = a.snippet,
            )
        }.filter { it.score > 0f }
            .let { all ->
                // When something matches the typed words exactly, weak look-alikes only add noise.
                if (all.any { it.exactKeyword }) all.filter { it.exactKeyword || it.score >= EXACT_MATCH_FLOOR } else all
            }
            .let { all ->
                // Drop the long tail: anything far below the best result is rarely what was meant.
                val top = all.maxOfOrNull { it.score } ?: 0f
                all.filter { it.exactKeyword || it.score >= TAIL_FRACTION * top }
            }
            .sortedWith(compareByDescending<SearchHit> { it.exactKeyword }.thenByDescending { it.score })
            .take(limit)
        Log.d(TAG, "RESULT q='$q' tokens=$tokens top=${hits.map { it.itemId }}")
        return SearchResult(hits, semanticAvailable = qVec != null)
    }

    /** A transcript chunk covers many seconds; if the best visual clip lies within it, that clip is the better answer. */
    private fun narrowToClip(chosen: SegmentRef?, clip: SegmentRef?): SegmentRef? {
        if (chosen == null || clip == null || chosen.type == SegmentType.VIDEO_CLIP) return chosen
        val cs = chosen.startMs ?: return chosen; val ce = chosen.endMs ?: return chosen
        val ks = clip.startMs ?: return chosen; val ke = clip.endMs ?: return chosen
        return if (ks < ce && ke > cs) clip else chosen
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private val SignalGroup.margin get() = when (this) { SignalGroup.VISUAL -> 0.10f; SignalGroup.TEXT -> 0.08f; SignalGroup.AUDIO -> 0.10f }
    private val SignalGroup.minSpread get() = 0.08f
    private val SignalGroup.minTop get() = when (this) { SignalGroup.VISUAL -> 0.10f; SignalGroup.TEXT -> 0.30f; SignalGroup.AUDIO -> 0.30f }
    private val SignalGroup.weight get() = when (this) { SignalGroup.VISUAL -> W_VISUAL; SignalGroup.TEXT -> W_TEXT; SignalGroup.AUDIO -> W_AUDIO }

    /** Text cards drawn from Office documents say little about how the document looks. */
    private val SegmentType.factor get() = if (this == SegmentType.CARD_IMAGE) 0.5f else 1f

    companion object {
        private const val TAG = "LSA.Search"
        const val KEYWORD_CANDIDATES = 2000
        /** With an exact keyword hit, other results must score at least this much (a full signal weighs ~1.0). */
        const val EXACT_MATCH_FLOOR = 0.6f
        const val TAIL_FRACTION = 0.25f
        const val W_VISUAL = 1.0f
        const val W_TEXT = 0.8f
        const val W_AUDIO = 0.8f
        const val W_KEYWORD = 1.2f
    }
}
