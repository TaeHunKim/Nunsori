package com.lsa.app.indexing

import com.lsa.app.data.db.ImageDao
import com.lsa.app.data.db.ImageEntity
import com.lsa.app.data.db.IndexStatus
import com.lsa.app.data.media.MediaImage
import com.lsa.app.data.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class IndexProgress(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val error: IndexError? = null,
    val finished: Boolean = false,
    /** Stopped early by the thermal/stop gate; remaining work is picked up by the next run. */
    val stoppedEarly: Boolean = false,
)

enum class IndexError { MODEL_MISSING, NO_FOLDERS, LOAD_FAILED }

/** Everything needed to index one file; backed by the loaded embedding + OCR models. */
interface IndexSession {
    val embeddingModelId: String
    suspend fun index(item: MediaImage): ImageEntity
    fun failed(item: MediaImage, t: Throwable): ImageEntity
}

/** Result of [IndexRunner.sync]: what is already indexed and what still has to be processed. */
data class IndexPlan(val total: Int, val done: Int, val todo: List<MediaImage>)

/**
 * Incremental, resumable indexing.
 *
 * [sync] needs no model: it removes rows for files that disappeared, records moves/renames and works out what has to
 * be (re)indexed. [process] does the expensive part and commits file by file, so cancelling loses at most one file.
 */
class IndexRunner(
    private val dao: ImageDao,
    private val media: MediaSource,
) {
    suspend fun sync(folders: List<String>, embeddingModelId: String): IndexPlan {
        val items = media.listImages(folders)
        val states = dao.states().associateBy { it.mediaId }

        // An empty listing while rows exist usually means lost permission / unmounted storage, not "all deleted".
        if (items.isEmpty() && states.isNotEmpty()) return IndexPlan(0, 0, emptyList())

        val present = items.mapTo(HashSet()) { it.mediaId }
        states.keys.filter { it !in present }.chunked(500).forEach { dao.deleteByIds(it) }

        val todo = ArrayList<MediaImage>()
        for (item in items) {
            val s = states[item.mediaId]
            val unchanged = s != null && s.dateModified == item.dateModified &&
                s.embeddingModelId == embeddingModelId && s.status == IndexStatus.DONE
            when {
                s == null || !unchanged -> todo += item
                s.relativePath != item.relativePath || s.displayName != item.displayName ->
                    dao.updateLocation(item.mediaId, item.relativePath, item.displayName)
            }
        }
        todo.sortByDescending { it.dateTaken ?: (it.dateModified * 1000) }
        return IndexPlan(total = items.size, done = items.size - todo.size, todo = todo)
    }

    /**
     * [gate] is checked before every file; returning false stops early (thermal throttling, user pause).
     */
    suspend fun process(
        plan: IndexPlan,
        session: IndexSession,
        gate: suspend () -> Boolean = { true },
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexProgress {
        var progress = IndexProgress(total = plan.total, done = plan.done)
        onProgress(progress)
        for (item in plan.todo) {
            currentCoroutineContext().ensureActive()
            if (!gate()) {
                progress = progress.copy(current = null, stoppedEarly = true)
                onProgress(progress)
                return progress
            }
            progress = progress.copy(current = item.displayName)
            onProgress(progress)
            try {
                dao.upsert(session.index(item))
                progress = progress.copy(done = progress.done + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.upsert(session.failed(item, e))
                progress = progress.copy(failed = progress.failed + 1)
            }
            onProgress(progress)
        }
        progress = progress.copy(current = null, finished = true)
        onProgress(progress)
        return progress
    }
}
