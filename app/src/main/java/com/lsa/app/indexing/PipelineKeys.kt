package com.lsa.app.indexing

import com.lsa.app.data.db.ItemKind

/** Everything that changes what an extractor produces. A different key means the item must be reprocessed. */
object PipelineKeys {
    /** Bump when chunking / extraction logic changes in a way that invalidates stored segments. */
    const val IMAGE_VERSION = 1
    const val TEXT_VERSION = 1
    const val DOC_VERSION = 1
    const val AUDIO_VERSION = 1
    const val VIDEO_VERSION = 3

    /** [asrModelId] is "none" while no speech recognition model is installed. */
    fun of(kind: ItemKind, embeddingModelId: String, ocrModelId: String, asrModelId: String = "none", videoDetail: String = "MEDIUM", videoSpeech: Boolean = true): String = when (kind) {
        ItemKind.IMAGE -> "$embeddingModelId|$ocrModelId|c$IMAGE_VERSION"
        ItemKind.TEXT -> "$embeddingModelId|t$TEXT_VERSION"
        ItemKind.DOC -> "$embeddingModelId|$ocrModelId|d$DOC_VERSION"
        ItemKind.AUDIO -> "$embeddingModelId|$asrModelId|a$AUDIO_VERSION"
        ItemKind.VIDEO -> "$embeddingModelId|v$VIDEO_VERSION|$videoDetail|${if (videoSpeech) asrModelId else "off"}"
        // Other kinds are added together with their extractors.
        else -> "$embeddingModelId|$ocrModelId|c0"
    }
}
