package com.lsa.app.ui

import com.lsa.app.ui.common.cpuTicks
import com.lsa.app.ui.common.rssKb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceParsingTest {
    @Test fun cpuTicksSumsUserAndSystemTimeEvenWithOddProcessNames() {
        // fields after ')': state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ...
        val stat = "123 (com.lsa (app)) S 1 2 3 4 5 6 7 8 9 10 250 50 0 0"
        assertEquals(300L, cpuTicks(stat))
    }

    @Test fun cpuTicksOfGarbageIsNull() = assertNull(cpuTicks("nonsense"))

    @Test fun rssIsReadFromTheStatusFile() {
        assertEquals(204800L, rssKb("Name:\tapp\nVmRSS:\t  204800 kB\nThreads:\t4\n"))
    }
}
