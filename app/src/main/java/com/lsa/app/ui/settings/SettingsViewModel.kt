package com.lsa.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.IndexedFolderEntity
import com.lsa.app.data.media.FolderInfo
import com.lsa.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderRow(val info: FolderInfo, val enabled: Boolean)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.imageDao()
    private val available = MutableStateFlow<List<FolderInfo>>(emptyList())

    val folders: StateFlow<List<FolderRow>> = combine(available, dao.observeFolders()) { all, saved ->
        val enabled = saved.filter { it.enabled }.map { it.relativePath }.toSet()
        all.map { FolderRow(it, it.relativePath in enabled) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelStates = c.modelManager.states
    val embeddingModelId = c.settings.embeddingModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_EMBEDDING)
    val ocrModelId = c.settings.ocrModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_OCR)
    val hfToken = c.settings.hfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val autoMode = c.settings.autoMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.AutoIndexMode.MANUAL)
    val scheduleMinutes = c.settings.scheduleMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_SCHEDULE_MINUTES)
    val chargingOnly = c.settings.scheduleChargingOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val residency = c.settings.residency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.ResidencyPolicy.IDLE_2M)

    fun setAutoMode(m: com.lsa.app.indexing.AutoIndexMode) = viewModelScope.launch { c.settings.setAutoMode(m) }
    fun setScheduleMinutes(v: Int) = viewModelScope.launch { c.settings.setScheduleMinutes(v) }
    fun setChargingOnly(v: Boolean) = viewModelScope.launch { c.settings.setScheduleChargingOnly(v) }
    fun setResidency(p: com.lsa.app.indexing.ResidencyPolicy) = viewModelScope.launch { c.settings.setResidency(p) }

    private val _loadingFolders = MutableStateFlow(false)
    val loadingFolders = _loadingFolders.asStateFlow()

    fun refreshFolders() = viewModelScope.launch {
        _loadingFolders.value = true
        try { available.value = c.mediaRepo.listFolders() } finally { _loadingFolders.value = false }
    }

    fun setFolderEnabled(path: String, enabled: Boolean) = viewModelScope.launch {
        dao.upsertFolder(IndexedFolderEntity(path, enabled))
        if (!enabled) dao.deleteByFolder(path)
    }

    fun refreshModels() = c.modelManager.refresh()
    fun download(id: String) = c.modelManager.startDownload(id)
    fun cancelDownload(id: String) = c.modelManager.cancelDownload(id)
    fun deleteModel(id: String) = viewModelScope.launch {
        c.residency.closeAll()
        c.modelManager.delete(id)
    }
    fun selectEmbedding(id: String) = viewModelScope.launch { c.settings.setEmbeddingModelId(id) }
    fun selectOcr(id: String) = viewModelScope.launch { c.settings.setOcrModelId(id) }
    fun saveToken(token: String) = viewModelScope.launch { c.settings.setHfToken(token) }
}
