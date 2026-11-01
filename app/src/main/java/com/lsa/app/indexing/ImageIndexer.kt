package com.lsa.app.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.lsa.app.data.db.ImageEntity
import com.lsa.app.data.db.IndexStatus
import com.lsa.app.data.media.MediaImage
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.VectorMath
import com.lsa.app.ml.ocr.OcrEngine
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapLoader {
    /** Decodes with EXIF orientation applied, downscaled so the long side is <= [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
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

class ImageIndexer(
    private val context: Context,
    private val embedder: Embedder,
    private val ocr: OcrEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) : IndexSession {
    override val embeddingModelId: String get() = embedder.modelId

    override suspend fun index(m: MediaImage): ImageEntity {
        val bmp = BitmapLoader.decode(context, m.uri, OCR_MAX_SIDE)
        try {
            val text = ocr.recognize(bmp).trim().take(MAX_OCR_CHARS)
            val imageVec = embedder.embedImage(BitmapLoader.toJpeg(bmp, EMBED_MAX_SIDE))
            val ocrVec = if (text.isNotBlank()) embedder.embedDocument(text) else null
            val loc = readLocation(m.uri)
            return base(m).copy(
                latitude = loc?.first, longitude = loc?.second,
                ocrText = text, ocrModelId = ocr.modelId,
                imageEmb = VectorMath.toBytes(imageVec), ocrEmb = ocrVec?.let(VectorMath::toBytes),
                embeddingDim = imageVec.size,
                status = IndexStatus.DONE, error = null,
            )
        } finally {
            bmp.recycle()
        }
    }

    override fun failed(m: MediaImage, t: Throwable): ImageEntity =
        base(m).copy(status = IndexStatus.FAILED, error = t.message ?: t.javaClass.simpleName)

    private fun base(m: MediaImage) = ImageEntity(
        mediaId = m.mediaId, uri = m.uri.toString(), relativePath = m.relativePath,
        displayName = m.displayName, mimeType = m.mimeType, sizeBytes = m.sizeBytes,
        width = m.width, height = m.height, dateTaken = m.dateTaken, dateModified = m.dateModified,
        latitude = null, longitude = null, ocrText = null, ocrModelId = null,
        imageEmb = null, ocrEmb = null, embeddingModelId = embedder.modelId, embeddingDim = 0,
        indexedAt = clock(), status = IndexStatus.FAILED, error = null,
    )

    private fun readLocation(uri: Uri): Pair<Double, Double>? = try {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val source = if (granted) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(source)?.use { s ->
            ExifInterface(s).latLong?.let { it[0] to it[1] }
        }
    } catch (_: Exception) { null }

    companion object {
        const val OCR_MAX_SIDE = 1600
        const val EMBED_MAX_SIDE = 1024
        const val MAX_OCR_CHARS = 6000
    }
}
