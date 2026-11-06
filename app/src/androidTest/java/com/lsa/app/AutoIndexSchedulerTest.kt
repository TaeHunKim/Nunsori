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
        val c = info(AutoIndexScheduler.TRIGGER_A)!!.constraints
        assertTrue("content trigger present", c.contentUriTriggers.isNotEmpty())
        assertTrue(c.contentUriTriggers.first().isTriggeredForDescendants)
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(15_000L, c.contentTriggerUpdateDelayMillis)
        assertEquals(120_000L, c.contentTriggerMaxDelayMillis)
        assertTrue("catch-up queued", wm.getWorkInfosForUniqueWork(AutoIndexScheduler.CATCH_UP).get().isNotEmpty())
        val net = info(AutoIndexScheduler.NET)!!
        assertEquals("hourly safety net", 3600_000L, net.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(net.constraints.requiresBatteryNotLow())
    }

    @Test fun armingTheNextTriggerUsesTheOtherSlotAndNeverReplacesAWaitingOne() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IMMEDIATE, 180, true))
        scheduler.armNextTrigger(AutoIndexScheduler.TRIGGER_A)
        assertTrue("slot B armed while A is still there", active(AutoIndexScheduler.TRIGGER_B) && active(AutoIndexScheduler.TRIGGER_A))
        val idB = info(AutoIndexScheduler.TRIGGER_B)!!.id
        scheduler.armNextTrigger(AutoIndexScheduler.TRIGGER_A)
        assertEquals("a waiting slot is kept", idB, info(AutoIndexScheduler.TRIGGER_B)!!.id)
        // B runs and arms the other slot (A, finished or waiting)
        scheduler.armNextTrigger(AutoIndexScheduler.TRIGGER_B)
        assertTrue(active(AutoIndexScheduler.TRIGGER_A))
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

    @Test fun idleIsAQuarterHourlyCheckWhileCharging() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IDLE, 180, false))
        val w = info(AutoIndexScheduler.IDLE)!!
        val c = w.constraints
        // the idle rule itself (screen off long enough for the battery level) is checked by the worker
        assertTrue(c.requiresCharging()); assertFalse(c.requiresDeviceIdle()); assertFalse(c.requiresBatteryNotLow())
        assertEquals(AutoIndexScheduler.IDLE_CHECK_MIN * 60_000L, w.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun switchingModeCancelsTheOthers() {
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IMMEDIATE, 180, true))
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.IDLE, 180, true))
        assertFalse("trigger cancelled", active(AutoIndexScheduler.TRIGGER_A) || active(AutoIndexScheduler.TRIGGER_B) || active(AutoIndexScheduler.NET))
        assertTrue(active(AutoIndexScheduler.IDLE))
        scheduler.apply(AutoIndexScheduler.Config(AutoIndexMode.MANUAL, 180, true))
        assertFalse(active(AutoIndexScheduler.IDLE)); assertFalse(active(AutoIndexScheduler.SCHEDULED))
    }
}
