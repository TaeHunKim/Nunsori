package com.lsa.app

import android.net.Uri
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.ml.embedding.Embedder
import java.io.File

/** Deterministic stand-in for the real model: different texts give different (normalised) vectors. */
class FakeEmbedder(override val modelId: String = "fake", private val audio: Boolean = false) : Embedder {
    val audioClips = mutableListOf<Int>() // WAV sizes in bytes
    val documents = mutableListOf<Pair<String, String?>>()
    var images = 0

    private fun vec(seed: Int): FloatArray {
        val r = java.util.Random(seed.toLong())
        val v = FloatArray(16) { r.nextFloat() - 0.5f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(16) { v[it] / n }
    }

    override suspend fun embedImage(jpeg: ByteArray): FloatArray { images++; return vec(jpeg.size) }
    override suspend fun embedDocument(text: String, title: String?): FloatArray { documents += text to title; return vec(text.hashCode()) }
    override suspend fun embedQuery(text: String): FloatArray = vec(text.hashCode())
    override val supportsAudio: Boolean get() = audio
    override suspend fun embedAudio(wav: ByteArray): FloatArray { audioClips += wav.size; return vec(wav.size) }
    override fun close() {}
}

/** Collects what an extractor emits instead of writing to the database. */
class CollectingSink : ItemSink {
    val segments = mutableListOf<NewSegment>()
    var title: String? = null
    var pageCount: Int? = null
    var lastProgress = 0L
    var batches = 0

    override suspend fun meta(title: String?, latitude: Double?, longitude: Double?, durationMs: Long?, pageCount: Int?) {
        title?.let { this.title = it }; pageCount?.let { this.pageCount = it }
    }

    override suspend fun segments(segments: List<NewSegment>, progress: Long) {
        this.segments += segments; lastProgress = progress; batches++
    }
}

fun fileEntry(file: File, kind: ItemKind) = SourceEntry(
    sourceKey = "fs:${file.absolutePath}", kind = kind, uri = Uri.fromFile(file), folderKey = "x/", displayName = file.name,
    mimeType = null, sizeBytes = file.length(), modifiedAt = file.lastModified(),
)

/** Speech tools with scripted results. */
class FakeSpeech(
    override val canTranscribe: Boolean = true,
    private val spans: List<com.lsa.app.indexing.audio.Span> = emptyList(),
    private val cues: List<com.lsa.app.indexing.audio.Cue> = emptyList(),
) : com.lsa.app.indexing.audio.SpeechTools {
    var transcribedFrom: Long? = null
    override suspend fun speechSpans(pcm: File) = spans
    override suspend fun transcribe(pcm: File, fromMs: Long, onCue: suspend (com.lsa.app.indexing.audio.Cue) -> Unit) {
        transcribedFrom = fromMs
        cues.filter { it.startMs >= fromMs }.forEach { onCue(it) }
    }
}
