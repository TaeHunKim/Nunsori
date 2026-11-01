package com.lsa.app.ml

import android.content.Context
import android.util.Log
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.ResidencyPolicy
import com.lsa.app.indexing.audio.SherpaSpeech
import com.lsa.app.indexing.audio.SpeechTools
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.llm.ChatModel
import com.lsa.app.ml.llm.LiteRtChatModel
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
    private val describe: (T) -> String? = { null },
    private val policy: suspend () -> ResidencyPolicy,
) {
    private val _label = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** What is loaded right now (e.g. "GPU"), or null. For the resource indicator. */
    val label: kotlinx.coroutines.flow.StateFlow<String?> = _label
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
            current = it; currentKey = key; _label.value = describe(it)
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
        current = null; currentKey = null; _label.value = null
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
    private val embedderRes = LeasedResource<Embedder>("embedder", scope, { it.backend.ifEmpty { null } }, policy)
    private val ocrRes = LeasedResource<OcrEngine>("ocr", scope, policy = policy)
    private val speechRes = LeasedResource<SpeechTools>("speech", scope, policy = policy)
    private val chatRes = LeasedResource<ChatModel>("chat", scope, { it.backend.ifEmpty { null } }, policy)

    /** Backend of the loaded embedding model and chat model (null = not loaded). */
    val embedderBackend get() = embedderRes.label
    val chatBackend get() = chatRes.label

    /** Test hook. */
    var chatFactory: suspend (spec: com.lsa.app.models.ModelSpec, file: File, contextTokens: Int) -> ChatModel? = { spec, file, ctx ->
        LiteRtChatModel.load(file, File(context.cacheDir, "litertlm"), spec.vision, ctx)
    }

    /** Test hook. */
    var embedderFactory: suspend (modelId: String, file: File, cacheDir: File, lowPower: Boolean) -> Embedder? = { id, file, cache, low ->
        when (id) {
            "eg2-440m", "eg2-740m" -> EmbeddingGemma2Embedder.load(id, file, cache, low)
            else -> null
        }
    }

    /** Null when the selected embedding model is not installed or not supported. */
    suspend fun embedder(lowPower: Boolean = false): Lease<Embedder>? {
        val id = settings.embeddingModelId.first()
        val spec = ModelCatalog.byId(id) ?: return null
        if (spec.source !is ModelSource.HuggingFace || !manager.isReady(spec)) return null
        val file = manager.file(spec) ?: return null
        return embedderRes.acquire(if (lowPower) "$id:low" else id) { embedderFactory(id, file, File(context.cacheDir, "litertlm"), lowPower) }
    }

    /** Only ML Kit is implemented so far; [modelId] keys the resident instance. */
    suspend fun ocr(modelId: String): Lease<OcrEngine> =
        ocrRes.acquire(modelId) { MlKitOcrEngine() }!!

    /** Voice activity detection always works; recognition only when the selected ASR model is installed. */
    suspend fun speech(lowPower: Boolean = false): Lease<SpeechTools> {
        val spec = ModelCatalog.byId(settings.asrModelId.first())?.takeIf { it.source is ModelSource.Archive && manager.isReady(it) }
        return speechRes.acquire((spec?.id ?: "none") + if (lowPower) ":low" else "") { SherpaSpeech(context, spec?.let(manager::dir), if (lowPower) 1 else 2) }!!
    }

    /** Null when the selected chat model is not installed. Loading takes a while and uses a lot of memory. */
    suspend fun chat(): Lease<ChatModel>? {
        val spec = ModelCatalog.byId(settings.chatModelId.first()) ?: return null
        if (spec.kind != com.lsa.app.models.ModelKind.LLM || spec.source !is ModelSource.HuggingFace || !manager.isReady(spec)) return null
        val file = manager.file(spec) ?: return null
        val ctx = settings.chatContextTokens.first()
        return chatRes.acquire("${spec.id}|$ctx") { chatFactory(spec, file, ctx) }
    }

    suspend fun closeChat() = chatRes.closeNow()
    suspend fun trim() { embedderRes.trim(); ocrRes.trim(); speechRes.trim(); chatRes.trim() }
    suspend fun closeAll() { embedderRes.closeNow(); ocrRes.closeNow(); speechRes.closeNow(); chatRes.closeNow() }
}
