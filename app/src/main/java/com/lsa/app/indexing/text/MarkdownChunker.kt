package com.lsa.app.indexing.text

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Block
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Markdown-aware chunking: blocks (paragraph, list, table, code fence, quote) are kept whole, chunks follow the
 * heading structure, and every chunk carries its heading path (`A > B > C`) as title.
 * Anything unexpected falls back to the plain [TextChunker].
 */
class MarkdownChunker(private val cfg: ChunkerConfig = ChunkerConfig()) {
    private val plain = TextChunker(cfg)
    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    class Result(val chunks: List<Chunk>, val title: String?)

    fun chunk(raw: String, fallbackTitle: String? = null): Result {
        val (body, frontTitle) = stripFrontMatter(raw)
        return try {
            val r = chunkParsed(body, frontTitle ?: fallbackTitle)
            if (r.isEmpty() && body.isNotBlank()) Result(plain.chunk(body, frontTitle ?: fallbackTitle), frontTitle)
            else Result(r, frontTitle ?: firstHeading ?: fallbackTitle)
        } catch (e: Exception) {
            Result(plain.chunk(body, frontTitle ?: fallbackTitle), frontTitle)
        }
    }

    private var firstHeading: String? = null

    private class Piece(val text: String, val headingPath: String?, val isHeading: Boolean, val level: Int)

    private fun chunkParsed(body: String, docTitle: String?): List<Chunk> {
        firstHeading = null
        val lines = body.split("\n")
        val doc = parser.parse(body)
        val pieces = ArrayList<Piece>()
        val path = ArrayList<Pair<Int, String>>() // (level, text)
        var node: Node? = doc.firstChild
        while (node != null) {
            val block = node as? Block
            val spans = block?.sourceSpans.orEmpty()
            if (block != null && spans.isNotEmpty()) {
                val from = spans.first().lineIndex
                val to = spans.last().lineIndex
                val text = lines.subList(from, minOf(to + 1, lines.size)).joinToString("\n").trimEnd()
                if (block is Heading) {
                    val t = headingText(block)
                    while (path.isNotEmpty() && path.last().first >= block.level) path.removeAt(path.size - 1)
                    path += block.level to t
                    if (firstHeading == null && block.level == 1) firstHeading = t
                    pieces += Piece(text, null, true, block.level)
                } else if (text.isNotBlank()) {
                    pieces += Piece(text, path.joinToString(" > ") { it.second }.ifEmpty { null }, false, 0)
                }
            }
            node = node.next
        }
        return pack(pieces, docTitle)
    }

    private fun headingText(h: Heading): String {
        val sb = StringBuilder()
        var n: Node? = h.firstChild
        fun walk(x: Node?) { var c = x; while (c != null) { if (c is Text) sb.append(c.literal); walk(c.firstChild); c = c.next } }
        walk(n)
        return sb.toString().trim()
    }

    private fun pack(pieces: List<Piece>, docTitle: String?): List<Chunk> {
        val out = ArrayList<Chunk>()
        val cur = StringBuilder()
        var curPath: String? = null
        var pendingHeadings = ArrayList<String>()

        fun flush() {
            if (cur.isNotBlank()) out += Chunk(cur.toString().trim(), curPath ?: docTitle)
            cur.clear(); curPath = null
        }

        // Sections: a heading of level <= 2 closes the current chunk if it is big enough.
        for (p in pieces) {
            if (p.isHeading) {
                if (p.level <= 2 && cur.length >= cfg.minChars) flush()
                pendingHeadings += p.text
                continue
            }
            val heads = pendingHeadings.joinToString("\n\n")
            pendingHeadings = ArrayList()
            val blockText = (if (heads.isNotEmpty()) "$heads\n\n" else "") + p.text
            for (part in splitBlock(p.text, blockText)) {
                if (cur.isNotEmpty() && cur.length + part.length + 2 > cfg.targetChars) {
                    // carry the tail of the old chunk into the next one as overlap
                    val tail = overlapTail(cur.toString())
                    val path = curPath
                    flush()
                    if (tail.isNotEmpty()) { cur.append(tail).append("\n\n"); curPath = path }
                }
                if (curPath == null) curPath = p.headingPath
                if (cur.isNotEmpty() && !cur.endsWith("\n\n")) cur.append("\n\n")
                cur.append(part)
            }
        }
        if (pendingHeadings.isNotEmpty() && cur.isEmpty()) { // headings only (empty sections)
            cur.append(pendingHeadings.joinToString("\n\n"))
        }
        flush()
        return mergeTinyTail(out)
    }

    private fun mergeTinyTail(chunks: List<Chunk>): List<Chunk> {
        if (chunks.size < 2) return chunks
        val last = chunks.last(); val prev = chunks[chunks.size - 2]
        return if (last.text.length < cfg.minChars && prev.text.length + last.text.length + 2 <= cfg.maxChars) {
            chunks.dropLast(2) + Chunk(prev.text + "\n\n" + last.text, prev.title)
        } else chunks
    }

    private fun overlapTail(text: String): String {
        val paras = text.split("\n\n")
        val last = paras.last()
        return if (last.length in 1..cfg.overlapChars) last else ""
    }

    /** Whole blocks stay whole; only blocks over the hard limit are split (tables by row with header, else by lines). */
    private fun splitBlock(rawBlock: String, withHeadings: String): List<String> {
        if (withHeadings.length <= cfg.maxChars) return listOf(withHeadings)
        val lines = rawBlock.split("\n")
        val isTable = lines.size >= 3 && lines[1].trim().matches(Regex("\\|?[ :\\-|]+\\|?")) && lines[0].contains('|')
        if (isTable) {
            val header = lines[0] + "\n" + lines[1]
            val parts = ArrayList<String>()
            var cur = StringBuilder(header)
            for (row in lines.drop(2)) {
                if (cur.length + row.length + 1 > cfg.targetChars && cur.length > header.length) { parts += cur.toString(); cur = StringBuilder(header) }
                cur.append("\n").append(row)
            }
            if (cur.length > header.length) parts += cur.toString()
            return parts
        }
        return TextChunker(cfg).chunk(withHeadings).map { it.text }
    }

    companion object {
        /** Returns (text without front matter, title from front matter). */
        fun stripFrontMatter(raw: String): Pair<String, String?> {
            if (!raw.startsWith("---")) return raw to null
            val lines = raw.split("\n")
            if (lines[0].trim() != "---") return raw to null
            val close = (1 until minOf(lines.size, 200)).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." } ?: return raw to null
            val title = lines.subList(1, close).firstOrNull { it.trimStart().startsWith("title:") }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
            return lines.drop(close + 1).joinToString("\n") to title
        }
    }
}
