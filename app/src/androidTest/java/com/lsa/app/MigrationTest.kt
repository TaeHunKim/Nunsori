package com.lsa.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ALL_MIGRATIONS
import com.lsa.app.data.db.MIGRATION_1_2
import com.lsa.app.data.db.MIGRATION_2_3
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.ml.embedding.VectorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun v1ImagesBecomeItemsAndInt8Segments() {
        val img = floatArrayOf(0.6f, 0.8f, 0f, 0f)
        val ocr = floatArrayOf(0f, 0f, 1f, 0f)
        helper.createDatabase("mig", 1).apply {
            execSQL("INSERT INTO indexed_folders (relativePath, enabled) VALUES ('Pictures/fixtures/', 1)")
            val s = compileStatement(
                """INSERT INTO images (mediaId, uri, relativePath, displayName, mimeType, sizeBytes, width, height, dateTaken, dateModified,
                   latitude, longitude, ocrText, ocrModelId, imageEmb, ocrEmb, embeddingModelId, embeddingDim, indexedAt, status, error)
                   VALUES (42, 'content://m/42', 'Pictures/fixtures/', 'a.jpg', 'image/jpeg', 100, 10, 20, 5, 1700000000,
                   NULL, NULL, 'hello 영수증', 'mlkit-ko', ?, ?, 'eg2-440m', 4, 123, 'DONE', NULL)""",
            )
            s.bindBlob(1, VectorMath.toBytes(img)); s.bindBlob(2, VectorMath.toBytes(ocr)); s.executeInsert()
            execSQL(
                """INSERT INTO images (mediaId, uri, relativePath, displayName, mimeType, sizeBytes, width, height, dateTaken, dateModified,
                   embeddingModelId, embeddingDim, indexedAt, status, error) VALUES
                   (43, 'content://m/43', 'Pictures/fixtures/', 'bad.jpg', 'image/jpeg', 1, 1, 1, NULL, 1, 'eg2-440m', 0, 1, 'FAILED', 'boom')""",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)

        db.query("SELECT sourceKey, kind, folderKey, displayName, modifiedAt, status, embeddingModelId, pipelineKey, progress FROM items ORDER BY sourceKey").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("ms:image:42", c.getString(0)); assertEquals("IMAGE", c.getString(1))
            assertEquals("Pictures/fixtures/", c.getString(2)); assertEquals("a.jpg", c.getString(3))
            assertEquals("seconds become milliseconds", 1_700_000_000_000L, c.getLong(4))
            assertEquals("DONE", c.getString(5)); assertEquals("eg2-440m", c.getString(6))
            assertEquals("eg2-440m|mlkit-ko|c1", c.getString(7)); assertEquals(1L, c.getLong(8))
            c.moveToNext()
            assertEquals("FAILED", c.getString(5))
        }
        db.query("SELECT type, ordinal, text, embedding FROM segments ORDER BY ordinal").use { c ->
            assertEquals("failed image has no segments", 2, c.count)
            c.moveToFirst()
            assertEquals("IMAGE", c.getString(0))
            val back = Quant.decode(c.getBlob(3))
            for (i in img.indices) assertEquals(img[i], back[i], 0.01f)
            c.moveToNext()
            assertEquals("OCR_TEXT", c.getString(0)); assertEquals("hello 영수증", c.getString(2))
            assertEquals(1f, Quant.decode(c.getBlob(3))[2], 0.01f)
        }
        db.query("SELECT recursive FROM indexed_folders").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT name FROM sqlite_master WHERE name = 'images'").use { assertEquals("old table dropped", 0, it.count) }
    }
    private val itemV2 = """INSERT INTO items (sourceKey, kind, uri, folderKey, displayName, mimeType, sizeBytes, modifiedAt, title, durationMs,
        pageCount, width, height, dateTaken, latitude, longitude, status, error, embeddingModelId, pipelineKey, progress, indexedAt)
        VALUES ('ms:image:7', 'IMAGE', 'content://m/7', 'Pictures/', 'dubai.jpg', 'image/jpeg', 10, 1, NULL, NULL, NULL, 10, 10, 5,
        25.2, 55.3, 'DONE', NULL, 'eg2-440m', 'eg2-440m|mlkit-ko|c1', 1, 1)"""

    @Test fun v2ToV3AddsAnEmptyPlaceColumnAndKeepsRows() {
        helper.createDatabase("mig23", 2).apply { execSQL(itemV2); close() }
        val db = helper.runMigrationsAndValidate("mig23", 3, true, MIGRATION_2_3)
        db.query("SELECT displayName, latitude, place FROM items").use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("dubai.jpg", c.getString(0)); assertEquals(25.2, c.getDouble(1), 0.0001)
            assertTrue("existing rows get place = NULL until the backfill runs", c.isNull(2))
        }
    }

    /** Fails when an entity changes without a migration: every exported schema must reach the latest version. */
    @Test fun everyExportedSchemaMigratesToTheLatestVersion() {
        for (from in 2 until AppDatabase.VERSION) {
            val name = "chain$from"
            helper.createDatabase(name, from).apply { execSQL(itemV2); close() }
            helper.runMigrationsAndValidate(name, AppDatabase.VERSION, true, *ALL_MIGRATIONS).close()
        }
        helper.createDatabase("chain1", 1).close()
        helper.runMigrationsAndValidate("chain1", AppDatabase.VERSION, true, *ALL_MIGRATIONS).close()
    }
}
