package com.lsa.app.ml.embedding

/** Produces L2-normalized vectors in a shared text/image space. */
interface Embedder : AutoCloseable {
    val modelId: String

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
