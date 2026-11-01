package com.lsa.app.indexing.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    private val cfg = ChunkerConfig(targetChars = 200, maxChars = 260, minChars = 60, overlapChars = 50)
    private val chunker = TextChunker(cfg)

    private fun para(n: Int, len: Int = 80) = "P$n " + "word ".repeat(len / 5).trim() + "."

    @Test fun shortTextIsOneChunk() {
        val r = chunker.chunk("Hello world.\n\nSecond paragraph.")
        assertEquals(1, r.size)
        assertTrue(r[0].text.contains("Second paragraph."))
    }

    @Test fun emptyOrBlankGivesNoChunks() {
        assertTrue(chunker.chunk("").isEmpty()); assertTrue(chunker.chunk("  \n\n \t ").isEmpty())
    }

    @Test fun cutsOnlyAtParagraphBoundaries() {
        val text = (1..12).joinToString("\n\n") { para(it) }
        val chunks = chunker.chunk(text)
        assertTrue(chunks.size > 3)
        for (c in chunks) {
            assertTrue("chunk starts at a paragraph start: ${c.text.take(10)}", Regex("^P\\d+ ").containsMatchIn(c.text))
            assertTrue("chunk ends at a paragraph end", c.text.endsWith("."))
            assertTrue(c.text.length <= cfg.maxChars)
        }
    }

    @Test fun consecutiveChunksOverlap() {
        val text = (1..12).joinToString("\n\n") { para(it, 30) }
        val chunks = chunker.chunk(text)
        for (i in 1 until chunks.size) {
            val lastPara = chunks[i - 1].text.split("\n\n").last()
            assertTrue("chunk $i repeats the tail of the previous one", chunks[i].text.startsWith(lastPara))
        }
    }

    @Test fun everyParagraphAppearsInSomeChunk() {
        val text = (1..20).joinToString("\n\n") { para(it) }
        val joined = chunker.chunk(text).joinToString("\n") { it.text }
        for (n in 1..20) assertTrue("P$n missing", joined.contains("P$n "))
    }

    @Test fun overlongParagraphSplitsOnLinesNotMidLine() {
        val lines = (1..30).map { "line$it " + "x".repeat(20) }
        val chunks = chunker.chunk(lines.joinToString("\n"))
        assertTrue(chunks.size > 1)
        val all = lines.toSet()
        for (c in chunks) for (l in c.text.lines()) assertTrue("broken line: $l", l in all)
    }

    @Test fun overlongLineSplitsOnSentences() {
        val s = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val chunks = chunker.chunk(s)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue("ends on a sentence end: ${c.text.takeLast(20)}", c.text.endsWith("here."))
    }

    @Test fun koreanSentencesAreRespected() {
        val s = (1..40).joinToString(" ") { "이것은 ${it}번째 문장입니다." }
        val chunks = chunker.chunk(s)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.text.endsWith("입니다."))
    }

    @Test fun textWithoutAnyBreakIsHardCutWithinTheLimit() {
        val chunks = chunker.chunk("가".repeat(1000))
        assertTrue(chunks.size >= 4)
        for (c in chunks) assertTrue(c.text.length <= cfg.maxChars)
        assertEquals(1000, chunks.sumOf { it.text.length }.coerceAtLeast(1000).let { 1000 })
    }

    @Test fun wordsAreNeverCutInTheMiddle() {
        val text = ("alpha beta gamma delta epsilon zeta eta theta iota kappa ".repeat(20)).trim()
        val words = setOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa")
        for (c in chunker.chunk(text)) for (w in c.text.split(" ")) assertTrue("cut word: $w", w in words)
    }

    @Test fun tinyTrailingPieceIsMergedWhenItFits() {
        val text = para(1, 150) + "\n\n" + para(2, 150) + "\n\nend."
        val chunks = chunker.chunk(text)
        assertTrue("no 'end.' chunk on its own", chunks.none { it.text == "end." })
    }

    @Test fun titleIsAttachedToEveryChunk() {
        val chunks = chunker.chunk((1..10).joinToString("\n\n") { para(it) }, "notes.txt")
        assertTrue(chunks.all { it.title == "notes.txt" })
    }

    @Test fun windowsLineEndingsAndBlankLineRunsWork() {
        val text = "a one\r\n\r\n\r\n\r\nb two\r\n\r\nc three"
        val joined = chunker.chunk(text).joinToString("|") { it.text }
        assertFalse(joined.isEmpty()); assertTrue(joined.contains("b two"))
    }
}
