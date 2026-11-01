package com.lsa.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.search.SearchHit
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

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    /** Stored text keeps markdown headings (`## A`); results should read as plain text. */
    private fun display(s: String) = s.replace(Regex("(^|\\s)#{1,6}\\s+"), "$1")

    var query by mutableStateOf("")
    private val _state = MutableStateFlow<SearchUi>(SearchUi.Idle)
    val state: StateFlow<SearchUi> = _state.asStateFlow()
    private var job: Job? = null

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) { _state.value = SearchUi.Idle; return }
        job?.cancel()
        _state.value = SearchUi.Loading
        job = viewModelScope.launch {
            try {
                val res = c.search.search(q)
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
