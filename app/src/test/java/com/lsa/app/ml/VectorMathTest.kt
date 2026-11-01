package com.lsa.app.ml

import com.lsa.app.ml.embedding.Quant
import com.lsa.app.ml.embedding.VectorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {
    @Test fun cosineOfIdenticalIsOne() = assertEquals(1f, VectorMath.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-6f)
    @Test fun cosineOfOrthogonalIsZero() = assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    @Test fun normalizeGivesUnitLength() = assertEquals(1f, VectorMath.norm(VectorMath.normalize(floatArrayOf(3f, 4f))), 1e-6f)
    @Test fun zeroVectorSafe() = assertEquals(0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    @Test fun bytesRoundTrip() {
        val v = floatArrayOf(0.5f, -1.25f, 3.0e-5f, 768f)
        assertArrayEquals(v, VectorMath.fromBytes(VectorMath.toBytes(v)), 0f)
    }

    @Test fun quantizedRoundTripIsClose() {
        val rnd = java.util.Random(7)
        val v = VectorMath.normalize(FloatArray(768) { rnd.nextGaussian().toFloat() })
        val back = Quant.decode(Quant.encode(v))
        assertEquals(1.0f, VectorMath.cosine(v, back), 1e-3f)
    }

    @Test fun quantizedDotApproximatesFloatDot() {
        val rnd = java.util.Random(11)
        val a = VectorMath.normalize(FloatArray(768) { rnd.nextGaussian().toFloat() })
        val b = VectorMath.normalize(FloatArray(768) { rnd.nextGaussian().toFloat() })
        assertEquals(VectorMath.dot(a, b), Quant.dot(a, Quant.encode(b)), 5e-3f)
    }

    @Test fun quantizedSizeIsHeaderPlusOneBytePerDim() = assertEquals(4 + 768, Quant.encode(FloatArray(768) { 0.1f }).size)

    @Test fun zeroVectorQuantizes() = assertEquals(0f, Quant.dot(floatArrayOf(1f, 1f), Quant.encode(floatArrayOf(0f, 0f))), 0f)
}
