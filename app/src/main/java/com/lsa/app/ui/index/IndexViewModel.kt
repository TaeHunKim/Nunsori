package com.lsa.app.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.models.ModelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.lsa.app.data.db.ItemSummary

@OptIn(ExperimentalCoroutinesApi::class)
class IndexViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.itemDao()
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val summaries: StateFlow<List<ItemSummary>> = dao.observeSummaries().state(emptyList())
    val progress = c.indexing.progress
    val running = c.indexing.running.state(false)
    val enabledFolderCount = dao.observeFolders().map { f -> f.count { it.enabled } }.state(0)
    val modelReady = combine(c.settings.embeddingModelId, c.modelManager.states) { id, st ->
        st[id] is ModelState.Installed
    }.state(false)
    val staleCount = c.settings.embeddingModelId.flatMapLatest { dao.observeStaleCount(it) }.state(0)

    /** Starts as `true` so existing users never see the dialog flash before DataStore loads. */

    fun start() = c.indexing.start()
    fun pause() = c.indexing.pause()
}
