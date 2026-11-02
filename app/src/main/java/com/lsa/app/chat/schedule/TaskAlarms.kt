package com.lsa.app.chat.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lsa.app.LsaApplication
import com.lsa.app.Notifications
import com.lsa.app.R
import kotlinx.coroutines.launch

/** Sets one exact alarm per active TIME task. Without the exact-alarm permission it falls back to an inexact one. */
open class TaskAlarms(private val context: Context) {
    private val am get() = context.getSystemService(AlarmManager::class.java)

    open fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    open fun arm(taskId: Long, atMs: Long) {
        val pi = pending(taskId)
        try {
            if (canScheduleExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using an inexact one", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    open fun cancel(taskId: Long) { am.cancel(pending(taskId)) }

    private fun pending(taskId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, taskId.toInt(),
        Intent(context, TaskAlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "LSA.Tasks"
        const val ACTION_FIRE = "com.lsa.app.TASK_FIRE"
        const val EXTRA_TASK = "taskId"
    }
}

/** An alarm went off: hand the task to the worker (the receiver itself must return quickly). */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TaskAlarms.EXTRA_TASK, -1)
        if (id > 0) TaskRunWorker.enqueue(context, id)
    }
}

/** Boot, clock, time zone or exact-alarm permission changes wipe or invalidate alarms: arm them all again. */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as LsaApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try { container.taskManager.rearmAll() } finally { pending.finish() }
        }
    }
}

/** Runs one scheduled task (loads the models, generates the answer, posts it to the chat, notifies). */
class TaskRunWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.progress(
            applicationContext, id, Notifications.CH_INDEXING, applicationContext.getString(R.string.notif_task_running),
            null, 0, 0, applicationContext.getString(R.string.action_pause),
        )
        return Notifications.foregroundInfo(Notifications.ID_TASK_RUN, n)
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK, -1)
        if (taskId < 0) return Result.failure()
        // Starting a foreground service from the background may be refused; the run then continues without it.
        try { setForeground(getForegroundInfo()) } catch (e: Exception) { Log.d(TAG, "no foreground: ${e.message}") }
        val c = (applicationContext as LsaApplication).container
        return try {
            c.taskManager.run(taskId)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "task $taskId failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "LSA.Tasks"
        const val KEY_TASK = "taskId"

        /** One unique work per task: a second trigger while one is waiting or running collapses into it. */
        fun enqueue(context: Context, taskId: Long) {
            val req = OneTimeWorkRequestBuilder<TaskRunWorker>()
                .setInputData(Data.Builder().putLong(KEY_TASK, taskId).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("task-run-$taskId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
