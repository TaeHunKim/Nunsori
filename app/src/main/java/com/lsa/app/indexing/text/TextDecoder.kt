package com.lsa.app.indexing.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Turns file bytes into text, coping with the encodings found on Korean phones and PCs. */
object TextDecoder {
    private val cp949: Charset? = runCatching { Charset.forName("x-windows-949") }.getOrNull()

    /** Returns null for binary data. */
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        if (looksBinary(bytes)) return null
        // BOMs
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        strict(bytes, Charsets.UTF_8)?.let { return it }
        cp949?.let { cs -> strict(bytes, cs)?.let { return it } }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    /** UTF-16 text contains NUL bytes too, so only treat NULs as binary when there is no UTF-16 BOM. */
    private fun looksBinary(b: ByteArray): Boolean {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) return false
        val n = minOf(b.size, 8192)
        var nul = 0
        for (i in 0 until n) if (b[i] == 0.toByte()) nul++
        return nul > 0
    }
}
