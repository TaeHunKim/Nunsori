package com.lsa.app.models

import androidx.annotation.StringRes
import com.lsa.app.R

enum class ModelKind { EMBEDDING, OCR, ASR, LLM }

sealed interface ModelSource {
    /** Downloaded by the user from Hugging Face into app storage. */
    data class HuggingFace(val repo: String, val fileName: String) : ModelSource {
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    }

    /** One archive (tar.bz2) downloaded and unpacked; [files] maps an entry name suffix to the local file name. */
    data class Archive(val url: String, val files: Map<String, String>) : ModelSource

    /** Shipped inside the APK (small). */
    data object Bundled : ModelSource

    /** Listed in the catalog but not implemented yet. */
    data object Planned : ModelSource
}

data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    @StringRes val descriptionRes: Int,
    val sizeBytes: Long?,
    val license: String,
    val source: ModelSource,
    /** Embedding models only: can embed audio clips (needs the audio encoder). */
    val audio: Boolean = false,
    /** Chat models only: accepts images as input. */
    val vision: Boolean = false,
)

object ModelCatalog {
    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "eg2-440m", kind = ModelKind.EMBEDDING,
            displayName = "EmbeddingGemma 2 · text+vision 440M",
            descriptionRes = R.string.model_eg2_440m_desc,
            sizeBytes = 387_710_976L, license = "Apache-2.0",
            source = ModelSource.HuggingFace(
                "litert-community/embeddinggemma-2-text-vision-440m-litert-lm",
                "embeddinggemma-2-text-vision-440m.litertlm",
            ),
        ),
        ModelSpec(
            id = "eg2-740m", kind = ModelKind.EMBEDDING,
            displayName = "EmbeddingGemma 2 · 740M (+audio)",
            descriptionRes = R.string.model_eg2_740m_desc,
            sizeBytes = 485_000_000L, license = "Apache-2.0",
            source = ModelSource.HuggingFace(
                "litert-community/embeddinggemma-2-740m-litert-lm",
                "embeddinggemma-2-740m.litertlm",
            ),
            audio = true,
        ),
        ModelSpec(
            id = "mobileclip2-s0", kind = ModelKind.EMBEDDING,
            displayName = "MobileCLIP2-S0",
            descriptionRes = R.string.model_mobileclip2_desc,
            sizeBytes = null, license = "Apple ASCL",
            source = ModelSource.Planned,
        ),
        ModelSpec(
            id = "chat-gemma4-e2b", kind = ModelKind.LLM,
            displayName = "Gemma 4 E2B (chat, images)",
            descriptionRes = R.string.model_chat_gemma4_e2b_desc,
            sizeBytes = 2_588_147_712L, license = "Apache-2.0",
            source = ModelSource.HuggingFace("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"),
            vision = true,
        ),
        ModelSpec(
            id = "chat-gemma4-e4b", kind = ModelKind.LLM,
            displayName = "Gemma 4 E4B (chat, images)",
            descriptionRes = R.string.model_chat_gemma4_e4b_desc,
            sizeBytes = 3_659_530_240L, license = "Apache-2.0",
            source = ModelSource.HuggingFace("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm"),
            vision = true,
        ),
        ModelSpec(
            id = "chat-qwen25-1.5b", kind = ModelKind.LLM,
            displayName = "Qwen2.5 1.5B (chat, text only)",
            descriptionRes = R.string.model_chat_qwen_desc,
            sizeBytes = 1_597_922_856L, license = "Apache-2.0",
            source = ModelSource.HuggingFace(
                "litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelSpec(
            id = "mlkit-ko", kind = ModelKind.OCR,
            displayName = "ML Kit Text Recognition v2 (Korean + Latin)",
            descriptionRes = R.string.model_mlkit_desc,
            sizeBytes = null, license = "ML Kit Terms",
            source = ModelSource.Bundled,
        ),
        ModelSpec(
            id = "asr-sensevoice", kind = ModelKind.ASR,
            displayName = "SenseVoice (Korean, English, Chinese, Japanese)",
            descriptionRes = R.string.model_sensevoice_desc,
            sizeBytes = 163_002_883L, license = "FunASR Model License",
            source = ModelSource.Archive(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
                mapOf("model.int8.onnx" to "model.int8.onnx", "tokens.txt" to "tokens.txt"),
            ),
        ),
        ModelSpec(
            id = "asr-moonshine", kind = ModelKind.ASR,
            displayName = "Moonshine tiny (Korean / English)",
            descriptionRes = R.string.model_moonshine_desc,
            sizeBytes = null, license = "MIT",
            source = ModelSource.Planned,
        ),
        ModelSpec(
            id = "ppocrv5-ko", kind = ModelKind.OCR,
            displayName = "PP-OCRv5 mobile (Korean)",
            descriptionRes = R.string.model_ppocr_desc,
            sizeBytes = null, license = "Apache-2.0",
            source = ModelSource.Planned,
        ),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
    fun ofKind(kind: ModelKind): List<ModelSpec> = all.filter { it.kind == kind }
}
