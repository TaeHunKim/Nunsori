package com.lsa.app.indexing.audio

import java.io.File

/** Voice activity detection and speech recognition on a decoded PCM file (16 kHz mono 16-bit). Runs in OndolSaem ([com.lsa.app.ml.ModelHost]). */
interface SpeechTools : AutoCloseable {
    val canTranscribe: Boolean

    /** Time ranges that contain speech (voice activity detection only). */
    suspend fun speechSpans(pcm: File): List<Span>

    /** Streams recognised utterances from [fromMs] on. Needs an ASR model ([canTranscribe]). */
    suspend fun transcribe(pcm: File, fromMs: Long, onCue: suspend (Cue) -> Unit)

    override fun close() {}
}
