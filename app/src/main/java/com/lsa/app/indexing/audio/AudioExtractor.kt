package com.lsa.app.indexing.audio

import android.content.Context
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Audio in two stages, each committed as it goes so a stopped run resumes where it left off:
 *  A. speech recognition -> transcript chunks (text embeddings with time ranges)
 *  B. ~30 s audio windows cut at silences (audio embeddings) — only with an audio-capable embedding model
 * `progress` is the position in stage A (ms), or [STAGE_B] + windows done.
 */
class AudioExtractor(
    private val context: Context,
    private val embedder: Embedder,
    private val speech: SpeechTools,
    private val scratchDir: File,
) {
    suspend fun extract(entry: SourceEntry, resume: Long, sink: ItemSink, decode: Boolean = true) {
        if (!speech.canTranscribe && !embedder.supportsAudio) {
            throw SkipItem("needs a speech recognition model or the audio-capable embedding model")
        }
        scratchDir.mkdirs()
        val pcm = File(scratchDir, "audio-${entry.sourceKey.hashCode().toUInt()}.pcm")
        try {
            val durationMs = withContext(Dispatchers.IO) { AudioDecoder.decode(context, entry.uri, pcm) }
            if (durationMs < MIN_MS) throw SkipItem("too short")
            sink.meta(durationMs = durationMs)
            runStages(pcm, durationMs, resume, sink)
        } finally { pcm.delete() }
    }

    internal suspend fun runStages(pcm: File, durationMs: Long, resume: Long, sink: ItemSink) {
        if (resume < STAGE_B && speech.canTranscribe) transcribe(pcm, resume, sink)
        if (embedder.supportsAudio) embedWindows(pcm, durationMs, (resume - STAGE_B).coerceAtLeast(0), sink)
    }

    /** Stage A on its own (also used for the sound track of videos). [progressOffset] is added to reported progress. */
    suspend fun transcribe(pcm: File, fromMs: Long, sink: ItemSink, progressOffset: Long = 0, targetChars: Int = 600, maxSpanMs: Long = Long.MAX_VALUE) {
        val chunker = TranscriptChunker(targetChars, maxSpanMs = maxSpanMs)
        var ordinal = (fromMs / 1000).toInt() // only used for ordering; unique per run position
        val batch = ArrayList<NewSegment>()
        suspend fun emit(c: TranscriptChunk) {
            batch += NewSegment(
                SegmentType.TRANSCRIPT, ordinal++, startMs = c.startMs, endMs = c.endMs, text = c.text,
                embedding = Quant.encode(embedder.embedDocument(c.text)),
            )
            sink.segments(batch.toList(), progress = progressOffset + c.endMs); batch.clear()
        }
        speech.transcribe(pcm, fromMs) { cue -> chunker.add(cue)?.let { emit(it) } }
        chunker.finish()?.let { emit(it) }
    }

    private suspend fun embedWindows(pcm: File, durationMs: Long, doneWindows: Long, sink: ItemSink) {
        val windows = AudioWindows.plan(durationMs, speech.speechSpans(pcm))
        RandomAccessFile(pcm, "r").use { f ->
            for ((i, w) in windows.withIndex().drop(doneWindows.toInt())) {
                currentCoroutineContext().ensureActive()
                val from = Pcm.msToByte(w.startMs); val to = minOf(Pcm.msToByte(w.endMs), f.length())
                if (to - from < Pcm.BYTES_PER_SECOND) { sink.segments(emptyList(), STAGE_B + i + 1); continue }
                val bytes = ByteArray((to - from).toInt()).also { f.seek(from); f.readFully(it) }
                if (Pcm.rms(bytes) < SILENCE_RMS) { sink.segments(emptyList(), STAGE_B + i + 1); continue }
                val v = embedder.embedAudio(Pcm.toWav(bytes))
                sink.segments(
                    listOf(NewSegment(SegmentType.AUDIO_CLIP, 1_000_000 + i, startMs = w.startMs, endMs = w.endMs, embedding = Quant.encode(v))),
                    STAGE_B + i + 1,
                )
            }
        }
    }

    companion object {
        /** Progress values at or above this mean "stage A is done". */
        const val STAGE_B = 1_000_000_000_000L
        const val MIN_MS = 1_000L
        /** About -54 dBFS: below this a window is treated as digital silence and not embedded. */
        const val SILENCE_RMS = 0.002
    }
}
