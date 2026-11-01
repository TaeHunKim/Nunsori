package com.lsa.app.chat

import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.ml.llm.TurnRole

/** Keeps the conversation history inside the part of the model's context window reserved for it. */
object ContextBudget {
    /** Rough token count for mixed Korean and English text. */
    fun estimateTokens(text: String): Int = (text.length + 2) / 3

    /** Share of the context window the replayed history may use; the rest is for system prompt, tools, files and the answer. */
    const val HISTORY_SHARE = 0.6

    class Trimmed(val turns: List<ChatTurn>, val dropped: Int)

    /** The newest turns that fit in [budgetTokens], starting with a user turn. */
    fun trim(history: List<ChatTurn>, budgetTokens: Int): Trimmed {
        var used = 0
        var keep = 0
        for (t in history.asReversed()) {
            val cost = estimateTokens(t.text) + 8
            if (used + cost > budgetTokens) break
            used += cost; keep++
        }
        var turns = history.takeLast(keep)
        while (turns.isNotEmpty() && turns.first().role != TurnRole.USER) turns = turns.drop(1)
        return Trimmed(turns, history.size - turns.size)
    }

    fun clip(text: String, maxChars: Int): String = if (text.length <= maxChars) text else text.take(maxChars) + "…"
}
