package com.lsa.app

import android.util.Log
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.prefs.SettingsStore

/**
 * Runs once per app update, before anything depends on new settings or file layouts.
 * Database schema changes belong in Room migrations (data/db/Migrations.kt); derived data is rebuilt through
 * `pipelineKey`. This is for the rest: renamed or reinterpreted settings keys, changed cache layouts, ...
 *
 * Add steps as `if (from < <versionCode of the release that needs it>) { ... }`; versionCode = major*10000 + minor*100 + patch.
 */
object AppUpgrade {
    private const val TAG = "LSA.Upgrade"

    suspend fun run(context: android.content.Context, settings: SettingsStore, dao: ItemDao, current: Int) {
        val from = settings.lastVersionCode()
        if (from >= current) return
        Log.i(TAG, "upgrading from $from to $current")
        // 0.2.0: place names gained aliases (countries, regions); look them up again for photos indexed earlier.
        if (from < 200) dao.resetPlaces()
        // 0.8.0: models are downloaded and run by the OndolSaem app; delete the copies this app downloaded
        // (up to several GB) and the settings that went with them. Model ids stay the same, so the index stays valid.
        if (from in 1 until 800) {
            java.io.File(context.filesDir, "models").deleteRecursively()
            java.io.File(context.cacheDir, "litertlm").deleteRecursively()
            settings.removeModelHostingKeys()
        }
        // 0.8.1: the low-power indexing switch is gone; drop its stored value.
        if (from in 1 until 801) settings.removeLowPowerKey()
        // 0.10.0: the chat moved to the Malbotdari app. Its tables are dropped by MIGRATION_8_9 (which also cancels the
        // task alarms); here: queued task runs, the chat settings, the photos attached to chats, the task channel.
        if (from in 1 until 1000) {
            runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_TASK_WORKER) }
            settings.removeChatKeys()
            java.io.File(context.filesDir, "chat").deleteRecursively()
            runCatching { context.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("tasks") }
        }
        settings.setLastVersionCode(current)
    }

    /** Worker class of the scheduled chat tasks before 0.10.0 (WorkManager tags every request with its class name). */
    const val LEGACY_TASK_WORKER = "com.lsa.app.chat.schedule.TaskRunWorker"

    /** Cancels the exact alarms of the chat tasks dropped in 0.10.0 (same PendingIntent as the old TaskAlarms). */
    fun cancelChatAlarms(context: android.content.Context, taskIds: List<Long>) {
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        for (id in taskIds) {
            val intent = android.content.Intent("com.lsa.app.TASK_FIRE")
                .setComponent(android.content.ComponentName(context.packageName, "com.lsa.app.chat.schedule.TaskAlarmReceiver"))
                .putExtra("taskId", id)
            val pi = android.app.PendingIntent.getBroadcast(
                context, id.toInt(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE,
            ) ?: continue
            am.cancel(pi); pi.cancel()
        }
        Log.i(TAG, "cancelled the alarms of ${taskIds.size} dropped chat task(s)")
    }
}
