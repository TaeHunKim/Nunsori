package com.lsa.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.audio.Cue
import com.lsa.app.indexing.audio.Span
import com.lsa.app.indexing.audio.SpeechTools
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.llm.ChatEvent
import com.lsa.app.ml.llm.ChatModel
import com.lsa.app.ml.llm.ChatTool
import com.lsa.app.ml.llm.ChatTurn
import com.lsa.app.ml.llm.TurnRole
import com.lsa.app.ml.ocr.OcrEngine
import io.github.taehunkim.ondolsaem.ModelInfo
import io.github.taehunkim.ondolsaem.sdk.ChatStreamEvent
import io.github.taehunkim.ondolsaem.sdk.HistoryTurn
import io.github.taehunkim.ondolsaem.sdk.HostCallException
import io.github.taehunkim.ondolsaem.sdk.HostLease
import io.github.taehunkim.ondolsaem.sdk.HostUnavailableException
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import io.github.taehunkim.ondolsaem.sdk.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** A borrowed resource. Closing it returns the resource; the resource itself is NOT closed. */
class Lease<T>(val value: T, private val onClose: () -> Unit) : AutoCloseable {
    private var closed = false
    override fun close() { synchronized(this) { if (closed) return; closed = true }; onClose() }
}

/** What the OndolSaem model host offers right now. */
data class HostState(
    val status: Status,
    val models: Map<String, ModelInfo> = emptyMap(),
    /** Host app version and API level, once connected. */
    val hostVersion: String? = null,
    val hostApi: Int = 0,
) {
    enum class Status {
        UNKNOWN, NOT_INSTALLED, NOT_ALLOWED, UNREACHABLE, CONNECTED,
        /** Signed with a key OndolSaem does not know: waiting for (or denied) the user's approval in OndolSaem. */
        NOT_APPROVED,
        /** OndolSaem is older than this app needs: update OndolSaem. */
        HOST_TOO_OLD,
        /** OndolSaem no longer serves this app's SDK level: update Nunsori. */
        CLIENT_TOO_OLD,
    }

    fun ready(category: String) = models[category]?.ready == true
    fun model(category: String): ModelInfo? = models[category]?.takeIf { it.id.isNotEmpty() }
}

/**
 * Nunsori's access to AI models. The models live in the separate OndolSaem app (download, loading, memory); this
 * class hands out [Lease]s on remote [Embedder] / [OcrEngine] / [SpeechTools] / [ChatModel] proxies, so the code
 * above those interfaces is the same as when the models ran in this app.
 *
 * The ids of the host's active models are mirrored into [SettingsStore] (embedding, OCR, speech, chat): the index's
 * pipeline keys are built from them, so a model change in the host re-indexes what it affects.
 */
class ModelHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    val client: OndolSaemClient = OndolSaemClient(context),
) {
    private val _state = MutableStateFlow(HostState(HostState.Status.UNKNOWN))
    val state: StateFlow<HostState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    /** For the resource indicator: "OndolSaem" while bound, else null. */
    val embedderBackend: Flow<String?> = client.connected.map { if (it) BACKEND else null }
    val chatBackend: Flow<String?> get() = embedderBackend

    /** Test hooks: when set, used instead of the host (instrumented tests run without OndolSaem). */
    var embedderOverride: (suspend () -> Embedder?)? = null
    var chatOverride: (suspend () -> ChatModel?)? = null
    var ocrOverride: (suspend () -> OcrEngine)? = null
    var speechOverride: (suspend () -> SpeechTools)? = null
    var faceOverride: (suspend () -> com.lsa.app.ml.faces.FaceDetector?)? = null
    var contextTokensOverride: Int? = null

    /** Context window of the chat model as set in OndolSaem (prompts are trimmed to it). */
    fun chatContextTokens(): Int = contextTokensOverride
        ?: _state.value.model(Categories.LLM)?.contextTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS

    init {
        scope.launch { client.modelChanges.collect { refresh() } }
    }

    /** Asks the host for its active models and mirrors their ids into the settings. Never throws. */
    suspend fun refresh(): HostState = refreshLock.withLock {
        val next = try {
            val models = client.categories().associateBy { it.category }
            val info = client.hostInfo()
            HostState(HostState.Status.CONNECTED, models, info.appVersion, info.apiVersion)
        } catch (e: HostUnavailableException) {
            Log.i(TAG, "OndolSaem unavailable: ${e.reason} ${e.message.orEmpty()}")
            HostState(statusFor(e.reason), _state.value.models)
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            HostState(HostState.Status.UNREACHABLE, _state.value.models)
        }
        if (next.status == HostState.Status.CONNECTED) mirror(next)
        _state.value = next
        next
    }

    private suspend fun mirror(s: HostState) {
        // Missing model -> empty / "none", so pipeline keys and the "model missing" hints follow the host.
        suspend fun put(current: Flow<String>, value: String, set: suspend (String) -> Unit) {
            if (current.first() != value) set(value)
        }
        s.models[Categories.EMBEDDING]?.let { put(settings.embeddingModelId, if (it.ready) it.id else "", settings::setEmbeddingModelId) }
        s.models[Categories.OCR]?.let { put(settings.ocrModelId, if (it.ready) it.id else "", settings::setOcrModelId) }
        s.models[Categories.ASR]?.let { put(settings.asrModelId, if (it.ready) it.id else "none", settings::setAsrModelId) }
        s.models[Categories.LLM]?.let { put(settings.chatModelId, it.id, settings::setChatModelId) }
        // OndolSaem before 0.3.0 (API 1) has no face category: no face model.
        put(settings.faceModelId, s.models[Categories.FACE]?.takeIf { it.ready }?.id ?: "", settings::setFaceModelId)
    }

    /** The cached model of [category], refreshing first when the host has not been asked yet. */
    private suspend fun known(category: String): ModelInfo? {
        val s = _state.value
        return if (s.status == HostState.Status.CONNECTED) s.model(category) else refresh().model(category)
    }

    /** Takes a host lease on [category] (keeps its model loaded); null when the host or the model is missing. */
    private suspend fun hostLease(category: String): HostLease? = try {
        client.acquire(category)
    } catch (e: HostCallException) {
        if (e.code == OndolSaem.Errors.MODEL_NOT_READY) { refresh(); null } else throw e
    } catch (e: HostUnavailableException) {
        refresh(); null
    }

    /**
     * Null when the host or its embedding model is missing. [background]: indexing (waits behind interactive
     * requests in the host). The host picks the backend.
     */
    suspend fun embedder(background: Boolean = false): Lease<Embedder>? {
        embedderOverride?.let { f -> return f()?.let { e -> Lease(e) { } } }
        val lease = hostLease(Categories.EMBEDDING) ?: return null
        val info = known(Categories.EMBEDDING)
        if (info == null || !info.ready) { lease.close(); return null }
        val priority = if (background) OndolSaem.PRIORITY_BACKGROUND else OndolSaem.PRIORITY_INTERACTIVE
        return Lease(RemoteEmbedder(client, info, priority) { scope.launch { refresh() } }) { lease.close() }
    }

    /** [modelId] is ignored: the host serves its active OCR model. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun ocr(modelId: String = ""): Lease<OcrEngine> {
        ocrOverride?.let { return Lease(it()) { } }
        val lease = hostLease(Categories.OCR)
        val id = _state.value.model(Categories.OCR)?.id ?: ""
        return Lease(RemoteOcrEngine(client, id)) { lease?.close() }
    }

    /** Voice activity detection always works when the host is reachable; transcription needs its speech model. */
    suspend fun speech(): Lease<SpeechTools> {
        speechOverride?.let { return Lease(it()) { } }
        val lease = hostLease(Categories.ASR)
        val can = lease != null && runCatching { client.canTranscribe() }.getOrDefault(false)
        return Lease(RemoteSpeechTools(client, can)) { lease?.close() }
    }

    /**
     * Face detection + identity vectors (OndolSaem API 2). Null when OndolSaem is older than 0.3.0, unreachable, or
     * its face model is not downloaded. Background priority (indexing).
     */
    suspend fun faces(): Lease<com.lsa.app.ml.faces.FaceDetector>? {
        faceOverride?.let { f -> return f()?.let { d -> Lease(d) { } } }
        if (!runCatching { client.supports(OndolSaem.Api.V2) }.getOrDefault(false)) return null
        val lease = hostLease(Categories.FACE) ?: return null
        val info = known(Categories.FACE)
        if (info == null || !info.ready) { lease.close(); return null }
        return Lease(RemoteFaceDetector(client, info.id)) { lease.close() }
    }

    /** Null when the host or its chat model is missing. Loading the model can take a while (the call waits). */
    suspend fun chat(): Lease<ChatModel>? {
        chatOverride?.let { f -> return f()?.let { m -> Lease(m) { } } }
        val lease = hostLease(Categories.LLM) ?: return null
        val info = known(Categories.LLM)
        if (info == null || !info.ready) { lease.close(); return null }
        return Lease(RemoteChatModel(client, info, scope)) { lease.close() }
    }

    /** The chat history was edited: drop the host's cached conversation. */
    suspend fun closeChat() { runCatching { client.resetChat() } }

    /** Memory is managed by the host. */
    suspend fun trim() {}
    suspend fun closeAll() {}

    companion object {
        private const val TAG = "LSA.ModelHost"
        const val BACKEND = "OndolSaem"
        /** Assumed when OndolSaem did not report a context size. */
        const val DEFAULT_CONTEXT_TOKENS = 4096
        /** Where to get the host app. */
        const val INSTALL_URL = "https://github.com/taehunkim/ondolsaem/releases"
        /** Where to get a newer Nunsori. */
        const val APP_RELEASES_URL = "https://github.com/TaeHunKim/Nunsori/releases"

        fun statusFor(reason: HostUnavailableException.Reason): HostState.Status = when (reason) {
            HostUnavailableException.Reason.NOT_INSTALLED -> HostState.Status.NOT_INSTALLED
            HostUnavailableException.Reason.NOT_ALLOWED -> HostState.Status.NOT_ALLOWED
            HostUnavailableException.Reason.NOT_APPROVED -> HostState.Status.NOT_APPROVED
            HostUnavailableException.Reason.NO_ANSWER -> HostState.Status.UNREACHABLE
            HostUnavailableException.Reason.HOST_TOO_OLD -> HostState.Status.HOST_TOO_OLD
            HostUnavailableException.Reason.CLIENT_TOO_OLD -> HostState.Status.CLIENT_TOO_OLD
        }
    }
}

