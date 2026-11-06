package com.lsa.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.lsa.app.indexing.IndexingWorker
import io.github.taehunkim.ondolsaem.sdk.IdleRule
import io.github.taehunkim.ondolsaem.sdk.IdleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "when idle" check on the device: until the screen has been off long enough for the battery level the worker
 * returns without an indexing pass (no "last automatic run" recorded); after that it runs one. Needs a charger and the
 * screen off (emulator: `adb shell dumpsys battery set ac 1`, `... set status 2`, `adb shell input keyevent 223`).
 */
@RunWith(AndroidJUnit4::class)
class IdleIndexTest {
    private val app: LsaApplication = ApplicationProvider.getApplicationContext()

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<IndexingWorker>(app).setInputData(
            workDataOf(IndexingWorker.KEY_SOURCE to IndexingWorker.SOURCE_AUTO, IndexingWorker.KEY_SOURCE_DETAIL to IndexingWorker.DETAIL_IDLE),
        ).build().doWork()
    }

    @Test fun runsOnlyAfterTheScreenWasOffLongEnough() = runBlocking {
        val idle = IdleState(app)
        val s = idle.snapshot()
        assumeTrue("needs a charger and the screen off ($s)", s.charging && !s.screenOn)
        val settings = app.container.settings
        val need = IdleRule.requiredOffMinutes(settings.idlePreset.first(), s.batteryPct)
        val now = System.currentTimeMillis()
        try {
            val before = settings.lastAutoRun.first()
            idle.setScreenOffSince(now - (need - 1) * 60_000L)
            assertEquals(ListenableWorker.Result.success(), run())
            assertEquals("no pass before $need min off at ${s.batteryPct} %", before, settings.lastAutoRun.first())

            idle.setScreenOffSince(now - (need + 1) * 60_000L)
            run()
            val after = settings.lastAutoRun.first()
            assertNotEquals(before, after)
            assertEquals(IndexingWorker.DETAIL_IDLE, after!!.source)
        } finally {
            idle.setScreenOffSince(null)
        }
    }
}
