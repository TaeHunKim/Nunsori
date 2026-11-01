package com.lsa.app.indexing

import android.content.Context
import android.os.PowerManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lsa.app.data.db.ImageDao
import com.lsa.app.data.media.MediaStoreRepository
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ui.index.hasPhotoPermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

class IndexingController(
    private val context: Context,
    private val dao: ImageDao,
    private val runner: IndexRunner,
    private val settings: SettingsStore,
) {
    private val wm = WorkManager.getInstance(context)

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** Serialises indexing runs (manual and automatic) so two workers never fight over the models. */
    val runLock = Mutex()

    @Volatile var stopRequested = false
        private set

    /** Pause button shows while a manual run is queued/running or any automatic run is actually running. */
    val running: Flow<Boolean> = combine(
        wm.getWorkInfosForUniqueWorkFlow(NAME),
        wm.getWorkInfosByTagFlow(TAG),
    ) { manual, tagged ->
        manual.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } ||
            tagged.any { it.state == WorkInfo.State.RUNNING }
    }

    internal fun update(p: IndexProgress) { _progress.value = p }
    internal fun resetStop() { stopRequested = false }

    /** Manual start: runs immediately, no constraints. */
    fun start() {
        _progress.value = _progress.value.copy(error = null, finished = false)
        stopRequested = false
        wm.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexingWorker>().addTag(TAG).build(),
        )
    }

    /** Stops a manual run and asks any running automatic run to stop after the current file. */
    fun pause() {
        stopRequested = true
        wm.cancelUniqueWork(NAME)
    }

    /** Thermal guard for automatic runs. */
    fun isHot(): Boolean =
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    /**
     * Cheap, model-free cleanup: removes index rows whose files are gone. Skipped when a run is active or
     * there is nothing to look at.
     */
    suspend fun pruneNow() {
        if (!hasPhotoPermission(context)) return
        val folders = dao.enabledFolders()
        if (folders.isEmpty() || !runLock.tryLock()) return
        try {
            runner.sync(folders, settings.embeddingModelId.first())
        } finally {
            runLock.unlock()
        }
    }

    companion object {
        const val NAME = "indexing"
        const val TAG = "indexing-run"
    }
}