/** Thrown when the host started answering with another embedding model than the one this proxy stands for. */
class EmbeddingModelChangedException(expected: String, actual: String) :
    IllegalStateException("embedding model changed in OndolSaem: $expected -> $actual")

private class RemoteEmbedder(
    private val client: OndolSaemClient, info: ModelInfo, private val priority: Int, private val onMismatch: () -> Unit,
) : Embedder {
    override val modelId: String = info.id
    override val backend: String = ModelHost.BACKEND
    override val supportsAudio: Boolean = info.audio

    private fun check(r: io.github.taehunkim.ondolsaem.EmbeddingResult): FloatArray {
        if (r.modelId != modelId) { onMismatch(); throw EmbeddingModelChangedException(modelId, r.modelId) }
        return r.vector
    }

    override suspend fun embedImage(jpeg: ByteArray) = check(client.embedImage(jpeg, priority))
    override suspend fun embedDocument(text: String, title: String?) = check(client.embedDocument(text, title, priority))
    override suspend fun embedQuery(text: String) = check(client.embedQuery(text, OndolSaem.PRIORITY_INTERACTIVE))
    override suspend fun embedAudio(wav: ByteArray) = check(client.embedAudio(wav, priority))
    override suspend fun embedImages(jpegs: List<ByteArray>) = check(client.embedImages(jpegs, priority))
    override fun close() {}
}

private class RemoteOcrEngine(private val client: OndolSaemClient, override val modelId: String) : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): String {
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return client.recognizeText(jpeg)
    }
}

private class RemoteSpeechTools(private val client: OndolSaemClient, override val canTranscribe: Boolean) : SpeechTools {
    override suspend fun speechSpans(pcm: java.io.File): List<Span> =
        client.speechSpans(pcm).map { Span(it.first, it.last) }

    override suspend fun transcribe(pcm: java.io.File, fromMs: Long, onCue: suspend (Cue) -> Unit) {
        if (!canTranscribe) return
        client.transcribe(pcm, fromMs) { s, e, t -> onCue(Cue(s, e, t)) }
    }
}

private class RemoteChatModel(private val client: OndolSaemClient, info: ModelInfo, private val scope: CoroutineScope) : ChatModel {
    override val supportsImages: Boolean = info.vision
    override val backend: String = ModelHost.BACKEND

    override fun reply(conversationKey: String, system: String, history: List<ChatTurn>, user: ChatTurn, tools: List<ChatTool>): Flow<ChatEvent> {
        val byName = tools.associateBy { it.name }
        return client.chat(
            conversationKey, system,
            history.map { HistoryTurn(it.role == TurnRole.USER, it.text) },
            user.text, if (supportsImages) user.images else emptyList(),
            tools.map { ToolSpec(it.name, it.description, it.parameters) },
        ) { name, args ->
            val tool = byName[name] ?: return@chat "Error: unknown tool $name"
            tool.execute(runCatching { JSONObject(args) }.getOrElse { JSONObject() })
        }.map { ev ->
            when (ev) {
                is ChatStreamEvent.Token -> ChatEvent.Token(ev.text)
                is ChatStreamEvent.ToolRan -> ChatEvent.ToolRan(ev.name, ev.argsJson, ev.result)
            }
        }
    }

    override fun reset() { scope.launch { runCatching { client.resetChat() } } }
    override fun close() {}
}

/** Faces through OndolSaem (background priority). */
private class RemoteFaceDetector(private val client: OndolSaemClient, override val modelId: String) : com.lsa.app.ml.faces.FaceDetector {
    override suspend fun detect(jpeg: ByteArray): List<com.lsa.app.ml.faces.Face> =
        client.detectFaces(jpeg, OndolSaem.PRIORITY_BACKGROUND).map { com.lsa.app.ml.faces.Face(it.left, it.top, it.right, it.bottom, it.score, it.vector) }
}
