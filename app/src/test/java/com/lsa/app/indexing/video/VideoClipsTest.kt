package com.lsa.app.indexing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoClipsTest {
    private fun times(n: Int, stepMs: Long) = (0 until n).map { it * stepMs }
    private fun noCuts(n: Int) = List(n) { 0f }
    private fun cutsAt(n: Int, vararg at: Int) = List(n) { if (it in at) 0.5f else 0f }

    @Test fun frameStatsDetectsDifferentImages() {
        val w = 32; val h = 32
        val dark = IntArray(w * h) { 0xFF101010.toInt() }
        val bright = IntArray(w * h) { 0xFFF0F0F0.toInt() }
        val a = FrameStats.lumaGrid(dark, w, h); val b = FrameStats.lumaGrid(bright, w, h)
        assertEquals(0f, FrameStats.difference(a, FrameStats.lumaGrid(dark, w, h)), 0f)
        assertTrue(FrameStats.difference(a, b) > 0.8f)
        assertEquals(FrameStats.GRID * FrameStats.GRID, a.size)
    }

    @Test fun sameSceneIsOneClip() {
        val c = VideoClips.plan(times(6, 2000), noCuts(6), 12_000)
        assertEquals(1, c.size)
        assertEquals(0L, c[0].startMs); assertEquals(12_000L, c[0].endMs); assertEquals(0..5, c[0].frames)
    }

    @Test fun clipsStartAtSceneChanges() {
        // 3 scenes of 6 s, frames every 2 s, cuts at frame 3 and 6
        val c = VideoClips.plan(times(9, 2000), cutsAt(9, 3, 6), 18_000)
        assertEquals(listOf(0L, 6_000L, 12_000L), c.map { it.startMs })
        assertEquals(listOf(6_000L, 12_000L, 18_000L), c.map { it.endMs })
        assertEquals(listOf(0..2, 3..5, 6..8), c.map { it.frames })
    }

    @Test fun veryShortScenesAreMergedIntoTheirNeighbour() {
        // a 2 s flash between two long scenes
        val c = VideoClips.plan(times(10, 2000), cutsAt(10, 4, 5), 20_000)
        assertTrue("no clip shorter than the minimum: $c", c.all { it.endMs - it.startMs >= VideoClips.MIN_MS })
        assertEquals(20_000L, c.last().endMs)
    }

    @Test fun shortFirstSceneIsMergedForward() {
        val c = VideoClips.plan(times(8, 2000), cutsAt(8, 1), 16_000)
        assertEquals(1, c.size); assertEquals(0..7, c[0].frames)
    }

    @Test fun longSceneIsCutIntoOverlappingParts() {
        val c = VideoClips.plan(times(60, 1000), noCuts(60), 60_000)
        assertTrue(c.size >= 3)
        assertTrue(c.all { it.endMs - it.startMs <= VideoClips.MAX_MS + 1000 })
        for (i in 1 until c.size) assertTrue("part $i overlaps the previous", c[i].startMs < c[i - 1].endMs)
        assertEquals(0L, c.first().startMs); assertEquals(60_000L, c.last().endMs)
        // every frame is covered
        assertEquals((0 until 60).toSet(), c.flatMap { it.frames.toList() }.toSet())
    }

    @Test fun planningAlwaysMovesForward() {
        val c = VideoClips.plan(times(500, 100), noCuts(500), 50_000)
        for (i in 1 until c.size) assertTrue(c[i].frames.first > c[i - 1].frames.first)
    }

    @Test fun emptyInputHasNoClips() = assertTrue(VideoClips.plan(emptyList(), emptyList(), 0).isEmpty())

    @Test fun pickKeepsEverythingWhenSmallAndSpreadsEvenlyWhenLarge() {
        assertEquals(listOf(3, 4, 5), VideoClips.pick(3..5))
        val p = VideoClips.pick(0..99)
        assertEquals(VideoClips.MAX_FRAMES_PER_CLIP, p.size)
        assertEquals(0, p.first()); assertEquals(99, p.last())
        assertTrue(p.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun sampleIntervalWidensForLongVideos() {
        assertEquals(2_000L, VideoClips.sampleIntervalMs(2_000, 60_000))
        assertEquals(12_000L, VideoClips.sampleIntervalMs(2_000, 2 * 3600_000L, maxFrames = 600))
        assertEquals(listOf(0L, 2000L, 4000L), VideoClips.sampleTimes(5_000, 2_000))
        assertEquals(listOf(0L), VideoClips.sampleTimes(500, 2_000))
    }

    @Test fun detailLevelsMapToIntervals() {
        assertEquals(5_000L, VideoDetail.LOW.intervalMs); assertEquals(2_000L, VideoDetail.MEDIUM.intervalMs); assertEquals(1_000L, VideoDetail.HIGH.intervalMs)
    }
}
