package com.lsa.app.indexing

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
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
                .setInputData(autoData(IndexingWorker.DETAIL_CATCH_UP))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MIN, TimeUnit.MINUTES)
                .addTag(IndexingController.TAG).build(),
        )
    }

    fun apply(cfg: Config) {
        Log.i(TAG, "apply $cfg")
        if (cfg.mode != AutoIndexMode.IMMEDIATE) { wm.cancelUniqueWork(TRIGGER_A); wm.cancelUniqueWork(TRIGGER_B); wm.cancelUniqueWork(NET) }
        if (cfg.mode != AutoIndexMode.SCHEDULED) wm.cancelUniqueWork(SCHEDULED)
        if (cfg.mode != AutoIndexMode.IDLE) wm.cancelUniqueWork(IDLE)

        when (cfg.mode) {
            AutoIndexMode.MANUAL -> Unit
            AutoIndexMode.IMMEDIATE -> {
                // Triggers only see changes made after they are registered, so also run one catch-up pass now.
                enqueueCatchUp()
                wm.enqueueUniqueWork(TRIGGER_A, ExistingWorkPolicy.KEEP, triggerRequest(TRIGGER_A))
                // Safety net: triggers can be deferred by the system or never fire (plain files outside MediaStore).
                wm.enqueueUniquePeriodicWork(
                    NET, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<IndexingWorker>(NET_HOURS, TimeUnit.HOURS)
                        .setInputData(autoData(IndexingWorker.DETAIL_NET))
                        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MIN, TimeUnit.MINUTES)
                        .addTag(IndexingController.TAG).build(),
                )
            }
            AutoIndexMode.SCHEDULED -> {
                val delay = delayUntilNext(cfg.minutes, ZonedDateTime.now())
                val req = PeriodicWorkRequestBuilder<IndexingWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay)
                    .setInputData(autoData(IndexingWorker.DETAIL_SCHEDULED))
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
                // "Not in use" = charging and the screen off long enough for the battery level (IdleRule, the idle
                // preset in Settings). A check every 15 minutes while charging; the worker returns at once unless idle.
                val req = PeriodicWorkRequestBuilder<IndexingWorker>(IDLE_CHECK_MIN, TimeUnit.MINUTES)
                    .setInputData(autoData(IndexingWorker.DETAIL_IDLE))
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .addTag(IndexingController.TAG).build()
                wm.enqueueUniquePeriodicWork(IDLE, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }
    }

    /**
     * Arms the trigger slot other than [currentSlot]. Called when a trigger run *starts*, so a change made while it runs
     * is observed at once (a one-shot trigger queued after the run would leave a blind window). `KEEP` leaves an
     * already waiting slot alone, so at least one trigger is always pending.
     */
    fun armNextTrigger(currentSlot: String?) {
        val next = if (currentSlot == TRIGGER_A) TRIGGER_B else TRIGGER_A
        Log.i(TAG, "arm trigger $next (current $currentSlot)")
        wm.enqueueUniqueWork(next, ExistingWorkPolicy.KEEP, triggerRequest(next))
    }

    private fun autoData(detail: String, slot: String? = null): Data = Data.Builder()
        .putString(IndexingWorker.KEY_SOURCE, IndexingWorker.SOURCE_AUTO)
        .putString(IndexingWorker.KEY_SOURCE_DETAIL, detail)
        .apply { slot?.let { putString(IndexingWorker.KEY_SLOT, it) } }
        .build()

    private fun triggerRequest(slot: String) = OneTimeWorkRequestBuilder<IndexingWorker>()
        .setInputData(autoData(IndexingWorker.DETAIL_TRIGGER, slot))
        .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MIN, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(TRIGGER_UPDATE_DELAY_S))
                .setTriggerContentMaxDelay(Duration.ofMinutes(TRIGGER_MAX_DELAY_MIN))
                .setRequiresBatteryNotLow(true)
                .build(),
        )
        .addTag(IndexingController.TAG).build()

    companion object {
        private const val TAG = "LSA.Auto"
        /** Two alternating slots for the one-shot content trigger (see [armNextTrigger]). */
        const val TRIGGER_A = "auto-trigger-a"
        const val TRIGGER_B = "auto-trigger-b"
        /** Hourly catch-up pass in Immediate mode. */
        const val NET = "auto-immediate-net"
        const val NET_HOURS = 1L
        /** Retries after a failed or throttled automatic run: 2, 4, 6 … minutes instead of the default doubling up to 5 hours. */
        const val BACKOFF_MIN = 2L
        const val SCHEDULED = "auto-scheduled"
        const val IDLE = "auto-idle"
        /** Idle checks while charging (WorkManager's shortest period). */
        const val IDLE_CHECK_MIN = 15L
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
