package com.lsa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.AutoIndexMode
import com.lsa.app.indexing.AutoIndexScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies which WorkManager jobs (and constraints) each mode registers. Uses the app's real WorkManager. */
@RunWith(AndroidJUnit4::class)
class AutoIndexSchedulerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(ctx)
    private lateinit var scheduler: AutoIndexScheduler

    @Before fun setUp() {
        wm.cancelAllWork().result.get()
        scheduler = AutoIndexScheduler(ctx, SettingsStore(ctx), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @After fun tearDown() { wm.cancelAllWork().result.get() }

    private fun info(name: String): WorkInfo? = wm.getWorkInfosForUniqueWork(name).get().firstOrNull()
    private fun active(name: String) = wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }

    @Test fun immediateRegistersContentTriggerAndCatchUp() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IMMEDIATE, 180, true))
        val c = info(AutoIndexScheduler.TRIGGER)!!.constraints
        assertTrue("content trigger present", c.contentUriTriggers.isNotEmpty())
        assertTrue(c.contentUriTriggers.first().isTriggeredForDescendants)
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(15_000L, c.contentTriggerUpdateDelayMillis)
        assertEquals(120_000L, c.contentTriggerMaxDelayMillis)
        assertTrue("catch-up queued", wm.getWorkInfosForUniqueWork(AutoIndexScheduler.CATCH_UP).get().isNotEmpty())
    }

    @Test fun scheduledHonoursChargingOptionAndDelay() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.SCHEDULED, 3 * 60, true))
        val on = info(AutoIndexScheduler.SCHEDULED)!!
        assertTrue(on.constraints.requiresCharging()); assertTrue(on.constraints.requiresBatteryNotLow())
        assertTrue("initial delay set", on.initialDelayMillis > 0)
        assertEquals(24L * 3600_000, on.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.SCHEDULED, 3 * 60, false))
        assertFalse(info(AutoIndexScheduler.SCHEDULED)!!.constraints.requiresCharging())
    }

    @Test fun idleRequiresChargingAndDeviceIdle() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IDLE, 180, false))
        val c = info(AutoIndexScheduler.IDLE)!!.constraints
        assertTrue(c.requiresCharging()); assertTrue(c.requiresDeviceIdle()); assertTrue(c.requiresBatteryNotLow())
    }

    @Test fun switchingModeCancelsTheOthers() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IMMEDIATE, 180, true))
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IDLE, 180, true))
        assertFalse("trigger cancelled", active(AutoIndexScheduler.TRIGGER))
        assertTrue(active(AutoIndexScheduler.IDLE))
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.MANUAL, 180, true))
        assertFalse(active(AutoIndexScheduler.IDLE)); assertFalse(active(AutoIndexScheduler.SCHEDULED))
    }
}
