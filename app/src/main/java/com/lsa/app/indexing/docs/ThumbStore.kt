package com.lsa.app.indexing.docs

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Small JPEG previews (PDF pages, slide cards, video frames) kept in the app's private storage. */
class ThumbStore(context: Context) {
    val dir: File = File(context.filesDir, "thumbs")

    /** Saves [bitmap] scaled to [maxSide] and returns the path relative to [dir] (what segments store). */
    fun save(sourceKey: String, name: String, bitmap: Bitmap, maxSide: Int = THUMB_SIDE): String {
        val rel = "${keyDir(sourceKey)}/$name.jpg"
        val f = File(dir, rel).also { it.parentFile!!.mkdirs() }
        val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        if (scaled !== bitmap) scaled.recycle()
        return rel
    }

    fun file(rel: String) = File(dir, rel)

    /** Deletes every preview not in [keep] (files of items that no longer exist). */
    fun cleanup(keep: Set<String>): Int {
        var removed = 0
        dir.walkBottomUp().forEach { f ->
            if (f.isFile && f.relativeTo(dir).path !in keep) { if (f.delete()) removed++ }
            else if (f.isDirectory && f != dir && f.list().isNullOrEmpty()) f.delete()
        }
        return removed
    }

    fun clear() { dir.deleteRecursively() }

    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun keyDir(sourceKey: String): String =
        MessageDigest.getInstance("SHA-1").digest(sourceKey.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    companion object { const val THUMB_SIDE = 360 }
}
