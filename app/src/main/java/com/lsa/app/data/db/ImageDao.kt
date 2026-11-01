package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_COLS =
    "mediaId, uri, displayName, relativePath, dateTaken, sizeBytes, width, height, ocrText, indexedAt, status"

@Dao
interface ImageDao {
    @Upsert
    suspend fun upsert(entity: ImageEntity)

    @Query("DELETE FROM images WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM images WHERE relativePath = :path")
    suspend fun deleteByFolder(path: String)

    @Query("DELETE FROM images")
    suspend fun deleteAll()

    /** A file was moved/renamed without its content changing: keep the embeddings. */
    @Query("UPDATE images SET relativePath = :relativePath, displayName = :displayName WHERE mediaId = :id")
    suspend fun updateLocation(id: Long, relativePath: String, displayName: String)

    @Query("SELECT mediaId, dateModified, embeddingModelId, status, relativePath, displayName FROM images")
    suspend fun states(): List<IndexState>

    @Query("SELECT $SUMMARY_COLS FROM images ORDER BY indexedAt DESC")
    fun observeSummaries(): Flow<List<ImageSummary>>

    @Query("SELECT $SUMMARY_COLS FROM images WHERE mediaId IN (:ids)")
    suspend fun summaries(ids: List<Long>): List<ImageSummary>

    @Query("SELECT COUNT(*) FROM images WHERE status = 'DONE' AND embeddingModelId != :modelId")
    fun observeStaleCount(modelId: String): Flow<Int>

    @Query("SELECT mediaId, imageEmb, ocrEmb FROM images WHERE status = 'DONE' AND embeddingModelId = :modelId")
    suspend fun vectors(modelId: String): List<VectorRow>

    @Query("SELECT mediaId, ocrText FROM images WHERE status = 'DONE' AND ocrText IS NOT NULL AND ocrText != ''")
    suspend fun ocrTexts(): List<OcrRow>

    @Query("SELECT * FROM images WHERE mediaId = :id")
    suspend fun get(id: Long): ImageEntity?

    /** Cheap fingerprint of table contents, used to invalidate the in-memory search cache. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(indexedAt), 0) FROM images")
    suspend fun stamp(): String

    @Query("SELECT * FROM indexed_folders")
    fun observeFolders(): Flow<List<IndexedFolderEntity>>

    @Query("SELECT relativePath FROM indexed_folders WHERE enabled = 1")
    suspend fun enabledFolders(): List<String>

    @Upsert
    suspend fun upsertFolder(folder: IndexedFolderEntity)

    @Query("DELETE FROM indexed_folders WHERE relativePath = :path")
    suspend fun deleteFolder(path: String)
}

@Database(entities = [ImageEntity::class, IndexedFolderEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun imageDao(): ImageDao
}
