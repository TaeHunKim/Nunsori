package com.lsa.app.indexing.docs

/** Decides whether text pulled out of a PDF page is real text or font-encoding garbage (then OCR is used instead). */
object PdfText {
    fun usable(text: String): Boolean {
        val t = text.trim()
        if (t.length < MIN_CHARS) return false
        var good = 0; var bad = 0; var letters = 0; var plausible = 0
        for (c in t) {
            when {
                c == '�' || c in ''..'' || (c.isISOControl() && !c.isWhitespace()) -> bad++
                c.isLetterOrDigit() || c.isWhitespace() || c in PUNCT -> good++
                else -> Unit
            }
            if (c.isLetter()) {
                letters++
                // ASCII, Hangul, kana and CJK are what real documents here are written in; Latin-1 accents alone are suspicious
                if (c.code < 0x80 || c in '가'..'힣' || c in '぀'..'ヿ' || c in '一'..'鿿') plausible++
            }
        }
        if (bad.toDouble() / t.length > 0.03) return false
        if (good.toDouble() / t.length < 0.85) return false
        return letters == 0 || plausible.toDouble() / letters >= 0.5
    }

    private const val MIN_CHARS = 15
    private const val PUNCT = ".,;:!?-–—()[]{}%/'\"“”‘’…·•*#@&+=<>_\\|~`^$"
}
