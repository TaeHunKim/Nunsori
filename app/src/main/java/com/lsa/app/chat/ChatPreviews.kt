package com.lsa.app.chat

/** A file a search/read tool call returned in one turn. */
data class PreviewHit(val itemId: Long, val name: String, val isImage: Boolean)

object ChatPreviews {
    const val MAX = 6
    private const val FALLBACK_IMAGES = 4

    /**
     * Files to preview under an answer: those whose file name the answer mentions (case-insensitive).
     * If it names none, the first few images of the turn's results (an image search whose answer just describes them).
     */
    fun mentioned(answer: String, hits: List<PreviewHit>): List<PreviewHit> {
        val unique = hits.distinctBy { it.itemId }
        val named = unique.filter { it.name.isNotBlank() && answer.contains(it.name, ignoreCase = true) }
        if (named.isNotEmpty()) return named.take(MAX)
        return unique.filter { it.isImage }.take(FALLBACK_IMAGES)
    }
}
