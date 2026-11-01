package com.lsa.app.indexing.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Office documents cannot be rendered on the phone, so each slide/section/sheet gets a card with its title and
 * first lines. It serves as preview and as the (low weight) image of that unit.
 */
object TextCard {
    enum class Style(val accent: Int, val landscape: Boolean) {
        SLIDE(Color.rgb(0xD2, 0x47, 0x26), true),
        DOCUMENT(Color.rgb(0x2B, 0x57, 0x9A), false),
        SHEET(Color.rgb(0x21, 0x73, 0x46), false),
        OPEN_DOCUMENT(Color.rgb(0x00, 0x80, 0x80), false),
    }

    /** Headings are stored as `# Title`; on a card they should just read as text. */
    fun stripMarkdown(text: String): String = text.lines().joinToString("\n") { it.replace(HEADING, "") }

    private val HEADING = Regex("^#{1,6}\\s+")

    fun render(title: String, body: String, fileName: String, style: Style): Bitmap {
        val w = if (style.landscape) 800 else 600
        val h = if (style.landscape) 450 else 800
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val band = Paint().apply { color = style.accent }
        c.drawRect(0f, 0f, w.toFloat(), 14f, band)

        val pad = 36f
        val width = (w - 2 * pad).toInt()
        var y = 40f
        fun draw(text: String, size: Float, bold: Boolean, color: Int, maxLines: Int) {
            if (text.isBlank()) return
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; isFakeBoldText = bold }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save(); c.translate(pad, y); layout.draw(c); c.restore()
            y += layout.height + size * 0.6f
        }
        draw(fileName, 20f, false, Color.GRAY, 1)
        draw(title.ifBlank { fileName }, 40f, true, Color.rgb(0x20, 0x20, 0x20), 3)
        val remaining = ((h - y - 24f) / 30f).toInt().coerceAtLeast(1)
        draw(stripMarkdown(body), 24f, false, Color.rgb(0x40, 0x40, 0x40), remaining)
        return bmp
    }
}
