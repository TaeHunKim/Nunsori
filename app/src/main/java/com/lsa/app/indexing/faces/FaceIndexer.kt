package com.lsa.app.indexing.faces

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.lsa.app.data.db.FaceDao
import com.lsa.app.data.db.FaceEntity
import com.lsa.app.data.db.PersonEntity
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.ml.ModelHost
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min

/** Whether faces can be found right now (shown on the People screen). */
enum class FaceAvailability { READY, OFF, HOST_TOO_OLD, NO_MODEL }

/** Progress of the face pass: [done] of [total] photos. */
data class FaceProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * The face pass of an indexing run: finds the faces of photos that have not been scanned with the current face model
 * (`items.faceKey`), stores them with a small crop, then groups them ([FaceClusterer]). It never touches the photos'
 * search vectors, so turning face grouping on again only scans faces.
 */
class FaceIndexer(
    private val context: Context,
    private val dao: FaceDao,
    private val thumbs: ThumbStore,
    private val models: ModelHost,
    private val settings: SettingsStore,
    private val clusterer: FaceClusterer = FaceClusterer(),
) {
    private val _progress = MutableStateFlow(FaceProgress())
    val progress: StateFlow<FaceProgress> = _progress.asStateFlow()

    val availability: Flow<FaceAvailability> = combine(settings.faceGrouping, settings.faceModelId, models.state) { on, id, s ->
        when {
            !on -> FaceAvailability.OFF
            id.isNotEmpty() -> FaceAvailability.READY
            s.status == com.lsa.app.ml.HostState.Status.CONNECTED && s.hostApi < io.github.taehunkim.ondolsaem.sdk.OndolSaem.Api.V2 -> FaceAvailability.HOST_TOO_OLD
            else -> FaceAvailability.NO_MODEL
        }
    }

    /**
     * Scans every photo that still needs it, while [gate] allows (stop button, heat), then regroups. Returns the
     * number of photos scanned; 0 when face grouping is off or OndolSaem cannot find faces.
     */
    suspend fun pass(gate: suspend () -> Boolean): Int {
        if (!settings.faceGrouping.first()) return 0
        val lease = models.faces() ?: return 0
        var scanned = 0
        try {
            val detector = lease.value
            val key = keyOf(detector.modelId)
            if (dao.countScannedWithOtherModel(key) > 0) {
                // a new face model: old vectors cannot be compared with new ones, start over
                Log.i(TAG, "face model changed to ${detector.modelId}: scanning all photos again")
                deleteAll()
            }
            val total = dao.countFacesTodo(key)
            if (total == 0) { regroup(); return 0 }
            _progress.value = FaceProgress(true, 0, total)
            while (gate()) {
                val batch = dao.facesTodo(key, BATCH)
                if (batch.isEmpty()) break
                for (todo in batch) {
                    if (!gate()) break
                    try {
                        scanOne(todo.itemId, todo.sourceKey, Uri.parse(todo.uri), detector)
                    } catch (e: Exception) {
                        // an unreadable photo is not retried with this model; the search index already reports it
                        Log.w(TAG, "faces of ${todo.displayName} failed", e)
                    }
                    dao.setFaceKey(todo.itemId, key)
                    scanned++
                    _progress.value = FaceProgress(true, scanned, total)
                }
            }
            regroup()
        } finally {
            lease.close()
            _progress.value = FaceProgress()
        }
        Log.i(TAG, "face pass: $scanned photo(s)")
        return scanned
    }

    private suspend fun scanOne(itemId: Long, sourceKey: String, uri: Uri, detector: com.lsa.app.ml.faces.FaceDetector) {
        val bmp = BitmapLoader.decode(context, uri, MAX_SIDE)
        try {
            val faces = detector.detect(BitmapLoader.toJpeg(bmp, MAX_SIDE))
            // a photo processed again (changed file, new model) replaces its faces
            dao.thumbsOfItem(itemId).forEach { thumbs.file(it).delete() }
            dao.deleteFacesOfItem(itemId)
            if (faces.isEmpty()) return
            dao.insertFaces(
                faces.mapIndexed { i, f ->
                    FaceEntity(
                        itemId = itemId, left = f.left, top = f.top, right = f.right, bottom = f.bottom, score = f.score,
                        vector = Quant.encode(f.vector), personId = null,
                        thumbPath = runCatching { crop(bmp, f)?.let { c -> thumbs.save(sourceKey, "face_$i", c, FACE_SIDE).also { c.recycle() } } }.getOrNull(),
                    )
                },
            )
        } finally { bmp.recycle() }
    }

    /** A square crop around the face with some margin (hair, chin). */
    private fun crop(bmp: Bitmap, f: com.lsa.app.ml.faces.Face): Bitmap? {
        val w = bmp.width; val h = bmp.height
        val cx = (f.left + f.right) / 2 * w; val cy = (f.top + f.bottom) / 2 * h
        val side = max((f.right - f.left) * w, (f.bottom - f.top) * h) * 1.5f
        val l = max(0, (cx - side / 2).toInt()); val t = max(0, (cy - side / 2).toInt())
        val r = min(w, (cx + side / 2).toInt()); val b = min(h, (cy + side / 2).toInt())
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }

    /** Puts ungrouped faces into groups and merges groups that turned out to be the same person. */
    suspend fun regroup() {
        val now = System.currentTimeMillis()
        val all = dao.allVectors()
        val people = dao.allPeople().associateBy { it.personId }
        val groups = mutableMapOf<Long, FaceClusterer.Group>()
        val decoded = all.associate { it.faceId to Quant.decode(it.vector) }
        for (f in all) {
            val pid = f.personId ?: continue
            val v = decoded.getValue(f.faceId)
            groups.getOrPut(pid) { FaceClusterer.Group(pid, people[pid]?.name != null, FloatArray(v.size), 0) }.add(v)
        }
        val assigned = HashMap<Long, Long>()
        for (f in all.filter { it.personId == null }) {
            val v = decoded.getValue(f.faceId)
            val g = clusterer.match(v, groups.values, f.blockedPersonId)
                ?: dao.insertPerson(PersonEntity(createdAt = now, updatedAt = now)).let { id ->
                    FaceClusterer.Group(id, false, FloatArray(v.size), 0).also { groups[id] = it }
                }
            g.add(v)
            dao.assign(listOf(f.faceId), g.id)
            assigned[f.faceId] = g.id
        }
        for (f in all) {
            val pid = f.personId ?: assigned[f.faceId] ?: continue
            val blocked = f.blockedPersonId ?: continue
            groups[pid]?.blocked?.add(blocked)
        }
        for ((from, into) in clusterer.merges(groups.values.toList())) dao.merge(from, into, now)
        dao.deleteEmptyPeople()
    }

    /** Face grouping turned off (or a new face model): faces, people and crops go; every photo needs a new scan. */
    suspend fun deleteAll() {
        dao.allThumbs().forEach { thumbs.file(it).delete() }
        dao.deleteAll()
        dao.clearFaceKeys()
    }

    companion object {
        private const val TAG = "LSA.Faces"
        const val FACE_KEY_VERSION = 1
        private const val BATCH = 50
        private const val MAX_SIDE = 1600
        private const val FACE_SIDE = 160

        fun keyOf(modelId: String) = "$modelId|f$FACE_KEY_VERSION"
    }
}
