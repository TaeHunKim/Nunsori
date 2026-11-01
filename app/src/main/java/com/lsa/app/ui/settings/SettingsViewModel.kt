package com.lsa.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.IndexedFolderEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.media.FolderInfo
import com.lsa.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.itemDao()

    /** Folders the user chose, in one list for every kind of file. */
    val folders: StateFlow<List<IndexedFolderEntity>> = dao.observeFolders()
        .map { rows -> rows.filter { it.enabled }.sortedBy { it.relativePath.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Folders containing photos; only used for picking when "all files access" is missing. */
    private val _photoFolders = MutableStateFlow<List<FolderInfo>>(emptyList())
    val photoFolders: StateFlow<List<FolderInfo>> = _photoFolders.asStateFlow()

    val kinds = c.settings.enabledKinds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(ItemKind.IMAGE))

    fun setKind(kind: ItemKind, enabled: Boolean) = viewModelScope.launch { c.settings.setKindEnabled(kind, enabled) }

    fun addFolder(path: String, recursive: Boolean) = viewModelScope.launch {
        dao.upsertFolder(IndexedFolderEntity(path, enabled = true, recursive = recursive))
    }

    fun setRecursive(path: String, recursive: Boolean) = viewModelScope.launch {
        dao.upsertFolder(IndexedFolderEntity(path, enabled = true, recursive = recursive))
    }

    fun removeFolder(row: IndexedFolderEntity) = viewModelScope.launch {
        dao.deleteFolder(row.relativePath)
        if (row.recursive) dao.deleteItemsLike(escapeLike(row.relativePath) + "%") else dao.deleteItemsInFolder(row.relativePath)
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private val _thumbBytes = MutableStateFlow(0L)
    val thumbBytes: StateFlow<Long> = _thumbBytes.asStateFlow()
    fun refreshThumbBytes() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _thumbBytes.value = c.thumbs.sizeBytes() }
    fun clearThumbs() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        c.thumbs.clear(); dao.clearThumbPaths(); _thumbBytes.value = 0
    }

    fun refreshPhotoFolders() = viewModelScope.launch { _photoFolders.value = c.mediaRepo.listFolders() }

    val modelStates = c.modelManager.states
    val embeddingModelId = c.settings.embeddingModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_EMBEDDING)
    val ocrModelId = c.settings.ocrModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_OCR)
    val asrModelId = c.settings.asrModelId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_ASR)
    val skipMusic = c.settings.skipMusic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val chatModelId = c.settings.chatModelId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_CHAT)
    val chatContextTokens = c.settings.chatContextTokens.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_CHAT_CONTEXT)
    fun selectChat(id: String) = viewModelScope.launch { c.residency.closeChat(); c.settings.setChatModelId(id) }
    fun setChatContext(tokens: Int) = viewModelScope.launch { c.residency.closeChat(); c.settings.setChatContextTokens(tokens) }
    fun selectAsr(id: String) = viewModelScope.launch { c.settings.setAsrModelId(id) }
    fun setSkipMusic(v: Boolean) = viewModelScope.launch { c.settings.setSkipMusic(v) }
    val videoDetail = c.settings.videoDetail.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.video.VideoDetail.MEDIUM)
    val videoTranscribe = c.settings.videoTranscribe.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setVideoDetail(d: com.lsa.app.indexing.video.VideoDetail) = viewModelScope.launch { c.settings.setVideoDetail(d) }
    fun setVideoTranscribe(v: Boolean) = viewModelScope.launch { c.settings.setVideoTranscribe(v) }
    val hfToken = c.settings.hfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val autoMode = c.settings.autoMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.AutoIndexMode.MANUAL)
    val scheduleMinutes = c.settings.scheduleMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_SCHEDULE_MINUTES)
    val chargingOnly = c.settings.scheduleChargingOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val residency = c.settings.residency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.ResidencyPolicy.IDLE_2M)

    fun setAutoMode(m: com.lsa.app.indexing.AutoIndexMode) = viewModelScope.launch { c.settings.setAutoMode(m) }
    fun setScheduleMinutes(v: Int) = viewModelScope.launch { c.settings.setScheduleMinutes(v) }
    fun setChargingOnly(v: Boolean) = viewModelScope.launch { c.settings.setScheduleChargingOnly(v) }
    fun setResidency(p: com.lsa.app.indexing.ResidencyPolicy) = viewModelScope.launch { c.settings.setResidency(p) }

    fun refreshModels() = c.modelManager.refresh()
    fun download(id: String) = c.modelManager.startDownload(id)
    fun cancelDownload(id: String) = c.modelManager.cancelDownload(id)
    fun deleteModel(id: String) = viewModelScope.launch {
        c.residency.closeAll()
        c.modelManager.delete(id)
    }
    /** An embedding model switch waiting for the user's confirmation: every indexed file has to be embedded again. */
    data class ModelSwitch(val modelId: String, val files: Int)
    private val _pendingSwitch = MutableStateFlow<ModelSwitch?>(null)
    val pendingSwitch: StateFlow<ModelSwitch?> = _pendingSwitch.asStateFlow()

    /** Switches right away when nothing is indexed yet; otherwise asks first ([confirmSwitch] / [cancelSwitch]). */
    fun selectEmbedding(id: String) = viewModelScope.launch {
        if (id == c.settings.embeddingModelId.first()) return@launch
        val files = dao.doneCount()
        if (files == 0) c.settings.setEmbeddingModelId(id) else _pendingSwitch.value = ModelSwitch(id, files)
    }

    /** Applies the switch and starts re-indexing everything with the new model. */
    fun confirmSwitch() = viewModelScope.launch {
        val s = _pendingSwitch.value ?: return@launch
        _pendingSwitch.value = null
        c.settings.setEmbeddingModelId(s.modelId)
        c.indexing.start()
    }

    fun cancelSwitch() { _pendingSwitch.value = null }
    fun selectOcr(id: String) = viewModelScope.launch { c.settings.setOcrModelId(id) }
    fun saveToken(token: String) = viewModelScope.launch { c.settings.setHfToken(token) }
}
