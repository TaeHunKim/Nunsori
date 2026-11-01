package com.lsa.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object IndexStatus {
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long?,
    /** MediaStore DATE_MODIFIED (seconds). Used for change detection. */
    val dateModified: Long,
    val latitude: Double?,
    val longitude: Double?,
    val ocrText: String?,
    val ocrModelId: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val imageEmb: ByteArray?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val ocrEmb: ByteArray?,
    val embeddingModelId: String,
    val embeddingDim: Int,
    val indexedAt: Long,
    val status: String,
    val error: String?,
)

@Entity(tableName = "indexed_folders")
data class IndexedFolderEntity(
    @PrimaryKey val relativePath: String,
    val enabled: Boolean = true,
)

/** Lightweight projection (no BLOBs) for lists. */
data class ImageSummary(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val dateTaken: Long?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val ocrText: String?,
    val indexedAt: Long,
    val status: String,
)

data class IndexState(
    val mediaId: Long,
    val dateModified: Long,
    val embeddingModelId: String,
    val status: String,
    val relativePath: String,
    val displayName: String,
)

@Suppress("ArrayInDataClass")
data class VectorRow(
    val mediaId: Long,
    val imageEmb: ByteArray?,
    val ocrEmb: ByteArray?,
)

data class OcrRow(val mediaId: Long, val ocrText: String?)
