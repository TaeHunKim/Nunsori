package com.lsa.app.indexing

import com.lsa.app.data.db.ItemKind

object KindSupport {
    /** Kinds that have a working extractor; the others are listed in Settings as "coming soon". */
    val implemented: Set<ItemKind> = setOf(ItemKind.IMAGE, ItemKind.TEXT, ItemKind.DOC, ItemKind.AUDIO, ItemKind.VIDEO)
}
