package com.lsa.app.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastAutoRunTest {
    @Test fun roundTrips() {
        val r = LastAutoRun(1_700_000_000_000, "trigger", AutoOutcome.INDEXED, 3)
        assertEquals(r, LastAutoRun.decode(r.encode()))
    }

    @Test fun badInputIsIgnored() {
        assertNull(LastAutoRun.decode(null)); assertNull(LastAutoRun.decode("x")); assertNull(LastAutoRun.decode("a|b|c|d"))
    }
}
