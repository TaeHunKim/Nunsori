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
