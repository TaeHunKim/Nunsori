package com.lsa.app.ml

import io.github.taehunkim.ondolsaem.sdk.HostUnavailableException.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class HostStatusTest {
    @Test fun everyReasonHasItsOwnStatus() {
        assertEquals(HostState.Status.NOT_INSTALLED, ModelHost.statusFor(Reason.NOT_INSTALLED))
        assertEquals(HostState.Status.NOT_ALLOWED, ModelHost.statusFor(Reason.NOT_ALLOWED))
        assertEquals(HostState.Status.UNREACHABLE, ModelHost.statusFor(Reason.NO_ANSWER))
        assertEquals(HostState.Status.HOST_TOO_OLD, ModelHost.statusFor(Reason.HOST_TOO_OLD))
        assertEquals(HostState.Status.CLIENT_TOO_OLD, ModelHost.statusFor(Reason.CLIENT_TOO_OLD))
    }
}
