package com.lsa.app.search

/**
 * Score fusion helpers.
 *
 * Cosine similarities from different spaces (image<->text vs text<->text) have different, compressed ranges
 * (unrelated OCR text still scores ~0.6), so neither raw scores nor plain rank fusion work. Each signal is judged
 * against its own distribution: it only counts when its best score clearly stands out from the median
 * ([minSpread]), and is then rescaled to 1.0 at the max and 0.0 at [margin] below it.
 */
object Fusion {
    /** Returns only entries with a positive relative score. */
    fun relative(sims: Map<Long, Float>, margin: Float, minSpread: Float, minTop: Float = 0f): Map<Long, Float> {
        if (sims.isEmpty()) return emptyMap()
        val sorted = sims.values.sorted()
        val max = sorted.last()
        if (max < minTop) return emptyMap()
        // With very few documents there is no distribution to compare against.
        if (sorted.size >= MIN_DOCS_FOR_SPREAD && max - sorted[sorted.size / 2] < minSpread) return emptyMap()
        val out = HashMap<Long, Float>()
        for ((id, s) in sims) {
            val r = ((s - (max - margin)) / margin).coerceIn(0f, 1f)
            if (r > 0f) out[id] = r
        }
        return out
    }

    const val MIN_DOCS_FOR_SPREAD = 4
}

data class KeywordMatch(val count: Int, val exact: Boolean)

object KeywordMatcher {
    private val ascii = Regex("^[\\x00-\\x7F]+$")

    /** Single ASCII characters ("a", "I") are noise; single CJK syllables are kept. */
    fun tokens(query: String): List<String> =
        query.lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() && !(it.length == 1 && ascii.matches(it)) }
            .distinct()

    /** ASCII tokens must match whole words; others (Korean etc.) match as substrings. */
    private fun contains(textLower: String, token: String): Boolean =
        if (ascii.matches(token)) Regex("(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])").containsMatchIn(textLower)
        else textLower.contains(token)

    /** Returns null when no token occurs in [text]. */
    fun match(text: String, tokens: List<String>, fullQuery: String): KeywordMatch? {
        if (tokens.isEmpty()) return null
        val t = text.lowercase()
        val count = tokens.count { contains(t, it) }
        if (count == 0) return null
        return KeywordMatch(count, tokens.size == count && t.contains(fullQuery.lowercase().trim()))
    }

    fun snippet(text: String, tokens: List<String>, radius: Int = 40): String {
        val t = text.replace(Regex("\\s+"), " ")
        val lower = t.lowercase()
        val idx = tokens.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + radius * 2).coerceAtMost(t.length)
        return (if (start > 0) "…" else "") + t.substring(start, end) + (if (end < t.length) "…" else "")
    }
}
