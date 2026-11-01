package com.lsa.app.indexing.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {
    private val cfg = ChunkerConfig(targetChars = 300, maxChars = 400, minChars = 80, overlapChars = 60)
    private val md = MarkdownChunker(cfg)

    private fun words(n: Int) = "lorem ".repeat(n).trim() + "."

    @Test fun headingPathIsTheChunkTitle() {
        val doc = """
            # Guide

            ## Install

            ${words(30)}

            ### Linux

            ${words(30)}
        """.trimIndent()
        val r = md.chunk(doc, "guide.md")
        assertTrue(r.chunks.any { it.title == "Guide > Install" })
        assertTrue(r.chunks.any { it.title?.startsWith("Guide > Install") == true })
        assertEquals("Guide", r.title)
    }

    @Test fun headingTextStaysInsideTheChunk() {
        val r = md.chunk("# Title\n\n## Budget 2026\n\nWe spend ${words(10)}", "x.md")
        assertTrue(r.chunks.joinToString("\n") { it.text }.contains("Budget 2026"))
    }

    @Test fun codeFenceIsNotSplitWhenItFits() {
        val code = "```kotlin\nfun main() {\n    println(\"hi\")\n}\n```"
        val r = md.chunk("# T\n\nIntro text ${words(5)}\n\n$code\n\nAfter.", "x.md")
        val c = r.chunks.first { it.text.contains("fun main") }
        assertTrue(c.text.contains("```kotlin") && c.text.contains("println(\"hi\")") && c.text.trimEnd().contains("```"))
    }

    @Test fun tableStaysWholeAndBigTablesRepeatTheHeader() {
        val rows = (1..60).joinToString("\n") { "| row$it | value $it |" }
        val table = "| Name | Value |\n| --- | --- |\n$rows"
        val r = md.chunk("# T\n\n$table", "x.md")
        val withRows = r.chunks.filter { it.text.contains("| row") }
        assertTrue(withRows.size > 1)
        for (c in withRows) assertTrue("header repeated", c.text.contains("| Name | Value |"))
    }

    @Test fun listsAreKeptTogether() {
        val list = (1..6).joinToString("\n") { "- item $it" }
        val r = md.chunk("# T\n\nBefore.\n\n$list\n\nAfter.", "x.md")
        assertTrue(r.chunks.any { c -> (1..6).all { c.text.contains("- item $it") } })
    }

    @Test fun frontMatterIsRemovedAndProvidesTheTitle() {
        val r = md.chunk("---\ntitle: \"Quarterly plan\"\ntags: [a]\n---\n\nBody text here ${words(5)}", "x.md")
        assertEquals("Quarterly plan", r.title)
        assertFalse(r.chunks.joinToString { it.text }.contains("tags:"))
    }

    @Test fun h1AndH2StartNewChunksOnceBigEnough() {
        val doc = "# A\n\n${words(60)}\n\n## B\n\n${words(60)}\n\n## C\n\n${words(60)}"
        val r = md.chunk(doc, "x.md")
        assertTrue(r.chunks.size >= 3)
        assertTrue(r.chunks.any { it.title == "A > B" } && r.chunks.any { it.title == "A > C" })
    }

    @Test fun tinySectionsAreMergedInsteadOfBecomingTinyChunks() {
        val doc = "# A\n\nshort.\n\n## B\n\nalso short.\n\n## C\n\nshort too."
        val r = md.chunk(doc, "x.md")
        assertEquals(1, r.chunks.size)
        assertTrue(r.chunks[0].text.contains("also short."))
    }

    @Test fun veryLongParagraphIsSplitWithinLimits() {
        val r = md.chunk("# T\n\n" + (1..80).joinToString(" ") { "Sentence $it here." }, "x.md")
        assertTrue(r.chunks.size > 1)
        for (c in r.chunks) assertTrue(c.text.length <= cfg.maxChars + cfg.overlapChars)
    }

    @Test fun unclosedFenceAndOddMarkdownStillProduceText() {
        val r = md.chunk("# T\n\n```\nnever closed\n\n- [ ] ***weird* **markup\n", "x.md")
        assertTrue(r.chunks.isNotEmpty())
        assertTrue(r.chunks.joinToString { it.text }.contains("never closed"))
    }

    @Test fun nonMarkdownTextFallsBackToPlainChunking() {
        val r = md.chunk((1..30).joinToString("\n\n") { "paragraph $it ${words(8)}" }, "notes.md")
        assertTrue(r.chunks.size > 1)
        assertNotNull(r.chunks.first().title)
    }

    @Test fun emptyDocumentHasNoChunks() = assertTrue(md.chunk("", "x.md").chunks.isEmpty())

    @Test fun koreanMarkdownKeepsHeadingPath() {
        val r = md.chunk("# 회의록\n\n## 안건\n\n프로젝트 출시 일정은 12월 15일입니다. 디자인 리뷰는 화요일입니다.", "m.md")
        assertTrue(r.chunks.any { it.title == "회의록 > 안건" })
    }
}
