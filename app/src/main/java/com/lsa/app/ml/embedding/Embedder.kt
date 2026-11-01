package com.lsa.app.ml.embedding

/** Produces L2-normalized vectors in a shared text/image space. */
interface Embedder : AutoCloseable {
    val modelId: String

    /** "GPU" or "CPU" once loaded; empty for fakes. Shown by the resource indicator. */
    val backend: String get() = ""

    /** [jpeg] is an already-downscaled JPEG. */
    suspend fun embedImage(jpeg: ByteArray): FloatArray

    /** Text to be indexed (e.g. OCR text). Prefix handling is internal. */
    suspend fun embedDocument(text: String, title: String? = null): FloatArray

    /** User search query. Prefix handling is internal. */
    suspend fun embedQuery(text: String): FloatArray

    /** Whether [embedAudio] is available (EmbeddingGemma 2 740M only). */
    val supportsAudio: Boolean get() = false

    /** [wav] is a complete WAV file (16 kHz, mono, 16-bit). */
    suspend fun embedAudio(wav: ByteArray): FloatArray = throw UnsupportedOperationException("audio")

    /** Several frames of one clip -> one vector. */
    suspend fun embedImages(jpegs: List<ByteArray>): FloatArray = throw UnsupportedOperationException("images")
}

/**
 * The model has a hard 1024-token limit and no tokenizer is exposed, so an overlong input is only
 * discovered when the call fails ("input sequence length (N) exceeds ..."). Retry with a shorter
 * prefix of [text] (60% each time, at most [attempts] shortenings) before giving up.
 */
suspend fun <T> retryShorter(text: String, attempts: Int = 5, block: suspend (String) -> T): T {
    var t = text
    repeat(attempts) {
        try { return block(t) } catch (e: Exception) {
            if (e.message?.contains("exceeds", ignoreCase = true) != true) throw e
            t = t.take((t.length * 0.6).toInt()).trimEnd()
        }
    }
    return block(t)
}
