package com.lsa.app.ml

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
}
