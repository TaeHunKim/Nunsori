package com.lsa.app.ml.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** ML Kit v2 Korean recognizer (bundled model; also reads Latin script). */
class MlKitOcrEngine(override val modelId: String = "mlkit-ko") : OcrEngine {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private var created = false

    override suspend fun recognize(bitmap: Bitmap): String {
        created = true
        return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
    }

    override fun close() { if (created) recognizer.close() }
}
