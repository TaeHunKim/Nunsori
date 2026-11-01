package com.lsa.app.models

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lsa.app.LsaApplication
import com.lsa.app.Notifications
import com.lsa.app.R

class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val spec get() = ModelCatalog.byId(inputData.getString(KEY_MODEL_ID) ?: "")

    private fun info(done: Int, total: Int, name: String): ForegroundInfo {
        val n = Notifications.progress(
            applicationContext, id, Notifications.CH_DOWNLOAD,
            applicationContext.getString(R.string.notif_download_title, name),
            if (total > 0) "$done%" else null,
            done, total, applicationContext.getString(R.string.action_cancel),
        )
        return Notifications.foregroundInfo(Notifications.ID_DOWNLOAD, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0, spec?.displayName ?: "")

    override suspend fun doWork(): Result {
        val spec = spec ?: return Result.failure()
        val manager = (applicationContext as LsaApplication).container.modelManager
        setForeground(info(0, 0, spec.displayName))
        var lastPct = -1
        return try {
            manager.runDownload(spec) { d, t ->
                val pct = if (t > 0) (d * 100 / t).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    Notifications.notify(
                        applicationContext, Notifications.ID_DOWNLOAD,
                        info(pct, 100, spec.displayName).notification,
                    )
                }
            }
            Result.success()
        } catch (e: DownloadException) {
            // Auth/404 won't fix themselves; network errors can be retried.
            if (e.kind == DownloadException.Kind.NETWORK) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object { const val KEY_MODEL_ID = "model_id" }
}
