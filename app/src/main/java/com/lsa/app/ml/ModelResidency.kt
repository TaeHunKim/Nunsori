package com.lsa.app.ml

import android.content.Context
import android.util.Log
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.ResidencyPolicy
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.embedding.EmbeddingGemma2Embedder
import com.lsa.app.ml.ocr.MlKitOcrEngine
import com.lsa.app.ml.ocr.OcrEngine
import com.lsa.app.models.ModelCatalog
import com.lsa.app.models.ModelManager
import com.lsa.app.models.ModelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** A borrowed resource. Closing it returns the resource; the resource itself is NOT closed. */
class Lease<T>(val value: T, private val onClose: () -> Unit) : AutoCloseable {
    private var closed = false
    override fun close() { if (!closed) { closed = true; onClose() } }
}

/**
 * Holds one heavyweight resource (a loaded model) and unloads it according to [policy] once nobody is using it.
 * While at least one [Lease] is outstanding the resource is never closed (except by [closeNow]).
 */
class LeasedResource<T : AutoCloseable>(
    private val name: String,
    private val scope: CoroutineScope,
    private val policy: suspend () -> ResidencyPolicy,
) {
    private val mutex = Mutex()
    private var current: T? = null
    private var currentKey: String? = null
    private var leases = 0
    private var timer: Job? = null

    /** Returns null when [load] yields null. A different [key] replaces (and closes) the resident resource. */
    suspend fun acquire(key: String, load: suspend () -> T?): Lease<T>? = mutex.withLock {
        timer?.cancel(); timer = null
        if (currentKey != key) closeLocked()
        val res = current ?: load()?.also {
            current = it; currentKey = key
            Log.i(TAG, "$name loaded ($key)")
        } ?: return@withLock null
        leases++
        Lease(res) { scope.launch { release() } }
    }

    private suspend fun release() = mutex.withLock {
        if (leases > 0) leases--
        if (leases > 0 || current == null) return@withLock
        when (val idle = policy().idleMillis) {
            null -> Unit
            0L -> closeLocked()
            else -> timer = scope.launch {
                delay(idle)
                mutex.withLock { if (leases == 0) closeLocked() }
            }
        }
    }

    /** Unload now if nobody is using it (memory pressure). */
    suspend fun trim() = mutex.withLock { if (leases == 0) closeLocked() }

    /** Unload regardless of leases (model deleted/changed). */
    suspend fun closeNow() = mutex.withLock { closeLocked() }

    val isLoaded: Boolean get() = current != null

    private fun closeLocked() {
        timer?.cancel(); timer = null
        current?.let {
            runCatching { it.close() }
            Log.i(TAG, "$name unloaded")
        }
        current = null; currentKey = null
    }

    private companion object { const val TAG = "LSA.Residency" }
}

class ModelResidency(
    private val context: Context,
    private val manager: ModelManager,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    private val policy: suspend () -> ResidencyPolicy = { settings.residency.first() }
    private val embedderRes = LeasedResource<Embedder>("embedder", scope, policy)
    private val ocrRes = LeasedResource<OcrEngine>("ocr", scope, policy)

    /** Test hook. */
    var embedderFactory: suspend (modelId: String, file: File, cacheDir: File) -> Embedder? = { id, file, cache ->
        when (id) {
            "eg2-440m", "eg2-740m" -> EmbeddingGemma2Embedder.load(id, file, cache)
            else -> null
        }
    }

    /** Null when the selected embedding model is not installed or not supported. */
    suspend fun embedder(): Lease<Embedder>? {
        val id = settings.embeddingModelId.first()
        val spec = ModelCatalog.byId(id) ?: return null
        if (spec.source !is ModelSource.HuggingFace || !manager.isReady(spec)) return null
        val file = manager.file(spec) ?: return null
        return embedderRes.acquire(id) { embedderFactory(id, file, File(context.cacheDir, "litertlm")) }
    }

    /** Only ML Kit is implemented so far; [modelId] keys the resident instance. */
    suspend fun ocr(modelId: String): Lease<OcrEngine> =
        ocrRes.acquire(modelId) { MlKitOcrEngine() }!!

    suspend fun trim() { embedderRes.trim(); ocrRes.trim() }
    suspend fun closeAll() { embedderRes.closeNow(); ocrRes.closeNow() }
}
