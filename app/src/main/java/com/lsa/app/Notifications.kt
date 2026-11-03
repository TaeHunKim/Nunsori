package com.lsa.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

object Notifications {
    const val CH_INDEXING = "indexing"
    const val CH_TASKS = "tasks"
    const val ID_INDEXING = 1001
    const val ID_DOWNLOAD = 1002
    const val ID_TASK_RUN = 1003
    private const val ID_TASK_RESULT_BASE = 20000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INDEXING, ctx.getString(R.string.channel_indexing), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TASKS, ctx.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the app on chat [sessionId] (MainActivity reads [EXTRA_SESSION]). */
    private fun openChat(ctx: Context, sessionId: Long): PendingIntent = PendingIntent.getActivity(
        ctx, sessionId.toInt(),
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_SESSION, sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A scheduled task finished (or failed): the answer's start is the text, tapping opens that chat. */
    fun taskResult(ctx: Context, taskId: Long, sessionId: Long, text: String, ok: Boolean) {
        ensureChannels(ctx)
        val title = ctx.getString(if (ok) R.string.notif_task_done else R.string.notif_task_failed)
        val body = text.replace(Regex("\\s+"), " ").trim().take(300)
        val n = NotificationCompat.Builder(ctx, CH_TASKS)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openChat(ctx, sessionId))
            .build()
        notify(ctx, ID_TASK_RESULT_BASE + taskId.toInt(), n)
    }

    const val EXTRA_SESSION = "sessionId"

    fun progress(
        ctx: Context, workId: UUID, channel: String, title: String, text: String?,
        done: Int, total: Int, actionLabel: String,
    ): Notification {
        ensureChannels(ctx)
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .setProgress(total, done, total == 0)
            .addAction(0, actionLabel, cancel)
            .build()
    }

    fun foregroundInfo(id: Int, n: Notification) =
        ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun notify(ctx: Context, id: Int, n: Notification) {
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
