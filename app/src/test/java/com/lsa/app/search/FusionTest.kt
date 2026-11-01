package com.lsa.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTest {
    @Test fun bestIsOneAndOutsideMarginIsDropped() {
        val r = Fusion.relative(mapOf(1L to 0.80f, 2L to 0.74f, 3L to 0.50f), margin = 0.12f, minSpread = 0.05f)
        assertEquals(1f, r.getValue(1L), 1e-6f)
        assertEquals(0.5f, r.getValue(2L), 1e-5f)
        assertTrue(3L !in r)
    }

    @Test fun clearWinnerAmongNoiseIsKept() {
        val img = Fusion.relative(mapOf(1L to 0.73f, 2L to 0.57f, 3L to 0.59f, 4L to 0.58f), 0.10f, 0.08f)
        assertEquals(setOf(1L), img.keys)
    }

    @Test fun flatDistributionIsTreatedAsNoise() {
        // unrelated OCR docs all score ~0.6: no signal
        val ocr = Fusion.relative(mapOf(1L to 0.613f, 2L to 0.599f, 3L to 0.596f, 4L to 0.583f), 0.08f, 0.08f)
        assertTrue(ocr.isEmpty())
    }

    @Test fun tinyCollectionsSkipSpreadCheck() =
        assertEquals(setOf(1L), Fusion.relative(mapOf(1L to 0.6f, 2L to 0.59f), 0.005f, 0.08f).keys)

    @Test fun lowTopScoreDisablesSignal() =
        assertTrue(Fusion.relative(mapOf(1L to 0.1f), 0.1f, 0.0f, minTop = 0.3f).isEmpty())

    @Test fun emptyInputGivesEmpty() = assertTrue(Fusion.relative(emptyMap(), 0.1f, 0.0f).isEmpty())
}

class KeywordMatcherTest {
    @Test fun tokensAreLowercasedAndDistinct() =
        assertEquals(listOf("invoice", "2026"), KeywordMatcher.tokens("Invoice  invoice 2026"))

    @Test fun singleAsciiLettersAreDropped() = assertEquals(listOf("cat"), KeywordMatcher.tokens("a cat"))

    @Test fun singleKoreanSyllableIsKept() = assertEquals(listOf("물"), KeywordMatcher.tokens("물"))

    @Test fun asciiTokensMatchWholeWordsOnly() {
        assertNull(KeywordMatcher.match("Platform 3", listOf("form"), "form"))
        assertNotNull(KeywordMatcher.match("Platform 3", listOf("platform"), "platform"))
    }

    @Test fun matchesKoreanSubstring() {
        val m = KeywordMatcher.match("회의록 2026 프로젝트 일정", KeywordMatcher.tokens("회의록"), "회의록")
        assertNotNull(m); assertTrue(m!!.exact); assertEquals(1, m.count)
    }

    @Test fun partialTokenMatchIsNotExact() {
        val m = KeywordMatcher.match("Total due: $61.50", KeywordMatcher.tokens("total invoice"), "total invoice")!!
        assertEquals(1, m.count); assertTrue(!m.exact)
    }

    @Test fun noMatchReturnsNull() = assertNull(KeywordMatcher.match("hello", listOf("world"), "world"))

    @Test fun snippetContainsMatch() {
        val text = "x".repeat(200) + " NEEDLE " + "y".repeat(200)
        val s = KeywordMatcher.snippet(text, listOf("needle"))
        assertTrue(s.contains("NEEDLE")); assertTrue(s.startsWith("…") && s.endsWith("…"))
    }
}
