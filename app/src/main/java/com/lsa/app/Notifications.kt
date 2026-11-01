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
    const val CH_DOWNLOAD = "download"
    const val ID_INDEXING = 1001
    const val ID_DOWNLOAD = 1002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INDEXING, ctx.getString(R.string.channel_indexing), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DOWNLOAD, ctx.getString(R.string.channel_download), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(
        ctx: Context, workId: UUID, channel: String, title: String, text: String?,
        done: Int, total: Int, actionLabel: String,
    ): Notification {
        ensureChannels(ctx)
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
