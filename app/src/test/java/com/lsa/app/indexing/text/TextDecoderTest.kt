package com.lsa.app.indexing.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextDecoderTest {
    @Test fun utf8() = assertEquals("회의록 2026", TextDecoder.decode("회의록 2026".toByteArray(Charsets.UTF_8)))
    @Test fun utf8WithBomIsStripped() =
        assertEquals("한글", TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "한글".toByteArray()))
    @Test fun utf16WithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "안녕 hello".toByteArray(Charsets.UTF_16LE)
        assertEquals("안녕 hello", TextDecoder.decode(bytes))
    }
    @Test fun legacyKoreanCp949IsDetected() {
        val bytes = "안녕하세요 프로젝트 일정".toByteArray(charset("x-windows-949"))
        assertEquals("안녕하세요 프로젝트 일정", TextDecoder.decode(bytes))
    }
    @Test fun binaryDataIsRejected() = assertNull(TextDecoder.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 1, 2, 3)))
    @Test fun emptyFileIsEmptyText() = assertEquals("", TextDecoder.decode(ByteArray(0)))
    @Test fun latin1FallbackNeverThrows() = assertEquals("café", TextDecoder.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
}
