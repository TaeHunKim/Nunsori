package com.lsa.app.ml.embedding

import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EmbeddingGemma2Embedder private constructor(
    override val modelId: String,
    private val engine: EmbeddingEngine,
) : Embedder {
    private val mutex = Mutex()
    private val options = EmbeddingOptions(normalize = true)

    override suspend fun embedImage(jpeg: ByteArray): FloatArray =
        compute(InputData.Image(jpeg))

    override suspend fun embedDocument(text: String): FloatArray =
        compute(InputData.Text(DOC_PREFIX + text))

    override suspend fun embedQuery(text: String): FloatArray =
        compute(InputData.Text(QUERY_PREFIX + text))

    private suspend fun compute(input: InputData): FloatArray = mutex.withLock {
        withContext(Dispatchers.Default) {
            VectorMath.normalize(engine.computeEmbedding(listOf(input), options).embedding)
        }
    }

    override fun close() = engine.close()

    companion object {
        private const val TAG = "LSA.EG2"
        const val QUERY_PREFIX = "task: search result | query: "
        const val DOC_PREFIX = "title: none | text: "

        private val isEmulator: Boolean
            get() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("goldfish")

        /** Loads the model (can take ~10s; call off the main thread). Tries GPU on real devices, CPU otherwise. */
        suspend fun load(modelId: String, modelFile: File, cacheDir: File): EmbeddingGemma2Embedder =
            withContext(Dispatchers.Default) {
                cacheDir.mkdirs()
                val backends: List<() -> Backend> =
                    if (isEmulator) listOf({ Backend.CPU() }) else listOf({ Backend.GPU() }, { Backend.CPU() })
                var last: Throwable? = null
                for (make in backends) {
                    val backend = make()
                    val engine = EmbeddingEngine(
                        EmbeddingEngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = backend,
                            visionBackend = backend,
                            cacheDir = cacheDir.absolutePath,
                        ),
                    )
                    try {
                        engine.initialize()
                        Log.i(TAG, "loaded $modelId on ${backend.name}")
                        return@withContext EmbeddingGemma2Embedder(modelId, engine)
                    } catch (t: Throwable) {
                        Log.w(TAG, "init failed on ${backend.name}", t)
                        runCatching { engine.close() }
                        last = t
                    }
                }
                throw IllegalStateException("Could not load embedding model", last)
            }
    }
}
