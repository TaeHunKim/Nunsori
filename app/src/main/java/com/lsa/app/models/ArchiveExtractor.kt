package com.lsa.app.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/** Unpacks only the wanted files of a `.tar.bz2` model archive. */
object ArchiveExtractor {
    /**
     * @param wanted entry name (or suffix such as `model.int8.onnx`) -> file name inside [target]
     * @throws IOException when an expected file is missing from the archive
     */
    fun extract(archive: File, target: File, wanted: Map<String, String>) {
        target.mkdirs()
        val found = HashSet<String>()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                val local = wanted.entries.firstOrNull { (suffix, _) -> e.name == suffix || e.name.endsWith("/$suffix") }?.value ?: continue
                val part = File(target, "$local.part")
                part.outputStream().use { tar.copyTo(it) }
                val dest = File(target, local)
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("cannot write $dest")
                found += local
            }
        }
        val missing = wanted.values.toSet() - found
        if (missing.isNotEmpty()) throw IOException("archive lacks $missing")
    }
}
