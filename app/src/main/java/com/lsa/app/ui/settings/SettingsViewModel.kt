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

    val skipMusic = c.settings.skipMusic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setSkipMusic(v: Boolean) = viewModelScope.launch { c.settings.setSkipMusic(v) }
    val videoDetail = c.settings.videoDetail.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.video.VideoDetail.MEDIUM)
    val videoTranscribe = c.settings.videoTranscribe.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setVideoDetail(d: com.lsa.app.indexing.video.VideoDetail) = viewModelScope.launch { c.settings.setVideoDetail(d) }
    fun setVideoTranscribe(v: Boolean) = viewModelScope.launch { c.settings.setVideoTranscribe(v) }

    val autoMode = c.settings.autoMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lsa.app.indexing.AutoIndexMode.MANUAL)
    val scheduleMinutes = c.settings.scheduleMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_SCHEDULE_MINUTES)
    val chargingOnly = c.settings.scheduleChargingOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAutoMode(m: com.lsa.app.indexing.AutoIndexMode) = viewModelScope.launch { c.settings.setAutoMode(m) }
    fun setScheduleMinutes(v: Int) = viewModelScope.launch { c.settings.setScheduleMinutes(v) }
    fun setChargingOnly(v: Boolean) = viewModelScope.launch { c.settings.setScheduleChargingOnly(v) }

    val showResourceIndicator = c.settings.showResourceIndicator.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val taskJudgeFirst = c.settings.taskJudgeFirst.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setTaskJudgeFirst(on: Boolean) = viewModelScope.launch { c.settings.setTaskJudgeFirst(on) }
    fun setShowResourceIndicator(on: Boolean) = viewModelScope.launch { c.settings.setShowResourceIndicator(on) }
    val chatCustomPrompt = c.settings.chatCustomPrompt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    fun saveChatPrompt(s: String) = viewModelScope.launch { c.settings.setChatCustomPrompt(s) }
}
