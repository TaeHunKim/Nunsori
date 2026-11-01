package com.lsa.app.indexing.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.indexing.SkipItem
import com.lsa.app.indexing.text.TextChunker
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.ml.ocr.OcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * PDF = pages. Every page yields its text (read from the PDF, or OCR when that is missing/garbled, e.g. scans)
 * as chunks, plus the rendered page as an image embedding and a preview thumbnail.
 */
class PdfExtractor(
    private val context: Context,
    private val embedder: Embedder,
    private val ocr: OcrEngine,
    private val thumbs: ThumbStore,
) {
    suspend fun extract(entry: SourceEntry, resume: Long, sink: ItemSink) {
        val file = File(entry.uri.path ?: throw SkipItem("no path"))
        if (!file.exists()) throw SkipItem("file is gone")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (e: SecurityException) {
            pfd.close(); throw SkipItem("password protected")
        } catch (e: IOException) {
            pfd.close(); throw SkipItem("not a readable PDF")
        }
        var text: PDDocument? = null
        try {
            val total = minOf(renderer.pageCount, MAX_PAGES)
            if (total == 0) throw SkipItem("no pages")
            text = openText(file)
            val title = text?.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: entry.displayName
            sink.meta(title = title, pageCount = renderer.pageCount)
            val stripper = PDFTextStripper()
            for (i in resume.toInt() until total) {
                currentCoroutineContext().ensureActive()
                val bmp = render(renderer, i)
                try {
                    val pageText = pageText(text, stripper, i, bmp)
                    val segs = ArrayList<NewSegment>()
                    TextChunker().chunk(pageText).forEachIndexed { k, c ->
                        segs += NewSegment(
                            SegmentType.TEXT_CHUNK, i * 1000 + k, page = i + 1, text = c.text,
                            embedding = Quant.encode(embedSafely(c.text, title)),
                        )
                    }
                    val thumb = thumbs.save(entry.sourceKey, "p${i + 1}", bmp)
                    segs += NewSegment(
                        SegmentType.PAGE_IMAGE, i * 1000 + 900, page = i + 1,
                        embedding = Quant.encode(embedder.embedImage(BitmapLoader.toJpeg(bmp, IMAGE_SIDE))), thumbPath = thumb,
                    )
                    sink.segments(segs, progress = (i + 1).toLong())
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            renderer.close(); runCatching { text?.close() }
        }
    }

    private fun openText(file: File): PDDocument? = try {
        if (!initialised) { PDFBoxResourceLoader.init(context); initialised = true }
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    } catch (e: Exception) { null }

    private suspend fun pageText(doc: PDDocument?, stripper: PDFTextStripper, index: Int, bmp: Bitmap): String {
        if (doc != null) {
            val t = try { stripper.startPage = index + 1; stripper.endPage = index + 1; stripper.getText(doc) } catch (e: Exception) { "" }
            if (PdfText.usable(t)) return t.trim()
        }
        return ocr.recognize(bmp).trim()
    }

    private fun render(renderer: PdfRenderer, index: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = RENDER_WIDTH
        val h = (w.toFloat() * page.height / page.width).roundToInt().coerceIn(1, MAX_HEIGHT)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private suspend fun embedSafely(text: String, title: String?): FloatArray {
        var t = text
        repeat(4) {
            try { return embedder.embedDocument(t, title) } catch (e: Exception) {
                if (e.message?.contains("exceeds", ignoreCase = true) != true) throw e
                t = t.take((t.length * 0.6).toInt()).trimEnd()
            }
        }
        return embedder.embedDocument(t, title)
    }

    companion object {
        const val MAX_PAGES = 500
        const val RENDER_WIDTH = 1200
        const val MAX_HEIGHT = 2400
        const val IMAGE_SIDE = 1024
        @Volatile private var initialised = false
    }
}
