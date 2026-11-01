package com.lsa.app.indexing.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Pcm {
    const val RATE = 16_000
    /** 16-bit mono at [RATE]. */
    const val BYTES_PER_SECOND = RATE * 2

    fun msToByte(ms: Long): Long = (ms * RATE / 1000) * 2
    fun byteToMs(bytes: Long): Long = bytes / 2 * 1000 / RATE

    /** Wraps raw 16-bit little-endian mono PCM into a complete WAV file (what the audio embedder expects). */
    fun toWav(pcm: ByteArray, sampleRate: Int = RATE): ByteArray {
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return out.array()
    }

    /** Root-mean-square level in 0..1. */
    fun rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = sb.remaining()
        for (i in 0 until n) { val v = sb.get(i) / 32768.0; sum += v * v }
        return Math.sqrt(sum / n)
    }

    fun toFloats(pcm: ByteArray): FloatArray {
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }
}

/**
 * Streaming conversion of arbitrary interleaved PCM to mono at 16 kHz. Channels are averaged; sample-rate
 * conversion uses area averaging, which is enough for speech and avoids aliasing when downsampling.
 */
class StreamingResampler(private val srcRate: Int, private val channels: Int, private val dstRate: Int = Pcm.RATE) {
    private val step = srcRate.toDouble() / dstRate // source samples per output sample
    private var acc = 0.0      // sum of source samples inside the current output window
    private var covered = 0.0  // how much of the current window is filled (in source samples)

    /** [interleaved] holds `channels` values per frame, scaled to -1..1. Returns mono samples at the target rate. */
    fun process(interleaved: FloatArray, frames: Int, out: MutableList<Short>) {
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += interleaved[f * channels + c]
            s /= channels
            var remaining = 1.0 // this source sample is worth one unit of window coverage
            while (remaining > 1e-9) {
                val room = step - covered
                val take = minOf(room, remaining)
                acc += s * take; covered += take; remaining -= take
                if (covered >= step - 1e-9) {
                    out += (acc / step * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    acc = 0.0; covered = 0.0
                }
            }
        }
    }
}

/** One stretch of time inside a media file. */
data class Span(val startMs: Long, val endMs: Long) {
    val length get() = endMs - startMs
}

/** Plans the windows that are embedded as audio: ~30 s with overlap, cut at silences where possible. */
object AudioWindows {
    const val TARGET_MS = 30_000L
    const val OVERLAP_MS = 5_000L
    const val SNAP_MS = 6_000L
    /** The embedding model accepts about 40 s of audio; stay clearly below. */
    const val MAX_MS = 36_000L
    const val MIN_GAP_MS = 300L
    const val MIN_TAIL_MS = 4_000L

    fun plan(durationMs: Long, speech: List<Span>): List<Span> {
        if (durationMs <= 0) return emptyList()
        if (durationMs <= MAX_MS) return listOf(Span(0, durationMs))
        // silences between speech spans, as cut candidates (their midpoint)
        val cuts = ArrayList<Long>()
        var prevEnd = 0L
        for (s in speech.sortedBy { it.startMs }) {
            if (s.startMs - prevEnd >= MIN_GAP_MS) cuts += (prevEnd + s.startMs) / 2
            prevEnd = maxOf(prevEnd, s.endMs)
        }
        if (durationMs - prevEnd >= MIN_GAP_MS) cuts += (prevEnd + durationMs) / 2

        val out = ArrayList<Span>()
        var start = 0L
        while (start < durationMs) {
            val nominal = start + TARGET_MS
            if (nominal >= durationMs - MIN_TAIL_MS) { out += Span(start, durationMs); break }
            val snapped = cuts.filter { it in (nominal - SNAP_MS)..(nominal + SNAP_MS) && it > start + 8_000 }
                .minByOrNull { Math.abs(it - nominal) }
            val end = minOf(snapped ?: nominal, start + MAX_MS, durationMs)
            out += Span(start, end)
            start = maxOf(end - OVERLAP_MS, start + 1_000)
        }
        return out
    }
}

/** One recognised utterance. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class TranscriptChunk(val startMs: Long, val endMs: Long, val text: String)

/** Groups recognised utterances into chunks of about [targetChars], repeating the last utterance as overlap. */
class TranscriptChunker(
    private val targetChars: Int = 600,
    private val maxGapMs: Long = 20_000,
    /** A chunk never spans more than this much time (videos: so a hit says roughly when something was said). */
    private val maxSpanMs: Long = Long.MAX_VALUE,
) {
    private val pending = ArrayList<Cue>()
    private var overlap: Cue? = null
    /** Cues added since the last chunk was taken (the carried-over overlap cue does not count). */
    private var fresh = 0

    /** Adds a cue and returns a finished chunk when one is ready. */
    fun add(cue: Cue): TranscriptChunk? {
        if (cue.text.isBlank()) return null
        var done: TranscriptChunk? = null
        val last = pending.lastOrNull()
        val size = pending.sumOf { it.text.length + 1 }
        if (pending.isNotEmpty()) {
            val longPause = cue.startMs - last!!.endMs > maxGapMs
            // after a long pause the previous sentence is unrelated, so nothing is carried over
            val tooLong = cue.endMs - pending.first().startMs > maxSpanMs
            if (longPause || tooLong || size + cue.text.length > targetChars) done = take(carryOver = !longPause)
        }
        pending += cue
        fresh++
        return done
    }

    fun finish(): TranscriptChunk? = if (fresh == 0) null else take(carryOver = false)

    private fun take(carryOver: Boolean): TranscriptChunk {
        val text = pending.joinToString(" ") { it.text.trim() }
        val chunk = TranscriptChunk(pending.first().startMs, pending.last().endMs, text)
        overlap = pending.last()
        pending.clear(); fresh = 0
        // the overlap cue starts the next chunk (so a sentence cut by the boundary appears in both)
        if (carryOver) overlap?.let { pending += it }
        return chunk
    }

    companion object {
        fun group(cues: List<Cue>, targetChars: Int = 600): List<TranscriptChunk> {
            val c = TranscriptChunker(targetChars)
            val out = ArrayList<TranscriptChunk>()
            for (cue in cues) c.add(cue)?.let { out += it }
            c.finish()?.let { out += it }
            return out
        }
    }
}
