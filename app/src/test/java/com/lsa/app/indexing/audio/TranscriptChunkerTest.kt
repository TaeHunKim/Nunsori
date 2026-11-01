package com.lsa.app.indexing.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptChunkerTest {
    private fun cue(i: Int, text: String = "sentence number $i is spoken here.") = Cue(i * 5_000L, i * 5_000L + 4_000, text)

    @Test fun fewCuesFormOneChunkWithTheirTimeRange() {
        val c = TranscriptChunker.group((0 until 3).map { cue(it) }, 600)
        assertEquals(1, c.size)
        assertEquals(0L, c[0].startMs); assertEquals(14_000L, c[0].endMs)
        assertTrue(c[0].text.startsWith("sentence number 0") && c[0].text.contains("number 2"))
    }

    @Test fun longTranscriptsSplitNearTheTarget() {
        val c = TranscriptChunker.group((0 until 60).map { cue(it) }, 300)
        assertTrue(c.size > 5)
        assertTrue(c.all { it.text.length <= 300 + 60 })
    }

    @Test fun consecutiveChunksShareTheirBoundaryCue() {
        val c = TranscriptChunker.group((0 until 40).map { cue(it) }, 300)
        for (i in 1 until c.size) {
            assertTrue("chunk $i starts where the previous one ended", c[i].startMs <= c[i - 1].endMs)
            val lastSentence = c[i - 1].text.substringAfterLast("sentence number ").substringBefore(" is")
            assertTrue(c[i].text.contains("sentence number $lastSentence is"))
        }
    }

    @Test fun everyCueEndsUpInSomeChunk() {
        val cues = (0 until 50).map { cue(it) }
        val all = TranscriptChunker.group(cues, 250).joinToString(" ") { it.text }
        for (i in 0 until 50) assertTrue(all.contains("sentence number $i is"))
    }

    @Test fun aLongPauseStartsANewChunk() {
        val c = TranscriptChunker.group(listOf(cue(0), cue(1), Cue(300_000, 304_000, "much later in the recording")), 600)
        assertEquals(2, c.size)
        assertEquals("no overlap across the pause", 300_000L, c[1].startMs)
    }

    @Test fun blankCuesAreIgnored() {
        val ch = TranscriptChunker()
        assertNull(ch.add(Cue(0, 1000, "   ")))
        assertNull(ch.finish())
    }

    @Test fun streamingUseEmitsTheTailOnFinish() {
        val ch = TranscriptChunker(targetChars = 100)
        val out = ArrayList<TranscriptChunk>()
        for (i in 0 until 10) ch.add(cue(i))?.let { out += it }
        ch.finish()?.let { out += it }
        assertTrue(out.size >= 3)
        assertNull("nothing left after finish", ch.finish())
        assertEquals(9 * 5_000L + 4_000, out.last().endMs)
    }

    @Test fun overlapDoesNotCreateADuplicateTailChunk() {
        val c = TranscriptChunker.group((0 until 2).map { cue(it) }, 600)
        assertEquals(1, c.size)
    }

    @Test fun maxSpanSplitsEvenShortTextsByTime() {
        val ch = TranscriptChunker(targetChars = 1000, maxSpanMs = 10_000)
        val out = ArrayList<TranscriptChunk>()
        // cues every 5 s: with a 10 s limit a chunk holds at most two of them (plus the carried-over one)
        for (i in 0 until 8) ch.add(cue(i))?.let { out += it }
        ch.finish()?.let { out += it }
        assertTrue("time limit produced several chunks: ${out.size}", out.size >= 3)
        assertTrue(out.all { it.endMs - it.startMs <= 10_000 + 4_000 })
    }
}
