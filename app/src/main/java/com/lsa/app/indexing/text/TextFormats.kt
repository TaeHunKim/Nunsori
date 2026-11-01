package com.lsa.app.indexing.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** Converters from other text formats into something the chunkers understand. */
object TextFormats {
    private val BLOCK = setOf("p", "div", "section", "article", "header", "footer", "ul", "ol", "table", "blockquote", "pre", "main", "nav", "aside", "form")
    private val LINEBREAK = setOf("br", "li", "tr", "dt", "dd")
    private val SKIP = setOf("script", "style", "noscript", "template", "svg", "head")

    /** HTML -> pseudo-markdown (`# Heading` lines) so [MarkdownChunker] can follow the structure. Also returns `<title>`. */
    fun htmlToMarkdown(html: String): Pair<String, String?> {
        val doc = Jsoup.parse(html)
        val title = doc.title().trim().takeIf { it.isNotEmpty() }
        val sb = StringBuilder()
        fun newBlock() { if (sb.isNotEmpty() && !sb.endsWith("\n\n")) { if (!sb.endsWith("\n")) sb.append("\n"); sb.append("\n") } }
        fun newLine() { if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n") }
        fun visit(n: Node) {
            when (n) {
                is TextNode -> { val t = n.wholeText.replace(Regex("\\s+"), " "); if (t.isNotBlank() || (t == " " && sb.isNotEmpty() && !sb.endsWith("\n") && !sb.endsWith(" "))) sb.append(t) }
                is Element -> {
                    val tag = n.normalName()
                    if (tag in SKIP) return
                    val heading = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
                    if (heading) { newBlock(); sb.append("#".repeat(tag[1] - '0')).append(' ') }
                    else if (tag in BLOCK) newBlock() else if (tag in LINEBREAK) newLine()
                    n.childNodes().forEach(::visit)
                    if (heading || tag in BLOCK) newBlock() else if (tag in LINEBREAK) newLine()
                }
            }
        }
        doc.body().childNodes().forEach(::visit)
        return sb.toString().replace(Regex("[ \\t]+\\n"), "\n").trim() to title
    }

    /** Text nodes of an XML document, one per line, so tags and attributes do not pollute the embedding. */
    fun xmlToText(xml: String): String {
        val sb = StringBuilder()
        return try {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) sb.append(t).append('\n')
                }
                ev = p.nextToken()
            }
            sb.toString().trim()
        } catch (e: Exception) {
            // not well-formed: strip tags crudely rather than dropping the file
            xml.replace(Regex("<[^>]+>"), " ").replace(Regex("[ \\t]+"), " ").trim()
        }
    }

    /** Minimal RFC 4180 parser (quotes, doubled quotes, newlines inside quotes). */
    fun parseCsv(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.clear() }
        fun endRow() { endCell(); if (row.any { it.isNotEmpty() }) rows += row; row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endCell()
                !inQuotes && c == '\n' -> endRow()
                !inQuotes && c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Rows grouped by size, each group prefixed with the header row; used for CSV/TSV and spreadsheets. */
    fun tableChunks(rows: List<List<String>>, cfg: ChunkerConfig = ChunkerConfig(), prefix: String? = null, title: String? = null): List<Chunk> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().joinToString(" | ")
        val out = ArrayList<Chunk>()
        var cur = StringBuilder()
        fun start() { cur = StringBuilder(); if (prefix != null) cur.append(prefix).append('\n'); cur.append(header) }
        start()
        val base = cur.length
        for (r in rows.drop(1)) {
            val line = r.joinToString(" | ")
            if (cur.length + line.length + 1 > cfg.targetChars && cur.length > base) { out += Chunk(cur.toString(), title); start() }
            cur.append('\n').append(if (line.length > cfg.maxChars) line.take(cfg.maxChars) else line)
        }
        if (cur.length > base || out.isEmpty()) out += Chunk(cur.toString(), title)
        return out
    }

    private val TIME = Regex("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{1,3})|(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")

    private fun parseTimeMs(s: String): Long? {
        val m = TIME.find(s) ?: return null
        val g = m.groupValues
        return if (g[1].isNotEmpty()) g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].padEnd(3, '0').toLong()
        else g[5].toLong() * 60_000 + g[6].toLong() * 1000 + g[7].padEnd(3, '0').toLong()
    }

    /** SRT / WebVTT cues grouped into chunks that remember where they start and end (ms). */
    fun subtitleChunks(text: String, cfg: ChunkerConfig = ChunkerConfig(), title: String? = null): List<Chunk> {
        data class Cue(val start: Long, val end: Long, val text: String)
        val cues = ArrayList<Cue>()
        for (block in text.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timeIdx = lines.indexOfFirst { "-->" in it }
            if (timeIdx < 0) continue
            val (a, b) = lines[timeIdx].split("-->").let { it[0] to it.getOrElse(1) { "" } }
            val s = parseTimeMs(a) ?: continue
            val e = parseTimeMs(b) ?: s
            val body = lines.drop(timeIdx + 1).joinToString(" ") { it.replace(Regex("<[^>]+>"), "").trim() }.trim()
            if (body.isNotEmpty()) cues += Cue(s, e, body)
        }
        val out = ArrayList<Chunk>()
        var cur = StringBuilder(); var s = 0L; var e = 0L
        for (c in cues) {
            if (cur.isNotEmpty() && cur.length + c.text.length + 1 > cfg.targetChars) { out += Chunk(cur.toString(), title, s, e); cur = StringBuilder() }
            if (cur.isEmpty()) s = c.start else cur.append(' ')
            cur.append(c.text); e = c.end
        }
        if (cur.isNotEmpty()) out += Chunk(cur.toString(), title, s, e)
        return out
    }
}
