package com.lsa.app.indexing.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.indexing.audio.AudioDecoder
import com.lsa.app.indexing.audio.AudioExtractor
import com.lsa.app.indexing.audio.SpeechTools
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.ml.Lease
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Video = clips. Frames are sampled, scene changes decide where clips start, and every clip becomes one embedding
 * of several of its frames. Speech in the sound track is transcribed as a second stage.
 * `progress` is the number of clips done, or [AUDIO_STAGE] + position (ms) in the transcription.
 */
class VideoExtractor(
    private val context: Context,
    private val embedder: Embedder,
    private val thumbs: ThumbStore,
    private val detail: VideoDetail,
    private val transcribe: Boolean,
    private val speech: suspend () -> Lease<SpeechTools>,
    private val scratchDir: File,
) {
    suspend fun extract(entry: SourceEntry, resume: Long, sink: ItemSink) {
        val mmr = MediaMetadataRetriever()
        try {
            try {
                if (entry.uri.scheme == "file") mmr.setDataSource(entry.uri.path) else mmr.setDataSource(context, entry.uri)
            } catch (e: Exception) { throw SkipItem("cannot open video: ${e.message}") }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw SkipItem("unknown duration")
            if (durationMs < 500) throw SkipItem("too short")
            sink.meta(durationMs = durationMs)
            if (resume < AUDIO_STAGE) visual(mmr, entry, durationMs, resume.toInt(), sink)
            if (transcribe) transcribeSoundtrack(entry, if (resume >= AUDIO_STAGE) resume - AUDIO_STAGE else 0, sink)
        } finally { mmr.release() }
    }

    private suspend fun visual(mmr: MediaMetadataRetriever, entry: SourceEntry, durationMs: Long, doneClips: Int, sink: ItemSink) {
        val interval = VideoClips.sampleIntervalMs(detail.intervalMs, durationMs)
        val times = VideoClips.sampleTimes(durationMs, interval)

        // pass 1: tiny frames, only to find scene changes
        val diffs = ArrayList<Float>(times.size)
        var prev: FloatArray? = null
        for (t in times) {
            currentCoroutineContext().ensureActive()
            val frame = frameAt(mmr, t, 64, 64)
            // the retriever keeps the aspect ratio, so the real size can differ from the requested one
            val grid = frame?.let { bmp ->
                val bw = bmp.width; val bh = bmp.height
                val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                bmp.recycle(); FrameStats.lumaGrid(px, bw, bh)
            }
            diffs += if (grid != null && prev != null) FrameStats.difference(prev, grid) else 0f
            if (grid != null) prev = grid
        }
        val clips = VideoClips.plan(times, diffs, durationMs)
        if (clips.isEmpty()) throw SkipItem("no frames")

        // pass 2: a few larger frames per clip -> one vector
        val (w, h) = targetSize(mmr)
        for ((i, clip) in clips.withIndex().drop(doneClips)) {
            currentCoroutineContext().ensureActive()
            val picks = VideoClips.pick(clip.frames)
            val bitmaps = picks.mapNotNull { frameAt(mmr, times[it], w, h) }
            if (bitmaps.isEmpty()) { sink.segments(emptyList(), (i + 1).toLong()); continue }
            try {
                val jpegs = bitmaps.map { BitmapLoader.toJpeg(it, FRAME_SIDE) }
                val vec = embedClip(jpegs)
                val thumb = thumbs.save(entry.sourceKey, "c${i + 1}", bitmaps[bitmaps.size / 2])
                sink.segments(
                    listOf(NewSegment(SegmentType.VIDEO_CLIP, i, startMs = clip.startMs, endMs = clip.endMs, embedding = Quant.encode(vec), thumbPath = thumb)),
                    (i + 1).toLong(),
                )
            } finally { bitmaps.forEach { it.recycle() } }
        }
    }

    /** Several frames must fit the model's token limit; on overflow retry with every other frame. */
    private suspend fun embedClip(jpegs: List<ByteArray>): FloatArray {
        var use = jpegs
        while (true) {
            try { return embedder.embedImages(use) } catch (e: Exception) {
                if (e.message?.contains("exceeds", ignoreCase = true) != true || use.size <= 1) throw e
                use = use.filterIndexed { i, _ -> i % 2 == 0 }
            }
        }
    }

    private suspend fun transcribeSoundtrack(entry: SourceEntry, fromMs: Long, sink: ItemSink) {
        scratchDir.mkdirs()
        val pcm = File(scratchDir, "video-${entry.sourceKey.hashCode().toUInt()}.pcm")
        try {
            try { withContext(Dispatchers.IO) { AudioDecoder.decode(context, entry.uri, pcm) } } catch (e: SkipItem) { return } // silent video
            speech().use { lease ->
                if (lease.value.canTranscribe) AudioExtractor(context, embedder, lease.value, scratchDir).transcribe(pcm, fromMs, sink, AUDIO_STAGE, TRANSCRIPT_CHARS, TRANSCRIPT_SPAN_MS)
            }
        } finally { pcm.delete() }
    }

    private fun frameAt(mmr: MediaMetadataRetriever, ms: Long, w: Int, h: Int): Bitmap? =
        try { mmr.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, w, h) } catch (e: Exception) { null }

    /** Frame size that keeps the (rotated) aspect ratio with the long side at [FRAME_SIDE]. */
    private fun targetSize(mmr: MediaMetadataRetriever): Pair<Int, Int> {
        var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: FRAME_SIDE
        var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: FRAME_SIDE
        if ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0) { val t = w; w = h; h = t }
        val scale = FRAME_SIDE.toFloat() / maxOf(w, h)
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }

    companion object {
        /** Progress at or above this means "visual stage done". */
        const val AUDIO_STAGE = 1_000_000_000_000L
        const val FRAME_SIDE = 448
        /** Shorter than for recordings, so a hit says roughly *when* in the video something was said. */
        const val TRANSCRIPT_CHARS = 220
        const val TRANSCRIPT_SPAN_MS = 10_000L
    }
}
