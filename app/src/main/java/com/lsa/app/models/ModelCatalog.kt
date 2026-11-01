package com.lsa.app.models

import androidx.annotation.StringRes
import com.lsa.app.R

enum class ModelKind { EMBEDDING, OCR }

sealed interface ModelSource {
    /** Downloaded by the user from Hugging Face into app storage. */
    data class HuggingFace(val repo: String, val fileName: String) : ModelSource {
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    }

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
        ),
        ModelSpec(
            id = "mobileclip2-s0", kind = ModelKind.EMBEDDING,
            displayName = "MobileCLIP2-S0",
            descriptionRes = R.string.model_mobileclip2_desc,
            sizeBytes = null, license = "Apple ASCL",
            source = ModelSource.Planned,
        ),
        ModelSpec(
            id = "mlkit-ko", kind = ModelKind.OCR,
            displayName = "ML Kit Text Recognition v2 (Korean + Latin)",
            descriptionRes = R.string.model_mlkit_desc,
            sizeBytes = null, license = "ML Kit Terms",
            source = ModelSource.Bundled,
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
