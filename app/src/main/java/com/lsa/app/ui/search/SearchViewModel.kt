package com.lsa.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.ImageSummary
import com.lsa.app.search.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ResultItem(val hit: SearchHit, val summary: ImageSummary)

sealed interface SearchUi {
    data object Idle : SearchUi
    data object Loading : SearchUi
    data class Done(val hits: List<ResultItem>, val semanticAvailable: Boolean) : SearchUi
    data class Failed(val message: String) : SearchUi
}

class SearchViewModel(private val c: AppContainer) : ViewModel() {
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
                val summaries = c.db.imageDao().summaries(res.hits.map { it.mediaId }).associateBy { it.mediaId }
                val items = res.hits.mapNotNull { h -> summaries[h.mediaId]?.let { ResultItem(h, it) } }
                _state.value = SearchUi.Done(items, res.semanticAvailable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SearchUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
