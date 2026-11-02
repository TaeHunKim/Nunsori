package com.lsa.app.chat

import com.lsa.app.chat.actions.ActionPayloads
import com.lsa.app.chat.actions.ActionPayloads.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ActionPayloadsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun event(title: String = "", start: String = "", end: String = "") = ActionPayloads.calendarEvent(title, start, end, zone = seoul)

    @Test fun momentAcceptsDateAndDateTimeForms() {
        assertEquals(false, ActionPayloads.parseMoment("2026-12-05 14:00", seoul)!!.dateOnly)
        assertEquals(ActionPayloads.parseMoment("2026-12-05T14:00", seoul)!!.ms, ActionPayloads.parseMoment("2026-12-05 14:00", seoul)!!.ms)
        assertEquals(ActionPayloads.parseMoment("2026-12-05 9:30", seoul)!!.ms, ActionPayloads.parseMoment("2026-12-05 09:30", seoul)!!.ms)
        assertTrue(ActionPayloads.parseMoment("2026-12-05", seoul)!!.dateOnly)
        assertNull(ActionPayloads.parseMoment("next friday", seoul))
        assertNull(ActionPayloads.parseMoment("", seoul))
    }

    @Test fun aTimedEventDefaultsToOneHour() {
        val e = (event("Wedding", "2026-12-05 14:00") as Parsed.Ok).value
        assertEquals(false, e.allDay)
        assertEquals(3_600_000L, e.endMs - e.startMs)
    }

    @Test fun aDateOnlyEventIsAllDayInUtcWithExclusiveEnd() {
        val e = (event("Trip", "2026-12-05") as Parsed.Ok).value
        assertTrue(e.allDay)
        assertEquals(0L, e.startMs % 86_400_000L)
        assertEquals(86_400_000L, e.endMs - e.startMs)
        val span = (event("Trip", "2026-12-05", "2026-12-07") as Parsed.Ok).value
        assertEquals(3 * 86_400_000L, span.endMs - span.startMs)
    }

    @Test fun badEventsReturnErrorsTheModelCanActOn() {
        assertTrue((event(start = "2026-12-05") as Parsed.Error).message.startsWith("Error"))
        assertTrue((event("x", "soon") as Parsed.Error).message.contains("start"))
        assertTrue((event("x", "2026-12-05 14:00", "2026-12-05 13:00") as Parsed.Error).message.contains("before"))
    }

    @Test fun aContactNeedsAtLeastOneField() {
        assertTrue(ActionPayloads.contact("", note = "x") is Parsed.Error)
        val c = (ActionPayloads.contact(" Kim ", "010-1234-5678") as Parsed.Ok).value
        assertEquals("Kim", c.name)
        assertEquals("010-1234-5678", c.phone)
    }
}
