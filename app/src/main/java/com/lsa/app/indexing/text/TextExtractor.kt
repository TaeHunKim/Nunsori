package com.lsa.app.indexing.text

import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import java.io.File

/** txt / md / html / xml / csv / subtitles ...: decode, chunk with overlap, embed every chunk. */
class TextExtractor(private val embedder: Embedder) {
    suspend fun extract(entry: SourceEntry, resume: Long, sink: ItemSink) {
        val file = File(entry.uri.path ?: throw SkipItem("no path"))
        if (!file.exists()) throw SkipItem("file is gone")
        val bytes = file.inputStream().use { readUpTo(it, MAX_BYTES) }
        val text = TextDecoder.decode(bytes) ?: throw SkipItem("binary file")
        val doc = TextRouting.chunk(file.extension, text, entry.displayName)
        if (doc.chunks.isEmpty()) throw SkipItem("empty")
        sink.meta(title = doc.title)

        val batch = ArrayList<NewSegment>(BATCH)
        for ((i, c) in doc.chunks.withIndex().drop(resume.toInt()).take(MAX_CHUNKS)) {
            batch += NewSegment(
                SegmentType.TEXT_CHUNK, i, startMs = c.startMs, endMs = c.endMs, text = c.text,
                embedding = Quant.encode(embedder.embedDocument(c.text, c.title ?: doc.title)),
            )
            if (batch.size >= BATCH) { sink.segments(batch.toList(), progress = (i + 1).toLong()); batch.clear() }
        }
        if (batch.isNotEmpty()) sink.segments(batch.toList(), progress = (batch.last().ordinal + 1).toLong())
    }


    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        const val MAX_CHUNKS = 20_000
        const val BATCH = 8
    }
}

/** Up to [max] bytes of [input] (InputStream.readNBytes needs Android 13). */
internal fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
