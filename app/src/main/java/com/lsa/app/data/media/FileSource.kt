package com.lsa.app.data.media

import android.net.Uri
import android.webkit.MimeTypeMap
import com.lsa.app.data.db.ItemKind
import com.lsa.app.indexing.text.TextRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileTypes {
    /** Documents handled by dedicated extractors (added with Phase 3). */
    val DOCUMENTS = setOf("pdf", "docx", "pptx", "xlsx", "odt", "odp", "ods")

    fun kindOf(ext: String): ItemKind? = when (ext.lowercase()) {
        in TextRouting.ALL -> ItemKind.TEXT
        in DOCUMENTS -> ItemKind.DOC
        else -> null
    }
}

/**
 * Plain files below [root] (the primary shared storage). Needs "all files access" on Android 11+, which the
 * caller reports through [allowed]; without it nothing is listed.
 */
class FileSource(private val root: File, private val allowed: () -> Boolean = { true }) : ItemSource {
    override suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>): List<SourceEntry> = withContext(Dispatchers.IO) {
        if (!allowed() || (ItemKind.TEXT !in kinds && ItemKind.DOC !in kinds)) return@withContext emptyList()
        val out = LinkedHashMap<String, SourceEntry>()
        val visited = HashSet<String>()
        for (f in folders) walk(File(root, f.relativePath), f.recursive, kinds, out, visited)
        out.values.toList()
    }

    private fun walk(dir: File, recursive: Boolean, kinds: Set<ItemKind>, out: MutableMap<String, SourceEntry>, visited: MutableSet<String>) {
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return // symlink loops / overlapping selections
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) {
                if (recursive && !(dir == root && c.name == "Android")) walk(c, true, kinds, out, visited)
            } else {
                val kind = FileTypes.kindOf(c.extension) ?: continue
                if (kind !in kinds || out.size >= MAX_FILES) continue
                out["fs:${c.absolutePath}"] = SourceEntry(
                    sourceKey = "fs:${c.absolutePath}", kind = kind, uri = Uri.fromFile(c), folderKey = folderKey(c),
                    displayName = c.name, mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(c.extension.lowercase()),
                    sizeBytes = c.length(), modifiedAt = c.lastModified(),
                )
            }
        }
    }

    private fun folderKey(file: File): String {
        val rel = file.parentFile!!.relativeTo(root).path
        return if (rel.isEmpty() || rel == ".") "" else "$rel/"
    }

    private companion object { const val MAX_FILES = 200_000 }
}

/** Merges several sources; each decides for itself which kinds it can provide. */
class CompositeSource(private val sources: List<ItemSource>) : ItemSource {
    override suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>): List<SourceEntry> =
        sources.flatMap { it.list(folders, kinds) }
}
