package com.lsa.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeopleNameTest {
    private val names = listOf("Mina", "Mina Kim", "엄마", "민수")

    @Test fun exactIgnoringCaseAndSpaces() {
        assertEquals("Mina", PeopleDirectory.match("  mina ", names))
        assertEquals("Mina Kim", PeopleDirectory.match("mina   kim", names))
    }

    @Test fun particlesAndPossessivesAroundAName() {
        assertEquals("민수", PeopleDirectory.match("민수가", names))
        assertEquals("엄마", PeopleDirectory.match("엄마랑", names))
        assertEquals("the longest name wins", "Mina Kim", PeopleDirectory.match("Mina Kim's", names))
    }

    @Test fun partOfOneNameOnly() {
        assertEquals("Mina Kim", PeopleDirectory.match("Kim", names))
        assertNull("ambiguous", PeopleDirectory.match("Min", names))
        assertNull(PeopleDirectory.match("Obama", names))
        assertNull(PeopleDirectory.match(" ", names))
    }
}
