package com.lsa.app.indexing.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWindowsTest {
    private val s = 1000L

    @Test fun shortAudioIsOneWindow() = assertEquals(listOf(Span(0, 20 * s)), AudioWindows.plan(20 * s, emptyList()))
    @Test fun emptyAudioHasNoWindows() = assertTrue(AudioWindows.plan(0, emptyList()).isEmpty())

    @Test fun longAudioWithoutSilenceUsesFixedWindowsWithOverlap() {
        val w = AudioWindows.plan(100 * s, emptyList())
        assertEquals(Span(0, 30 * s), w[0])
        assertEquals("next window starts 5 s before the previous end", 25 * s, w[1].startMs)
        assertTrue(w.all { it.length <= AudioWindows.MAX_MS })
        assertEquals(100 * s, w.last().endMs)
    }

    @Test fun windowsCutAtTheNearestSilence() {
        // speech everywhere except a gap 32.0-33.0 s (midpoint 32.5 s) near the nominal cut at 30 s
        val speech = listOf(Span(0, 32 * s), Span(33 * s, 100 * s))
        val w = AudioWindows.plan(100 * s, speech)
        assertEquals("cut moved to the silence midpoint", 32_500L, w[0].endMs)
    }

    @Test fun silenceFarFromTheNominalCutIsIgnored() {
        val speech = listOf(Span(0, 50 * s), Span(51 * s, 100 * s)) // gap at 50.5 s, nominal cut at 30 s
        val w = AudioWindows.plan(100 * s, speech)
        assertEquals(30 * s, w[0].endMs)
    }

    @Test fun everyPointInTimeIsCovered() {
        val w = AudioWindows.plan(317 * s, listOf(Span(0, 100 * s), Span(101 * s, 200 * s), Span(205 * s, 317 * s)))
        assertEquals(0L, w.first().startMs); assertEquals(317 * s, w.last().endMs)
        for (i in 1 until w.size) assertTrue("window $i overlaps or touches the previous", w[i].startMs <= w[i - 1].endMs)
        assertTrue(w.all { it.length in 1..AudioWindows.MAX_MS })
    }

    @Test fun tinyTailIsAbsorbedIntoTheLastWindow() {
        val w = AudioWindows.plan(62 * s, emptyList())
        assertTrue("no window shorter than the tail limit", w.all { it.length >= AudioWindows.MIN_TAIL_MS })
        assertEquals(62 * s, w.last().endMs)
    }

    @Test fun planningAlwaysTerminatesEvenWithManyGaps() {
        val speech = (0 until 2000).map { Span(it * 500L, it * 500L + 100) } // gaps every half second
        val w = AudioWindows.plan(1_000_000L, speech)
        assertTrue(w.size in 20..200)
    }
}
