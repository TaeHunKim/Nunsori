package com.lsa.app.indexing

import com.lsa.app.data.prefs.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoIndexSchedulerTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 1, h, m, 0, 0, seoul)

    @Test fun laterTodayWhenTimeNotPassed() =
        assertEquals(Duration.ofMinutes(90), AutoIndexScheduler.delayUntilNext(3 * 60, at(1, 30)))

    @Test fun tomorrowWhenTimePassed() =
        assertEquals(Duration.ofHours(23), AutoIndexScheduler.delayUntilNext(3 * 60, at(4, 0)))

    @Test fun exactlyNowMeansTomorrow() =
        assertEquals(Duration.ofHours(24), AutoIndexScheduler.delayUntilNext(3 * 60, at(3, 0)))

    @Test fun midnightBoundary() =
        assertEquals(Duration.ofMinutes(1), AutoIndexScheduler.delayUntilNext(0, at(23, 59)))

    @Test fun dstShiftIsRespected() {
        // US spring-forward 2026-03-08: 02:00 -> 03:00, so 01:00 -> 04:00 is only 2h of wall-clock time.
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny)
        assertEquals(Duration.ofHours(2), AutoIndexScheduler.delayUntilNext(4 * 60, now))
    }

    @Test fun enumParsingFallsBackToDefault() {
        assertEquals(AutoIndexMode.IDLE, SettingsStore.parseEnum("IDLE", AutoIndexMode.MANUAL))
        assertEquals(AutoIndexMode.MANUAL, SettingsStore.parseEnum("garbage", AutoIndexMode.MANUAL))
        assertEquals(com.lsa.app.indexing.video.VideoDetail.MEDIUM, SettingsStore.parseEnum(null, com.lsa.app.indexing.video.VideoDetail.MEDIUM))
    }
}
