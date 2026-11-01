package com.lsa.app.indexing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lsa.app.LsaApplication
import com.lsa.app.Notifications
import com.lsa.app.R
import com.lsa.app.ui.index.hasAnyStorageAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Runs one indexing pass. Source "manual" is the Start button; "auto" runs come from the scheduler (new photos,
 * daily schedule, idle) and are throttled when the device gets hot.
 */
class IndexingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val auto get() = inputData.getString(KEY_SOURCE) == SOURCE_AUTO

    private fun info(p: IndexProgress): ForegroundInfo {
        val n = Notifications.progress(
            applicationContext, id, Notifications.CH_INDEXING,
            applicationContext.getString(R.string.notif_indexing_title),
            p.current ?: applicationContext.getString(R.string.notif_indexing_preparing),
            p.done, p.total, applicationContext.getString(R.string.action_pause),
        )
        return Notifications.foregroundInfo(Notifications.ID_INDEXING, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(IndexProgress())

    override suspend fun doWork(): Result {
        val c = (applicationContext as LsaApplication).container
        Log.i(TAG, "worker started auto=$auto")
        // Starting a foreground service from the background is not allowed on Android 12+; run silently instead.
        try { setForeground(info(IndexProgress())) } catch (e: Exception) { Log.d(TAG, "no foreground: ${e.message}") }
        return c.indexing.runLock.withLock { runPass(c) }.also { rearmTrigger(c) }
    }

    private suspend fun runPass(c: com.lsa.app.AppContainer): Result {
        val ctl = c.indexing
        ctl.resetStop()
        val folders = ctl.folderSpecs()
        if (folders.isEmpty()) {
            if (!auto) ctl.update(IndexProgress(error = IndexError.NO_FOLDERS))
            return if (auto) Result.success() else Result.failure()
        }
        if (!hasAnyStorageAccess(applicationContext)) return if (auto) Result.success() else Result.failure()

        val keys = ctl.pipelineKeys()
        val plan = c.indexRunner.sync(folders, ctl.enabledKinds(), keys)
        c.thumbs.cleanup(c.db.itemDao().thumbPaths().toSet()) // previews of deleted files
        Log.i(TAG, "sync: total=${plan.total} todo=${plan.todo.size} auto=$auto")
        if (plan.todo.isEmpty()) {
            ctl.update(IndexProgress(total = plan.total, done = plan.done, finished = true))
            return Result.success()
        }
        if (auto && ctl.isHot()) return Result.retry()

        val embLease = try { c.residency.embedder() } catch (e: Exception) {
            Log.w(TAG, "model load failed", e)
            if (!auto) ctl.update(IndexProgress(error = IndexError.LOAD_FAILED))
            return if (auto) Result.retry() else Result.failure()
        }
        if (embLease == null) {
            if (!auto) ctl.update(IndexProgress(error = IndexError.MODEL_MISSING))
            return if (auto) Result.success() else Result.failure()
        }
        val ocrLease = c.residency.ocr(c.settings.ocrModelId.first())
        var lastNotify = 0L
        val result = try {
            val session = ItemSession(applicationContext, embLease.value, ocrLease.value, c.thumbs, c.residency::speech, java.io.File(applicationContext.cacheDir, "audio"),
                VideoOptions(c.settings.videoDetail.first(), c.settings.videoTranscribe.first()))
            c.indexRunner.process(
                plan, session, keys,
                gate = { !ctl.stopRequested && !(auto && ctl.isHot()) },
            ) { p ->
                ctl.update(p)
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1000 || p.finished) {
                    lastNotify = now
                    runCatching { Notifications.notify(applicationContext, Notifications.ID_INDEXING, info(p).notification) }
                }
            }
        } finally {
            ocrLease.close(); embLease.close()
        }
        // Stopped by the thermal gate (not by the user): try again later.
        return if (result.stoppedEarly && !ctl.stopRequested) Result.retry() else Result.success()
    }

    /** The content-URI trigger is one-shot: queue the next one behind this run. */
    private fun rearmTrigger(c: com.lsa.app.AppContainer) {
        if (inputData.getString(KEY_SOURCE_DETAIL) == DETAIL_TRIGGER) c.autoIndex.armTriggerAfterCurrent()
    }

    companion object {
        private const val TAG = "LSA.Index"
        const val KEY_SOURCE = "source"
        const val SOURCE_AUTO = "auto"
        const val KEY_SOURCE_DETAIL = "detail"
        const val DETAIL_TRIGGER = "trigger"
    }
}
