package com.lsa.app.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.ml.ocr.OcrEngine
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapLoader {
    /** Decodes with EXIF orientation applied, downscaled so the long side is <= [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        return try {
            decodeRaster(context, uri, maxSide)
        } catch (e: ImageDecoder.DecodeException) {
            // SVG is not a raster format: the platform decoder rejects it ("Failed to create image decoder")
            decodeSvg(context, uri, maxSide) ?: throw e
        }
    }

    private fun decodeRaster(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxSide.toFloat() / max(w, h)
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
    }

    /** Renders an SVG on a white background, long side [maxSide]; null if the file is not a (valid) SVG. */
    fun decodeSvg(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val svg = context.contentResolver.openInputStream(uri)?.use { SVG.getFromInputStream(it) }
        if (svg == null) null else {
            // size from the document; fall back to the viewBox, then to a square
            val box = svg.documentViewBox
            val w0 = svg.documentWidth.takeIf { it > 0 } ?: box?.width()?.takeIf { it > 0 } ?: maxSide.toFloat()
            val h0 = svg.documentHeight.takeIf { it > 0 } ?: box?.height()?.takeIf { it > 0 } ?: maxSide.toFloat()
            val scale = maxSide / max(w0, h0)
            val w = (w0 * scale).roundToInt().coerceIn(1, maxSide)
            val h = (h0 * scale).roundToInt().coerceIn(1, maxSide)
            svg.setDocumentWidth(w.toFloat()); svg.setDocumentHeight(h.toFloat())
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                svg.renderToCanvas(canvas)
            }
        }
    } catch (e: SVGParseException) {
        Log.w("LSA.Image", "not a valid SVG: $uri", e); null
    }

    fun toJpeg(bitmap: Bitmap, maxSide: Int, quality: Int = 90): ByteArray {
        val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1), true,
            )
        } else bitmap
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            .toByteArray().also { if (scaled !== bitmap) scaled.recycle() }
    }
}

/** Image = pixels (embedded as a picture) + the text printed on it (OCR, embedded as text). */
class ImageExtractor(
    private val context: Context,
    private val embedder: Embedder,
    private val ocr: OcrEngine,
) {
    suspend fun extract(entry: SourceEntry, sink: ItemSink) {
        val bmp = BitmapLoader.decode(context, entry.uri, OCR_MAX_SIDE)
        try {
            val text = ocr.recognize(bmp).trim().take(MAX_OCR_CHARS)
            val imageVec = embedder.embedImage(BitmapLoader.toJpeg(bmp, EMBED_MAX_SIDE))
            // Only the head of the text is embedded (1024-token limit); all of it stays searchable by keyword.
            val ocrVec = if (text.isBlank()) null else try {
                embedder.embedDocument(text.take(OCR_EMBED_CHARS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OCR text of ${entry.uri} could not be embedded; keeping the image vector only", e)
                null
            }
            val loc = readLocation(entry.uri)
            sink.meta(latitude = loc?.first, longitude = loc?.second)
            val segs = ArrayList<NewSegment>(2)
            segs += NewSegment(SegmentType.IMAGE, 0, embedding = Quant.encode(imageVec))
            if (ocrVec != null) segs += NewSegment(SegmentType.OCR_TEXT, 1, text = text, embedding = Quant.encode(ocrVec))
            sink.segments(segs, progress = 1)
        } finally {
            bmp.recycle()
        }
    }

    private fun readLocation(uri: Uri): Pair<Double, Double>? = try {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val source = if (granted) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(source)?.use { s ->
            ExifInterface(s).latLong?.let { it[0] to it[1] }
        }.also { if (it == null) Log.d(TAG, "no GPS position in $uri (permission granted: $granted)") }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the location of $uri", e)
        null
    }

    companion object {
        private const val TAG = "LSA.Image"
        const val OCR_MAX_SIDE = 1600
        const val EMBED_MAX_SIDE = 1024
        const val MAX_OCR_CHARS = 6000
        const val OCR_EMBED_CHARS = 1500
    }
}
