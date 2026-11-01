package com.lsa.app.indexing.docs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextTest {
    @Test fun normalEnglishTextIsUsable() = assertTrue(PdfText.usable("The quarterly report shows revenue grew by 12% compared to last year."))
    @Test fun koreanTextIsUsable() = assertTrue(PdfText.usable("2026년 4분기 매출은 전년 대비 12% 증가했습니다. 자세한 내용은 아래를 참고하세요."))
    @Test fun emptyAndTinyTextTriggersOcr() { assertFalse(PdfText.usable("")); assertFalse(PdfText.usable("  p.3  ")) }
    @Test fun replacementCharactersMeanGarbage() = assertFalse(PdfText.usable("���abc����defg����"))
    @Test fun privateUseGlyphsMeanGarbage() = assertFalse(PdfText.usable(" "))
    @Test fun latin1AccentSoupFromBrokenFontsIsRejected() = assertFalse(PdfText.usable("ÃÂÅÄÃÂÅÄ ÉÈÊËÉÈÊË ÕÔÖÒÕÔÖÒ ÜÛÚÙÜÛÚÙ ÝÿÞ"))
    @Test fun frenchTextWithAccentsIsStillUsable() = assertTrue(PdfText.usable("Le résumé décrit les étapes nécessaires à la réalisation du projet."))
    @Test fun numbersAndPunctuationHeavyTableTextIsUsable() = assertTrue(PdfText.usable("Q1 | 1,200 | 3.5% | 2026-01-31 | (12) | 4,500.00 | 7/8"))
}
