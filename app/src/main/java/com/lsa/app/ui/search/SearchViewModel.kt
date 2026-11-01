package com.lsa.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.search.SearchFilter
import com.lsa.app.search.SearchHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ResultItem(val hit: SearchHit, val summary: ItemSummary, val snippet: String?, val thumbPath: String?)

sealed interface SearchUi {
    data object Idle : SearchUi
    data object Loading : SearchUi
    data class Done(val hits: List<ResultItem>, val semanticAvailable: Boolean) : SearchUi
    data class Failed(val message: String) : SearchUi
}

/** File categories offered as filters; "documents" covers text files and PDF/Office. */
enum class FileCategory(val kinds: Set<ItemKind>) {
    DOCUMENT(setOf(ItemKind.TEXT, ItemKind.DOC)), IMAGE(setOf(ItemKind.IMAGE)), AUDIO(setOf(ItemKind.AUDIO)), VIDEO(setOf(ItemKind.VIDEO))
}

enum class DatePreset { ALL, TODAY, WEEK, MONTH, YEAR, CUSTOM }

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    var categories by mutableStateOf(emptySet<FileCategory>())
        private set
    var datePreset by mutableStateOf(DatePreset.ALL)
        private set
    /** Custom range as local-day start (inclusive) and end (inclusive) in epoch milliseconds. */
    var customRange by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    /** Finished items that still belong to a previously selected embedding model (re-indexing in progress). */
    val staleCount = c.settings.embeddingModelId.flatMapLatest { c.db.itemDao().observeStaleCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleCategory(cat: FileCategory) { categories = if (cat in categories) categories - cat else categories + cat; search() }
    fun selectDate(p: DatePreset) { datePreset = p; search() }
    fun setCustomRange(startUtcDayMs: Long, endUtcDayMs: Long) {
        val zone = ZoneId.systemDefault()
        fun day(utcMs: Long) = Instant.ofEpochMilli(utcMs).atZone(ZoneId.of("UTC")).toLocalDate()
        val from = day(startUtcDayMs).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day(endUtcDayMs).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        customRange = from to to; datePreset = DatePreset.CUSTOM; search()
    }

    private fun filter(): SearchFilter {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val (from, to) = when (datePreset) {
            DatePreset.ALL -> null to null
            DatePreset.TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli() to null
            DatePreset.WEEK -> (now - 7L * 86_400_000) to null
            DatePreset.MONTH -> (now - 30L * 86_400_000) to null
            DatePreset.YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli() to null
            DatePreset.CUSTOM -> customRange?.let { it.first to it.second } ?: (null to null)
        }
        return SearchFilter(categories.flatMapTo(HashSet()) { it.kinds }, from, to)
    }

    /** Stored text keeps markdown headings (`## A`); results should read as plain text. */
    private fun display(s: String) = s.replace(Regex("(^|\\s)#{1,6}\\s+"), "$1")

    var query by mutableStateOf("")
    private val _state = MutableStateFlow<SearchUi>(SearchUi.Idle)
    val state: StateFlow<SearchUi> = _state.asStateFlow()
    private var job: Job? = null

    fun search() {
        val q = query.trim()
        val filter = filter()
        if (q.isEmpty() && !filter.active) { job?.cancel(); _state.value = SearchUi.Idle; return }
        job?.cancel()
        _state.value = SearchUi.Loading
        job = viewModelScope.launch {
            try {
                val res = c.search.search(q, filter)
                val summaries = c.db.itemDao().summaries(res.hits.map { it.itemId }).associateBy { it.itemId }
                val texts = c.db.itemDao().segmentTexts(res.hits.mapNotNull { it.best?.segId }).associate { it.segId to it.text }
                val items = res.hits.mapNotNull { h ->
                    summaries[h.itemId]?.let {
                        // show the page that matched, not just the first page
                        val page = h.best?.page
                        val thumb = when {
                            page != null -> c.db.itemDao().thumbOfPage(h.itemId, page) ?: it.thumbPath
                            // videos: the clip playing at the matched time (works for transcript hits too)
                            it.kind == com.lsa.app.data.db.ItemKind.VIDEO && h.best?.startMs != null ->
                                c.db.itemDao().clipThumbAt(h.itemId, h.best.startMs) ?: c.db.itemDao().thumbOfSegment(h.best.segId) ?: it.thumbPath
                            else -> it.thumbPath
                        }
                        ResultItem(h, it, (h.snippet ?: h.best?.segId?.let(texts::get)?.take(160))?.let(::display), thumb)
                    }
                }
                _state.value = SearchUi.Done(items, res.semanticAvailable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SearchUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
