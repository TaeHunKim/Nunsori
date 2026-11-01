package com.lsa.app.indexing

import android.content.Context
import android.os.PowerManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.media.FolderSpec
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ui.index.hasAnyStorageAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

class IndexingController(
    private val context: Context,
    private val dao: ItemDao,
    private val runner: IndexRunner,
    private val settings: SettingsStore,
    private val models: com.lsa.app.models.ModelManager,
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

    /** Kinds that are indexed right now. */
    suspend fun enabledKinds(): Set<ItemKind> = settings.enabledKinds.first().filterTo(HashSet()) { it in KindSupport.implemented }

    /** What a finished item of each kind must have been produced with, given the current settings. */
    suspend fun pipelineKeys(): (ItemKind) -> String {
        val emb = settings.embeddingModelId.first()
        val ocr = settings.ocrModelId.first()
        val asrId = settings.asrModelId.first()
        val asr = if (com.lsa.app.models.ModelCatalog.byId(asrId)?.let(models::isReady) == true) asrId else "none"
        val detail = settings.videoDetail.first().name
        val speech = settings.videoTranscribe.first()
        return { PipelineKeys.of(it, emb, ocr, asr, detail, speech) }
    }

    suspend fun folderSpecs(): List<FolderSpec> =
        dao.enabledFolderRows().map { FolderSpec(it.relativePath, it.recursive) }

    /** Manual start: runs immediately, no constraints. */
    fun start() {
        android.util.Log.i("LSA.Index", "start() requested")
        _progress.value = _progress.value.copy(error = null, finished = false)
        stopRequested = false
        wm.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexingWorker>().addTag(TAG).build(),
        )
    }

    /** Manual run that retries only the files that failed; the rest of the queue is left alone. */
    fun retryFailed() {
        _progress.value = _progress.value.copy(error = null, finished = false)
        stopRequested = false
        wm.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexingWorker>().addTag(TAG)
                .setInputData(androidx.work.workDataOf(IndexingWorker.KEY_ONLY_FAILED to true)).build(),
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
        if (!hasAnyStorageAccess(context)) return
        val folders = folderSpecs()
        if (folders.isEmpty() || !runLock.tryLock()) return
        try {
            runner.sync(folders, enabledKinds(), pipelineKeys())
        } finally {
            runLock.unlock()
        }
    }

    companion object {
        const val NAME = "indexing"
        const val TAG = "indexing-run"
    }
}
