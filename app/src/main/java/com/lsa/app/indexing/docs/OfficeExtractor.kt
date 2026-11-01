package com.lsa.app.indexing.docs

import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.indexing.text.Chunk
import com.lsa.app.indexing.text.MarkdownChunker
import com.lsa.app.indexing.text.TextChunker
import com.lsa.app.indexing.text.TextFormats
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * docx / pptx / xlsx / odt / odp / ods: text per slide, section or sheet. The phone cannot render these, so each unit
 * also gets a generated text card that serves as its preview and (lightly weighted) image embedding.
 */
class OfficeExtractor(private val embedder: Embedder, private val thumbs: ThumbStore) {
    suspend fun extract(entry: SourceEntry, resume: Long, sink: ItemSink) {
        val file = File(entry.uri.path ?: throw SkipItem("no path"))
        if (!file.exists()) throw SkipItem("file is gone")
        val ext = file.extension.lowercase()
        val doc = try {
            ZipSource.fromFile(file).use { OfficeParsers.parse(ext, it) }
        } catch (e: java.util.zip.ZipException) { throw SkipItem("not a valid $ext file") }
            ?: throw SkipItem("unsupported format")
        if (doc.units.isEmpty()) throw SkipItem("no text")
        val title = doc.title ?: entry.displayName
        sink.meta(title = title, pageCount = doc.units.size)

        for ((i, unit) in doc.units.withIndex().drop(resume.toInt())) {
            currentCoroutineContext().ensureActive()
            val segs = ArrayList<NewSegment>()
            val unitTitle = unit.label ?: title
            chunksOf(unit, unitTitle).take(MAX_CHUNKS_PER_UNIT).forEachIndexed { k, c ->
                segs += NewSegment(
                    SegmentType.TEXT_CHUNK, i * 1000 + k, page = unit.page, text = c.text,
                    embedding = Quant.encode(embedder.embedDocument(c.text, c.title ?: unitTitle)),
                )
            }
            val card = TextCard.render(unitTitle, unit.text.take(1200), entry.displayName, doc.style)
            try {
                val thumb = thumbs.save(entry.sourceKey, "u${i + 1}", card)
                segs += NewSegment(
                    SegmentType.CARD_IMAGE, i * 1000 + 900, page = unit.page,
                    embedding = Quant.encode(embedder.embedImage(BitmapLoader.toJpeg(card, 1024))), thumbPath = thumb,
                )
            } finally { card.recycle() }
            sink.segments(segs, progress = (i + 1).toLong())
        }
    }

    private fun chunksOf(unit: OfficeUnit, title: String): List<Chunk> = when {
        unit.rows != null -> TextFormats.tableChunks(unit.rows, prefix = "Sheet: ${unit.label ?: ""}".trim(), title = title)
        unit.markdown -> MarkdownChunker().chunk(unit.text, title).chunks
        else -> TextChunker().chunk(unit.text, title)
    }


    companion object { const val MAX_CHUNKS_PER_UNIT = 300 }
}
