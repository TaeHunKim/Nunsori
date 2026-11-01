package com.lsa.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaImage(
    val mediaId: Long,
    val uri: Uri,
    val relativePath: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long?,
    /** seconds */
    val dateModified: Long,
)

data class FolderInfo(val relativePath: String, val imageCount: Int)

interface MediaSource {
    suspend fun listImages(folders: Collection<String>): List<MediaImage>
}

class MediaStoreRepository(private val context: Context) : MediaSource {
    private val resolver: ContentResolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
    )

    private fun queryAll(): List<MediaImage> {
        val out = ArrayList<MediaImage>()
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += MediaImage(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    relativePath = c.getString(iPath) ?: "",
                    displayName = c.getString(iName) ?: "",
                    mimeType = c.getString(iMime),
                    sizeBytes = c.getLong(iSize),
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    dateTaken = if (c.isNull(iTaken)) null else c.getLong(iTaken).takeIf { it > 0 },
                    dateModified = c.getLong(iMod),
                )
            }
        }
        return out
    }

    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        queryAll().groupingBy { it.relativePath }.eachCount()
            .map { FolderInfo(it.key, it.value) }
            .sortedBy { it.relativePath.lowercase() }
    }

    override suspend fun listImages(folders: Collection<String>): List<MediaImage> = withContext(Dispatchers.IO) {
        val set = folders.toSet()
        queryAll().filter { it.relativePath in set }
    }
}
