package com.lsa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.SegmentType
import com.lsa.app.indexing.SkipItem
import com.lsa.app.indexing.docs.OfficeExtractor
import com.lsa.app.indexing.docs.PdfExtractor
import com.lsa.app.indexing.docs.TextCard
import com.lsa.app.indexing.docs.ThumbStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocExtractorsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var thumbs: ThumbStore
    private val embedder = FakeEmbedder()
    /** OCR runs in OndolSaem; here it only has to be called for the scanned page (its result is what we check for). */
    private val ocr = object : com.lsa.app.ml.ocr.OcrEngine {
        override val modelId = "fake-ocr"
        override suspend fun recognize(bitmap: android.graphics.Bitmap) = "Budget table 20481"
    }

    @Before fun setUp() {
        dir = File(ctx.cacheDir, "docs-test").also { it.deleteRecursively(); it.mkdirs() }
        thumbs = ThumbStore(ctx).also { it.clear() }
    }
    @After fun tearDown() { dir.deleteRecursively(); thumbs.clear() }

    /** Page 1: real vector text. Page 2: the same kind of text but pasted in as a bitmap (a "scan"). */
    private fun samplePdf(): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 28f; color = Color.BLACK; isAntiAlias = true }
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        p1.canvas.drawText("Annual budget report for the marketing department", 30f, 100f, paint)
        p1.canvas.drawText("Revenue grew by twelve percent this year", 30f, 150f, paint)
        doc.finishPage(p1)
        val scan = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(Color.WHITE)
            Canvas(b).drawText("Invoice number 20481 total due", 60f, 200f, Paint().apply { textSize = 56f; color = Color.BLACK; isAntiAlias = true })
        }
        val p2 = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 2).create())
        p2.canvas.drawBitmap(scan, null, android.graphics.Rect(0, 0, 595, 842), null)
        doc.finishPage(p2)
        return File(dir, "sample.pdf").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    @Test fun pdfPagesBecomeTextChunksPageImagesAndThumbnails() = runBlocking {
        val f = samplePdf()
        val sink = CollectingSink()
        PdfExtractor(ctx, embedder, ocr, thumbs).extract(fileEntry(f, ItemKind.DOC), 0, sink)
        assertEquals(2, sink.pageCount)
        val text = sink.segments.filter { it.type == SegmentType.TEXT_CHUNK }
        assertTrue("page 1 text (read from the PDF or via OCR): ${text.map { it.text }}", text.any { it.page == 1 && it.text!!.contains("budget", true) })
        assertTrue("page 2 is a scan and must come from OCR: ${text.map { it.text }}", text.any { it.page == 2 && it.text!!.contains("20481") })
        val images = sink.segments.filter { it.type == SegmentType.PAGE_IMAGE }
        assertEquals(listOf(1, 2), images.map { it.page })
        for (i in images) assertTrue("thumbnail exists", thumbs.file(i.thumbPath!!).length() > 500)
        assertTrue(sink.segments.all { it.embedding != null })
        assertEquals(2L, sink.lastProgress)
        assertEquals("one commit per page", 2, sink.batches)
    }

    @Test fun pdfResumesFromTheCheckpoint() = runBlocking {
        val sink = CollectingSink()
        PdfExtractor(ctx, embedder, ocr, thumbs).extract(fileEntry(samplePdf(), ItemKind.DOC), 1, sink)
        assertTrue("only page 2 is processed", sink.segments.all { it.page == 2 })
        assertTrue(sink.segments.isNotEmpty())
    }

    @Test fun brokenPdfIsSkippedNotCrashed() = runBlocking {
        val f = File(dir, "broken.pdf").also { it.writeText("this is not a pdf") }
        try { PdfExtractor(ctx, embedder, ocr, thumbs).extract(fileEntry(f, ItemKind.DOC), 0, CollectingSink()); fail("expected SkipItem") }
        catch (e: SkipItem) { assertTrue(e.reason, e.reason.isNotEmpty()) }
    }

    private fun docx(name: String, body: String): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>".toByteArray())
            z.closeEntry()
        }
        return f
    }

    @Test fun docxBecomesChunksPlusATextCard() = runBlocking {
        val body = "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Project Plan</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>The launch is scheduled for December and requires design approval.</w:t></w:r></w:p>"
        val sink = CollectingSink()
        OfficeExtractor(embedder, thumbs).extract(fileEntry(docx("plan.docx", body), ItemKind.DOC), 0, sink)
        assertEquals("plan.docx", sink.title)
        assertTrue(sink.segments.any { it.type == SegmentType.TEXT_CHUNK && it.text!!.contains("design approval") })
        val card = sink.segments.single { it.type == SegmentType.CARD_IMAGE }
        assertTrue(thumbs.file(card.thumbPath!!).length() > 500)
        assertTrue("headings give the chunk its title", embedder.documents.any { it.second == "Project Plan" })
    }

    @Test fun officeFileThatIsNotAZipIsSkipped() = runBlocking {
        val f = File(dir, "fake.docx").also { it.writeText("plain text pretending to be docx") }
        try { OfficeExtractor(embedder, thumbs).extract(fileEntry(f, ItemKind.DOC), 0, CollectingSink()); fail() } catch (e: SkipItem) { /* ok */ }
    }

    @Test fun textCardRendersTitleAndBody() {
        val bmp = TextCard.render("Quarterly results", "Revenue up\nCosts down\n".repeat(5), "deck.pptx", TextCard.Style.SLIDE)
        assertEquals(800, bmp.width); assertEquals(450, bmp.height)
        var nonWhite = 0
        for (x in 0 until bmp.width step 4) for (y in 0 until bmp.height step 4) if (bmp.getPixel(x, y) != Color.WHITE) nonWhite++
        assertTrue("something was drawn", nonWhite > 200)
        val portrait = TextCard.render("T", "b", "a.docx", TextCard.Style.DOCUMENT)
        assertTrue(portrait.height > portrait.width)
    }

    @Test fun thumbStoreSavesScalesAndCleansUp() {
        val big = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }
        val a = thumbs.save("fs:/a.pdf", "p1", big); val b = thumbs.save("fs:/a.pdf", "p2", big); val c = thumbs.save("fs:/b.pdf", "p1", big)
        val decoded = android.graphics.BitmapFactory.decodeFile(thumbs.file(a).path)
        assertEquals(360, maxOf(decoded.width, decoded.height))
        assertTrue(thumbs.sizeBytes() > 0)
        assertEquals("two removed", 2, thumbs.cleanup(setOf(a)))
        assertTrue(thumbs.file(a).exists()); assertFalse(thumbs.file(b).exists()); assertFalse(thumbs.file(c).exists())
        assertNotNull(thumbs.dir.list()); assertEquals("empty folders are removed", 1, thumbs.dir.list()!!.size)
        thumbs.clear(); assertEquals(0L, thumbs.sizeBytes())
    }
}
