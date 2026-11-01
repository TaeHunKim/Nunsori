package com.lsa.app.indexing

enum class AutoIndexMode { MANUAL, IMMEDIATE, SCHEDULED, IDLE }

/** When to unload the embedding / OCR models from memory. */
enum class ResidencyPolicy(val idleMillis: Long?) {
    KEEP(null),
    IDLE_2M(2 * 60_000L),
    IMMEDIATE(0L),
}
