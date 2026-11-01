package com.lsa.app.ml.ocr

import android.graphics.Bitmap

interface OcrEngine : AutoCloseable {
    val modelId: String
    suspend fun recognize(bitmap: Bitmap): String
    override fun close() {}
}
