package io.github.taehunkim.nunsori.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NunsoriSdkTest {
    @Test fun errorsRoundTrip() {
        val e = NunsoriErrors.decode(NunsoriErrors.encode(Nunsori.Errors.BAD_INPUT, "unknown tool x"))!!
        assertEquals(Nunsori.Errors.BAD_INPUT, e.code)
        assertEquals("unknown tool x", e.message)
        assertNull(NunsoriErrors.decode(IllegalStateException("something else")))
    }

    @Test fun refusalReasons() {
        val approval = SecurityException(NunsoriErrors.encode(Nunsori.Errors.NOT_APPROVED, "waiting").message)
        assertEquals(NunsoriUnavailableException.Reason.NOT_APPROVED, NunsoriErrors.refusal(approval).reason)
        assertEquals(NunsoriUnavailableException.Reason.NOT_ALLOWED, NunsoriErrors.refusal(SecurityException("no")).reason)
    }
}
