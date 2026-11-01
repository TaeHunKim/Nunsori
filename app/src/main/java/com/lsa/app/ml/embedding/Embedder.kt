package com.lsa.app.ml.embedding

/** Produces L2-normalized vectors in a shared text/image space. */
interface Embedder : AutoCloseable {
    val modelId: String

    /** [jpeg] is an already-downscaled JPEG. */
    suspend fun embedImage(jpeg: ByteArray): FloatArray

    /** Text to be indexed (e.g. OCR text). Prefix handling is internal. */
    suspend fun embedDocument(text: String): FloatArray

    /** User search query. Prefix handling is internal. */
    suspend fun embedQuery(text: String): FloatArray
}
