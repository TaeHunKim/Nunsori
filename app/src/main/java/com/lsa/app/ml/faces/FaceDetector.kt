package com.lsa.app.ml.faces

/** A face in a photo: box in 0..1 image coordinates, quality 0..1 and an L2-normalized identity vector. */
@Suppress("ArrayInDataClass")
data class Face(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float, val vector: FloatArray)

/** Finds faces and their identity vectors (run by OndolSaem, category "face"). */
interface FaceDetector {
    val modelId: String
    /** [jpeg]: the photo, JPEG-encoded. */
    suspend fun detect(jpeg: ByteArray): List<Face>
}
