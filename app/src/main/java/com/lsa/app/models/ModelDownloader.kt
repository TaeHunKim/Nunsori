package com.lsa.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class DownloadException(val kind: Kind, message: String? = null) : IOException(message) {
    enum class Kind { AUTH, NOT_FOUND, HTTP, NETWORK }
}

/** Resumable HTTP downloader (`.part` file + Range). */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun download(
        url: String,
        target: File,
        token: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val req = Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw DownloadException(DownloadException.Kind.NETWORK, e.message)
        }
        resp.use {
            when (it.code) {
                401, 403 -> throw DownloadException(DownloadException.Kind.AUTH, "HTTP ${it.code}")
                404 -> throw DownloadException(DownloadException.Kind.NOT_FOUND, "HTTP 404")
                416 -> { // already complete
                    if (part.exists()) { part.renameTo(target); return@withContext }
                    throw DownloadException(DownloadException.Kind.HTTP, "HTTP 416")
                }
                200, 206 -> Unit
                else -> throw DownloadException(DownloadException.Kind.HTTP, "HTTP ${it.code}")
            }
            val body = it.body
            val resumed = it.code == 206
            if (!resumed) { existing = 0; part.delete() }
            val total = if (resumed) {
                it.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (existing + body.contentLength())
            } else body.contentLength()

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                val buf = ByteArray(256 * 1024)
                var done = existing
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = try { input.read(buf) } catch (e: IOException) {
                            throw DownloadException(DownloadException.Kind.NETWORK, e.message)
                        }
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 250_000_000L) { onProgress(done, total); lastReport = now }
                    }
                }
                onProgress(done, total)
                if (total > 0 && done != total) {
                    throw DownloadException(DownloadException.Kind.NETWORK, "Incomplete: $done/$total")
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed")
        }
    }
}
