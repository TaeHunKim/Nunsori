package com.lsa.app.ml

import com.lsa.app.indexing.ResidencyPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeasedResourceTest {
    private class Res : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun idlePolicyUnloadsAfterTwoMinutes() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.IDLE_2M }
        res.acquire("k") { Res() }!!.close(); runCurrent()
        advanceTimeBy(119_000); runCurrent()
        assertTrue(res.isLoaded)
        advanceTimeBy(2_000); runCurrent()
        assertFalse(res.isLoaded)
    }

    @Test fun immediatePolicyUnloadsOnRelease() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.IMMEDIATE }
        val r = res.acquire("k") { Res() }!!
        assertTrue(res.isLoaded)
        r.close(); runCurrent()
        assertFalse(res.isLoaded)
    }

    @Test fun keepPolicyNeverUnloads() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.KEEP }
        res.acquire("k") { Res() }!!.close(); runCurrent()
        advanceTimeBy(24 * 3600_000L); runCurrent()
        assertTrue(res.isLoaded)
    }

    @Test fun neverUnloadedWhileLeased() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.IMMEDIATE }
        val a = res.acquire("k") { Res() }!!
        val b = res.acquire("k") { error("must reuse") }!!
        a.close(); runCurrent()
        assertTrue("still leased by b", res.isLoaded)
        b.close(); runCurrent()
        assertFalse(res.isLoaded)
    }

    @Test fun reacquireCancelsPendingUnload() = runTest {
        var loads = 0
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.IDLE_2M }
        res.acquire("k") { loads++; Res() }!!.close(); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        val again = res.acquire("k") { loads++; Res() }!!
        advanceTimeBy(60_000); runCurrent() // past the original deadline
        assertTrue(res.isLoaded)
        assertEquals("reused, not reloaded", 1, loads)
        again.close()
    }

    @Test fun differentKeyReplacesAndClosesOld() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.KEEP }
        val first = res.acquire("a") { Res() }!!
        val firstRes = first.value
        first.close(); runCurrent()
        res.acquire("b") { Res() }!!
        assertTrue(firstRes.closed)
    }

    @Test fun trimOnlyWhenIdle() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.KEEP }
        val l = res.acquire("k") { Res() }!!
        res.trim(); assertTrue(res.isLoaded)
        l.close(); runCurrent()
        res.trim(); assertFalse(res.isLoaded)
    }

    @Test fun nullLoadYieldsNullLease() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.KEEP }
        assertEquals(null, res.acquire("k") { null })
    }

    @Test fun doubleCloseReleasesOnce() = runTest {
        val res = LeasedResource<Res>("t", backgroundScope) { ResidencyPolicy.IMMEDIATE }
        val a = res.acquire("k") { Res() }!!
        val b = res.acquire("k") { Res() }!!
        a.close(); a.close(); runCurrent()
        assertTrue("b still holds it", res.isLoaded)
        b.close(); runCurrent()
        assertFalse(res.isLoaded)
    }
}
