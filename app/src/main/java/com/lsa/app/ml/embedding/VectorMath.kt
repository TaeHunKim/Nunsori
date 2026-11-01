package com.lsa.app.ml.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    fun norm(a: FloatArray): Float = sqrt(dot(a, a))

    fun normalize(a: FloatArray): FloatArray {
        val n = norm(a)
        if (n == 0f) return a
        return FloatArray(a.size) { a[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val d = norm(a) * norm(b)
        return if (d == 0f) 0f else dot(a, b) / d
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}

/**
 * int8 storage for normalised embeddings: 4x smaller than float32 with ~1e-3 cosine error.
 * Layout: `[scale: float32 LE][value: int8] * dim`, where `value * scale ~ original component`.
 */
object Quant {
    private const val HEADER = 4

    fun encode(v: FloatArray): ByteArray {
        var maxAbs = 0f
        for (x in v) { val a = kotlin.math.abs(x); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs == 0f) 1f else maxAbs / 127f
        val out = ByteArray(HEADER + v.size)
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(scale)
        for (i in v.indices) out[HEADER + i] = Math.round(v[i] / scale).coerceIn(-127, 127).toByte()
        return out
    }

    fun scale(blob: ByteArray): Float =
        java.nio.ByteBuffer.wrap(blob, 0, HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN).float

    fun dim(blob: ByteArray): Int = blob.size - HEADER

    fun decode(blob: ByteArray): FloatArray {
        val s = scale(blob)
        return FloatArray(blob.size - HEADER) { blob[HEADER + it] * s }
    }

    /** Dot product of a float query with a stored vector. */
    fun dot(query: FloatArray, blob: ByteArray): Float {
        val n = minOf(query.size, blob.size - HEADER)
        var s = 0f
        for (i in 0 until n) s += query[i] * blob[HEADER + i]
        return s * scale(blob)
    }
}
