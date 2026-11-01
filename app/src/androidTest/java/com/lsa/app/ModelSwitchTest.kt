package com.lsa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Switching the embedding model re-embeds every indexed file, so it needs the user's confirmation. */
@RunWith(AndroidJUnit4::class)
class ModelSwitchTest {
    private val container = (ApplicationProvider.getApplicationContext<Context>() as LsaApplication).container
    private val dao get() = container.db.itemDao()
    private lateinit var vm: SettingsViewModel
    private var itemId = 0L

    @Before fun setUp() = runBlocking {
        // The test runs against the app's own database; start from an empty index (the run uninstalls the app afterwards).
        container.db.clearAllTables()
        container.settings.setEmbeddingModelId("eg2-440m")
        vm = SettingsViewModel(container)
    }

    @After fun tearDown() = runBlocking {
        if (itemId != 0L) dao.deleteItems(listOf(itemId))
        container.settings.setEmbeddingModelId("eg2-440m")
    }

    private suspend fun addDoneItem() {
        itemId = dao.insertItem(
            ItemEntity(
                sourceKey = "k:switch", kind = ItemKind.IMAGE, uri = "content://x", folderKey = "F/", displayName = "x.jpg", mimeType = null,
                sizeBytes = 1, modifiedAt = 1, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null,
                latitude = null, longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = "eg2-440m", pipelineKey = "p",
                progress = 0, indexedAt = 1,
            ),
        )
    }

    @Test fun withNothingIndexedTheModelChangesAtOnce() = runBlocking {
        vm.selectEmbedding("eg2-740m").join()
        assertNull(vm.pendingSwitch.value)
        assertEquals("eg2-740m", container.settings.embeddingModelId.first())
    }

    @Test fun withIndexedFilesTheUserMustConfirm() = runBlocking {
        addDoneItem()
        vm.selectEmbedding("eg2-740m").join()
        val pending = vm.pendingSwitch.value
        assertNotNull(pending)
        assertEquals("eg2-740m", pending!!.modelId)
        assertEquals("the model is unchanged until confirmed", "eg2-440m", container.settings.embeddingModelId.first())
        vm.cancelSwitch()
        assertNull(vm.pendingSwitch.value)
        assertEquals("eg2-440m", container.settings.embeddingModelId.first())

        vm.selectEmbedding("eg2-740m").join()
        vm.confirmSwitch().join()
        assertNull(vm.pendingSwitch.value)
        assertEquals("eg2-740m", container.settings.embeddingModelId.first())
        container.indexing.pause() // confirming starts re-indexing; do not leave it running
    }

    @Test fun choosingTheCurrentModelAsksNothing() = runBlocking {
        addDoneItem()
        vm.selectEmbedding("eg2-440m").join()
        assertNull(vm.pendingSwitch.value)
    }
}
