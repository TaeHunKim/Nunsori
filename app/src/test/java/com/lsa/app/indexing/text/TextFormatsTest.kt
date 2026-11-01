package com.lsa.app.indexing.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormatsTest {
    @Test fun htmlBecomesMarkdownWithHeadings() {
        val (md, title) = TextFormats.htmlToMarkdown("<html><head><title>Page</title><style>p{}</style></head><body><h1>Top</h1><p>Hello <b>big</b> world</p><script>x()</script><h2>Sub</h2><ul><li>a</li><li>b</li></ul></body></html>")
        assertEquals("Page", title)
        assertTrue(md.contains("# Top")); assertTrue(md.contains("## Sub"))
        assertTrue(md.contains("Hello big world"))
        assertFalse(md.contains("x()")); assertFalse(md.contains("p{}"))
    }

    @Test fun htmlChunksFollowHeadings() {
        val html = "<h1>Guide</h1><h2>Install</h2><p>" + "word ".repeat(80) + "</p>"
        val doc = TextRouting.chunk("html", html, "g.html")
        assertTrue(doc.chunks.any { it.title?.contains("Install") == true })
    }

    @Test fun xmlKeepsOnlyTheTextNodes() {
        val t = TextFormats.xmlToText("<root a=\"attr\"><item>First</item><item><![CDATA[Second]]></item><!-- c --></root>")
        assertTrue(t.contains("First") && t.contains("Second")); assertFalse(t.contains("attr")); assertFalse(t.contains("item"))
    }

    @Test fun malformedXmlStillYieldsText() = assertTrue(TextFormats.xmlToText("<a><b>hello</a>").contains("hello"))

    @Test fun csvHandlesQuotesAndNewlinesInCells() {
        val rows = TextFormats.parseCsv("name,note\n\"Kim, Min\",\"line1\nline2\"\nLee,\"say \"\"hi\"\"\"\n", ',')
        assertEquals(3, rows.size)
        assertEquals(listOf("Kim, Min", "line1\nline2"), rows[1]); assertEquals("say \"hi\"", rows[2][1])
    }

    @Test fun tableChunksRepeatTheHeader() {
        val rows = listOf(listOf("id", "name")) + (1..100).map { listOf("$it", "name$it") }
        val chunks = TextFormats.tableChunks(rows, ChunkerConfig(targetChars = 200, maxChars = 300, minChars = 50, overlapChars = 0))
        assertTrue(chunks.size > 3)
        for (c in chunks) assertTrue(c.text.startsWith("id | name"))
        assertTrue(chunks.joinToString { it.text }.contains("100 | name100"))
    }

    @Test fun srtCuesBecomeTimedChunks() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello <i>there</i>\n\n2\n00:00:04,000 --> 00:00:06,000\nSecond line\n\n3\n01:02:03,250 --> 01:02:05,000\nLate\n"
        val chunks = TextFormats.subtitleChunks(srt, ChunkerConfig(targetChars = 15, maxChars = 60, minChars = 5, overlapChars = 0))
        assertEquals(1_000L, chunks.first().startMs)
        assertTrue(chunks.first().text.startsWith("Hello there"))
        assertEquals(3_723_250L, chunks.last().startMs); assertEquals(3_725_000L, chunks.last().endMs)
    }

    @Test fun vttTimestampsWithoutHoursWork() {
        val chunks = TextFormats.subtitleChunks("WEBVTT\n\n00:05.000 --> 00:07.500\nShort one\n")
        assertEquals(5_000L, chunks.single().startMs); assertEquals(7_500L, chunks.single().endMs)
    }

    @Test fun routingPicksStrategyByExtension() {
        assertTrue(TextRouting.chunk("csv", "a,b\n1,2", "t.csv").chunks.single().text.startsWith("a | b"))
        assertEquals("notes.txt", TextRouting.chunk("TXT", "hello", "notes.txt").title)
        assertTrue(TextRouting.chunk("md", "# H\n\ntext", "n.md").title == "H")
    }
}
