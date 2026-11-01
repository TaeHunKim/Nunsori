package com.lsa.app.indexing.video

import com.lsa.app.indexing.audio.Span
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frame-level helpers that need no Android classes, so they can be unit-tested. */
object FrameStats {
    const val GRID = 16

    /** Mean luminance of each cell of a [GRID]x[GRID] grid over ARGB [pixels] of a [w]x[h] image. */
    fun lumaGrid(pixels: IntArray, w: Int, h: Int): FloatArray {
        val sum = FloatArray(GRID * GRID); val count = IntArray(GRID * GRID)
        for (y in 0 until h) {
            val gy = y * GRID / h
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val l = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                val i = gy * GRID + x * GRID / w
                sum[i] += l; count[i]++
            }
        }
        return FloatArray(GRID * GRID) { if (count[it] == 0) 0f else sum[it] / count[it] }
    }

    /** Mean absolute luminance difference between two grids, 0..1. */
    fun difference(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size / 255f
    }
}

/** A stretch of a video that is embedded as one unit. */
data class Clip(val startMs: Long, val endMs: Long, val frames: IntRange)

/**
 * Turns sampled frames into clips: a new clip starts at every scene change; scenes that are too long are cut into
 * overlapping parts, scenes that are too short are merged into a neighbour.
 */
object VideoClips {
    const val SCENE_THRESHOLD = 0.10f
    const val MIN_MS = 4_000L
    const val MAX_MS = 20_000L
    const val OVERLAP = 0.2
    const val MAX_FRAMES_PER_CLIP = 8

    /**
     * @param timesMs timestamps of the sampled frames (ascending)
     * @param diffs `diffs[i]` is the change between frame i-1 and i (`diffs[0]` is ignored)
     */
    fun plan(timesMs: List<Long>, diffs: List<Float>, durationMs: Long, threshold: Float = SCENE_THRESHOLD): List<Clip> {
        if (timesMs.isEmpty()) return emptyList()
        // 1. scenes = runs of frames without a hard cut
        val scenes = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until timesMs.size) if (diffs.getOrElse(i) { 0f } > threshold) { scenes += start until i; start = i }
        scenes += start until timesMs.size
        fun endOf(r: IntRange, next: IntRange?) = next?.let { timesMs[it.first] } ?: durationMs

        // 2. merge scenes shorter than MIN_MS into the previous one (or the next, for the very first)
        val merged = ArrayList<IntRange>()
        for ((k, s) in scenes.withIndex()) {
            val len = endOf(s, scenes.getOrNull(k + 1)) - timesMs[s.first]
            if (len < MIN_MS && merged.isNotEmpty()) merged[merged.size - 1] = merged.last().first..s.last
            else merged += s
        }
        if (merged.size > 1) {
            val firstLen = timesMs[merged[1].first] - timesMs[merged[0].first]
            if (firstLen < MIN_MS) { merged[1] = merged[0].first..merged[1].last; merged.removeAt(0) }
        }

        // 3. cut long scenes into overlapping parts of at most MAX_MS
        val out = ArrayList<Clip>()
        for ((k, s) in merged.withIndex()) {
            val sceneEnd = endOf(s, merged.getOrNull(k + 1))
            var from = s.first
            while (true) {
                var to = from
                while (to + 1 <= s.last && timesMs[to + 1] - timesMs[from] < MAX_MS) to++
                val last = to >= s.last
                val end = if (last) sceneEnd else timesMs[to + 1]
                out += Clip(timesMs[from], end, from..to)
                if (last) break
                // next part starts OVERLAP of the clip earlier, but always moves forward
                val back = ((to - from + 1) * OVERLAP).roundToInt()
                from = maxOf(from + 1, to + 1 - back)
            }
        }
        return out
    }

    /** At most [max] frame indexes of [r], evenly spread and always including the first and last. */
    fun pick(r: IntRange, max: Int = MAX_FRAMES_PER_CLIP): List<Int> {
        val n = r.last - r.first + 1
        if (n <= max) return r.toList()
        return (0 until max).map { r.first + (it * (n - 1) / (max - 1)) }.distinct()
    }

    /** Seconds between sampled frames for a detail level, widened so very long videos stay within [maxFrames]. */
    fun sampleIntervalMs(detailMs: Long, durationMs: Long, maxFrames: Int = 600): Long = maxOf(detailMs, durationMs / maxFrames)

    fun sampleTimes(durationMs: Long, intervalMs: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = 0L
        while (t < durationMs) { out += t; t += intervalMs }
        return out.ifEmpty { listOf(0L) }
    }
}

/** How many frames of a video are looked at. */
enum class VideoDetail(val intervalMs: Long) { LOW(5_000), MEDIUM(2_000), HIGH(1_000) }
