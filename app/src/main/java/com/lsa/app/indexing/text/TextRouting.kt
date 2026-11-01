package com.lsa.app.indexing.text

/** Picks the right chunking strategy for a text-like file. Pure functions, easy to test. */
object TextRouting {
    class Doc(val chunks: List<Chunk>, val title: String?)

    val MARKDOWN = setOf("md", "markdown", "mdown", "mkd")
    val HTML = setOf("html", "htm", "xhtml")
    val XML = setOf("xml", "svg", "rss", "atom", "plist", "xsd", "xsl")
    val CSV = setOf("csv")
    val TSV = setOf("tsv")
    val SUBTITLES = setOf("srt", "vtt")
    val PLAIN = setOf(
        "txt", "text", "log", "ini", "conf", "cfg", "properties", "toml", "yaml", "yml", "json", "jsonl", "tex", "rst", "org",
        "kt", "java", "py", "js", "ts", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bat", "sql", "gradle",
    )

    val ALL: Set<String> = MARKDOWN + HTML + XML + CSV + TSV + SUBTITLES + PLAIN

    fun chunk(ext: String, text: String, fileName: String, cfg: ChunkerConfig = ChunkerConfig()): Doc {
        val e = ext.lowercase()
        return when (e) {
            in MARKDOWN -> {
                val r = MarkdownChunker(cfg).chunk(text, fileName)
                Doc(r.chunks, r.title ?: fileName)
            }
            in HTML -> {
                val (md, title) = TextFormats.htmlToMarkdown(text)
                val r = MarkdownChunker(cfg).chunk(md, title ?: fileName)
                Doc(r.chunks, title ?: r.title ?: fileName)
            }
            in XML -> Doc(TextChunker(cfg).chunk(TextFormats.xmlToText(text), fileName), fileName)
            in CSV -> Doc(TextFormats.tableChunks(TextFormats.parseCsv(text, ','), cfg, title = fileName), fileName)
            in TSV -> Doc(TextFormats.tableChunks(TextFormats.parseCsv(text, '\t'), cfg, title = fileName), fileName)
            in SUBTITLES -> Doc(TextFormats.subtitleChunks(text, cfg, fileName), fileName)
            else -> Doc(TextChunker(cfg).chunk(text, fileName), fileName)
        }
    }
}
