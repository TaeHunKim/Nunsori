package com.lsa.app.chat

import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.ml.llm.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {
    private fun turn(role: TurnRole, chars: Int) = ChatTurn(role, "x".repeat(chars))

    @Test fun keepsTheNewestTurnsThatFit() {
        val history = listOf(turn(TurnRole.USER, 300), turn(TurnRole.ASSISTANT, 300), turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30))
        val all = ContextBudget.trim(history, 10_000)
        assertEquals(4, all.turns.size); assertEquals(0, all.dropped)
        val tight = ContextBudget.trim(history, 60)
        assertEquals("only the last pair fits", 2, tight.turns.size); assertEquals(2, tight.dropped)
        assertEquals(TurnRole.USER, tight.turns.first().role)
    }

    @Test fun neverStartsWithAnAssistantTurn() {
        val history = listOf(turn(TurnRole.USER, 600), turn(TurnRole.ASSISTANT, 30), turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30))
        // Budget fits the last three turns, but the oldest of those is the assistant's reply to a dropped question.
        val t = ContextBudget.trim(history, 70)
        assertEquals(TurnRole.USER, t.turns.first().role)
        assertEquals(2, t.turns.size)
    }

    @Test fun emptyBudgetKeepsNothing() {
        val t = ContextBudget.trim(listOf(turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30)), 0)
        assertTrue(t.turns.isEmpty()); assertEquals(2, t.dropped)
    }

    private fun seg(type: SegmentType, ordinal: Int, text: String?, page: Int? = null, startMs: Long? = null, endMs: Long? = null) =
        SegmentEntity(itemId = 1, type = type, ordinal = ordinal, startMs = startMs, endMs = endMs, page = page, text = text, embedding = null, thumbPath = null)

    @Test fun readsDocumentTextWithPageMarkers() {
        val segs = listOf(
            seg(SegmentType.TEXT_CHUNK, 0, "First page text", page = 1),
            seg(SegmentType.PAGE_IMAGE, 1, null, page = 1),
            seg(SegmentType.TEXT_CHUNK, 2, "Second page text", page = 2),
        )
        val all = ReadFileTool.fullText(segs)
        assertEquals("[p.1]\nFirst page text\n\n[p.2]\nSecond page text", all)
        assertEquals("[p.2]\nSecond page text", ReadFileTool.fullText(segs, page = 2))
    }

    @Test fun readsTranscriptsInATimeRange() {
        val segs = listOf(
            seg(SegmentType.TRANSCRIPT, 0, "hello", startMs = 0, endMs = 5_000),
            seg(SegmentType.TRANSCRIPT, 1, "middle", startMs = 65_000, endMs = 70_000),
            seg(SegmentType.TRANSCRIPT, 2, "end", startMs = 3_700_000, endMs = 3_710_000),
        )
        assertEquals("[01:05] middle", ReadFileTool.fullText(segs, fromMs = 60_000, toMs = 100_000))
        assertTrue(ReadFileTool.fullText(segs).contains("[1:01:40] end"))
    }

    @Test fun imageTextIsReadFromOcrSegments() {
        val segs = listOf(seg(SegmentType.IMAGE, 0, null), seg(SegmentType.OCR_TEXT, 1, "Total 4500 won"))
        assertEquals("Total 4500 won", ReadFileTool.fullText(segs))
    }
}
