package com.lsa.app.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
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
    /** Failed files, counted from the database (not just the current run). */
    val failedCount = summaries.map { l -> l.count { it.status == com.lsa.app.data.db.ItemStatus.FAILED } }.state(0)
    val progress = c.indexing.progress
    val running = c.indexing.running.state(false)
    val enabledFolderCount = dao.observeFolders().map { f -> f.count { it.enabled } }.state(0)
    val modelReady = c.models.state.map { it.status == com.lsa.app.ml.HostState.Status.UNKNOWN || it.ready(io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories.EMBEDDING) }.state(true)
    /** Why the models are unavailable (OndolSaem missing, not allowed, ...). */
    val host = c.models.state
    fun refreshHost() { viewModelScope.launch { c.models.refresh() } }
    val staleCount = c.settings.embeddingModelId.flatMapLatest { dao.observeStaleCount(it) }.state(0)

    /** Runs only the failed files (a normal run also retries them, together with everything still pending). */
    fun retryFailed() = c.indexing.retryFailed()
    fun start() = c.indexing.start()
    fun pause() = c.indexing.pause()
}
