package com.lsa.app.indexing

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lsa.app.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Maps the chosen [AutoIndexMode] to WorkManager jobs. Idempotent: [apply] can be called any time. */
class AutoIndexScheduler(
    context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Emits when something changes that makes new indexing work possible (folders, model selected/installed). */
    private val catchUpSignals: Flow<Any> = emptyFlow(),
) {
    private val wm = WorkManager.getInstance(context)

    data class Config(val mode: AutoIndexMode, val minutes: Int, val chargingOnly: Boolean)

    /** Re-applies whenever a relevant setting changes (and once at startup). */
    fun start() {
        scope.launch {
            combine(settings.autoMode, settings.scheduleMinutes, settings.scheduleChargingOnly, ::Config)
                .distinctUntilChanged()
                .collect { apply(it) }
        }
        // In IMMEDIATE mode there is no other trigger when the user enables a folder or finishes a model download.
        scope.launch {
            combine(settings.autoMode, catchUpSignals) { mode, sig -> mode to sig }
                .distinctUntilChanged()
                .collect { (mode, _) -> if (mode == AutoIndexMode.IMMEDIATE) enqueueCatchUp() }
        }
    }

    private fun enqueueCatchUp() {
        wm.enqueueUniqueWork(
            CATCH_UP, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexingWorker>()
                .setInputData(autoData())
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(IndexingController.TAG).build(),
        )
    }

    fun apply(cfg: Config) {
        Log.i(TAG, "apply $cfg")
        if (cfg.mode != AutoIndexMode.IMMEDIATE) wm.cancelUniqueWork(TRIGGER)
        if (cfg.mode != AutoIndexMode.SCHEDULED) wm.cancelUniqueWork(SCHEDULED)
        if (cfg.mode != AutoIndexMode.IDLE) wm.cancelUniqueWork(IDLE)

        when (cfg.mode) {
            AutoIndexMode.MANUAL -> Unit
            AutoIndexMode.IMMEDIATE -> {
                // Triggers only see changes made after they are registered, so also run one catch-up pass now.
                enqueueCatchUp()
                wm.enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.KEEP, triggerRequest())
            }
            AutoIndexMode.SCHEDULED -> {
                val delay = delayUntilNext(cfg.minutes, ZonedDateTime.now())
                val req = PeriodicWorkRequestBuilder<IndexingWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay)
                    .setInputData(autoData())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresCharging(cfg.chargingOnly)
                            .build(),
                    )
                    .addTag(IndexingController.TAG).build()
                wm.enqueueUniquePeriodicWork(SCHEDULED, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            AutoIndexMode.IDLE -> {
                // CPU/GPU/NPU load is not readable by apps; "charging + device idle" is the proxy for "not in use".
                val req = PeriodicWorkRequestBuilder<IndexingWorker>(3, TimeUnit.HOURS)
                    .setInputData(autoData())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresCharging(true)
                            .setRequiresDeviceIdle(true)
                            .build(),
                    )
                    .addTag(IndexingController.TAG).build()
                wm.enqueueUniquePeriodicWork(IDLE, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }
    }

    /** Queues the next trigger behind the one that is currently running. */
    fun armTriggerAfterCurrent() {
        wm.enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, triggerRequest())
    }

    private fun autoData(detail: String? = null): Data = Data.Builder()
        .putString(IndexingWorker.KEY_SOURCE, IndexingWorker.SOURCE_AUTO)
        .apply { detail?.let { putString(IndexingWorker.KEY_SOURCE_DETAIL, it) } }
        .build()

    private fun triggerRequest() = OneTimeWorkRequestBuilder<IndexingWorker>()
        .setInputData(autoData(IndexingWorker.DETAIL_TRIGGER))
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(TRIGGER_UPDATE_DELAY_S))
                .setTriggerContentMaxDelay(Duration.ofMinutes(TRIGGER_MAX_DELAY_MIN))
                .setRequiresBatteryNotLow(true)
                .build(),
        )
        .addTag(IndexingController.TAG).build()

    companion object {
        private const val TAG = "LSA.Auto"
        const val TRIGGER = "auto-trigger"
        const val SCHEDULED = "auto-scheduled"
        const val IDLE = "auto-idle"
        const val CATCH_UP = IndexingController.NAME
        const val TRIGGER_UPDATE_DELAY_S = 15L
        const val TRIGGER_MAX_DELAY_MIN = 2L

        /** Time from [now] until the next local [minutesOfDay] (strictly in the future). */
        fun delayUntilNext(minutesOfDay: Int, now: ZonedDateTime): Duration {
            val zone: ZoneId = now.zone
            var next = now.toLocalDate().atTime(minutesOfDay / 60, minutesOfDay % 60).atZone(zone)
            if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(minutesOfDay / 60, minutesOfDay % 60).atZone(zone)
            return Duration.between(now, next)
        }
    }
}
