package com.lsa.app.indexing.text

/** A piece of a document, with the heading/title context it appeared under. */
data class Chunk(
    val text: String,
    val title: String? = null,
    /** For timed text (subtitles): position of the first/last cue. */
    val startMs: Long? = null,
    val endMs: Long? = null,
)

data class ChunkerConfig(
    val targetChars: Int = 800,
    val maxChars: Int = 1000,
    val minChars: Int = 200,
    val overlapChars: Int = 120,
)

/**
 * Splits plain text into overlapping chunks without breaking lines or words:
 * paragraphs (blank lines) first, then single lines, then sentences, then spaces, and only as a last resort
 * (long runs without any break) at the size limit.
 */
class TextChunker(private val cfg: ChunkerConfig = ChunkerConfig()) {

    fun chunk(text: String, title: String? = null): List<Chunk> =
        pack(text, units(text), cfg).map { Chunk(it, title) }

    /** A span of the original text that is never cut. */
    internal data class Span(val start: Int, val end: Int)

    internal fun units(text: String): List<Span> {
        val out = ArrayList<Span>()
        for (p in paragraphs(text)) {
            if (p.end - p.start <= cfg.maxChars) out += p else splitLong(text, p, out)
        }
        return out
    }

    private fun paragraphs(text: String): List<Span> {
        val out = ArrayList<Span>()
        var start = 0
        for (m in BLANK_LINE.findAll(text)) {
            trimmed(text, start, m.range.first)?.let { out += it }
            start = m.range.last + 1
        }
        trimmed(text, start, text.length)?.let { out += it }
        return out
    }

    private fun trimmed(text: String, from: Int, to: Int): Span? {
        var s = from; var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s < e) Span(s, e) else null
    }

    /** Splits an over-long paragraph by lines, then sentences, then words, then hard. */
    private fun splitLong(text: String, p: Span, out: MutableList<Span>) {
        for (line in splitBy(text, p, LINE_BREAK)) {
            if (line.end - line.start <= cfg.maxChars) { out += line; continue }
            for (sentence in splitBy(text, line, SENTENCE_END)) {
                if (sentence.end - sentence.start <= cfg.maxChars) { out += sentence; continue }
                splitWords(text, sentence, out)
            }
        }
    }

    private fun splitBy(text: String, p: Span, regex: Regex): List<Span> {
        val out = ArrayList<Span>()
        var start = p.start
        for (m in regex.findAll(text.substring(p.start, p.end))) {
            val cut = p.start + m.range.last + 1
            trimmed(text, start, cut)?.let { out += it }
            start = cut
        }
        trimmed(text, start, p.end)?.let { out += it }
        return out
    }

    private fun splitWords(text: String, p: Span, out: MutableList<Span>) {
        var start = p.start
        while (start < p.end) {
            var end = minOf(start + cfg.targetChars, p.end)
            if (end < p.end) {
                // prefer to cut after whitespace or CJK punctuation inside the last quarter of the window
                val floor = start + (end - start) * 3 / 4
                var cut = -1
                for (i in end downTo floor + 1) {
                    if (text[i - 1].isWhitespace() || text[i - 1] in "，、。！？,;") { cut = i; break }
                }
                if (cut > 0) end = cut
            }
            trimmed(text, start, end)?.let { out += it }
            start = end
        }
    }

    companion object {
        private val BLANK_LINE = Regex("\\n[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n)*")
        private val LINE_BREAK = Regex("\\n")
        private val SENTENCE_END = Regex("[.!?。！？]+[\"')\\]”’]?\\s+")

        /**
         * Greedily packs [units] into chunks of about `targetChars`. Each following chunk starts with the trailing
         * units of the previous one (up to `overlapChars`) but always contains at least one new unit.
         */
        internal fun pack(text: String, units: List<Span>, cfg: ChunkerConfig): List<String> {
            if (units.isEmpty()) return emptyList()
            val chunks = ArrayList<IntRange>() // unit index ranges
            var first = 0
            var i = 0
            while (i < units.size) {
                // overlap must never push a chunk past the hard limit
                while (first < i && units[i].end - units[first].start > cfg.maxChars) first++
                var end = i
                while (end + 1 < units.size && units[end + 1].end - units[first].start <= cfg.targetChars) end++
                if (end < first) end = first
                // a lone small tail is merged into this chunk when it still fits
                if (end + 1 == units.size - 1 && units[end + 1].end - units[first].start <= cfg.maxChars &&
                    units[end + 1].end - units[end + 1].start < cfg.minChars
                ) end++
                chunks += first..end
                if (end >= units.size - 1) break
                // overlap: walk back over trailing units while they fit into overlapChars
                var back = end
                var len = 0
                while (back > first && len + (units[back].end - units[back].start) <= cfg.overlapChars) {
                    len += units[back].end - units[back].start
                    back--
                }
                first = if (back < end) back + 1 else end + 1
                i = end + 1
            }
            return chunks.map { text.substring(units[it.first].start, units[it.last].end) }
        }
    }
}
