package com.lsa.app.indexing

/** What the last automatic indexing run did, kept so the UI can say why nothing happened. */
data class LastAutoRun(val at: Long, val source: String, val outcome: String, val count: Int = 0) {
    fun encode() = "$at|$source|$outcome|$count"

    companion object {
        fun decode(s: String?): LastAutoRun? {
            val p = s?.split('|') ?: return null
            if (p.size != 4) return null
            return LastAutoRun(p[0].toLongOrNull() ?: return null, p[1], p[2], p[3].toIntOrNull() ?: 0)
        }
    }
}

object AutoOutcome {
    const val INDEXED = "INDEXED"
    const val NOTHING_NEW = "NOTHING_NEW"
    const val HOT = "HOT"
    const val NO_MODEL = "NO_MODEL"
    const val LOAD_FAILED = "LOAD_FAILED"
    const val NO_ACCESS = "NO_ACCESS"
    const val NO_FOLDERS = "NO_FOLDERS"
    const val ERROR = "ERROR"
}
