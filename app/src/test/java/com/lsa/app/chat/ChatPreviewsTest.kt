package com.lsa.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPreviewsTest {
    private val a = PreviewHit(1, "IMG_0001.jpg", true)
    private val b = PreviewHit(2, "plan.pdf", false)
    private val c = PreviewHit(3, "cat.png", true)

    @Test fun namedFilesWin() = assertEquals(listOf(b), ChatPreviews.mentioned("See **Plan.PDF** for the schedule.", listOf(a, b, c)))
    @Test fun fallsBackToImages() = assertEquals(listOf(a, c), ChatPreviews.mentioned("Here are the photos I found.", listOf(a, b, c)))
    @Test fun noImagesNoNames() = assertEquals(emptyList<PreviewHit>(), ChatPreviews.mentioned("Nothing named.", listOf(b)))
    @Test fun duplicatesAndCap() {
        val many = (1L..10).map { PreviewHit(it, "f$it.txt", false) } + PreviewHit(1, "f1.txt", false)
        assertEquals(6, ChatPreviews.mentioned(many.joinToString(" ") { it.name }, many).size)
    }
}
