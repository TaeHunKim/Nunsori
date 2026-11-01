package com.lsa.app

import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.media.FileSource
import com.lsa.app.data.media.FolderSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class FileSourceTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("fs").toFile()
        fun f(path: String, text: String = "x") = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        f("Documents/a.txt"); f("Documents/b.md"); f("Documents/c.png"); f("Documents/deep/d.csv"); f("Documents/.hidden/e.txt")
        f("Documents/deep/er/f.TXT"); f("Android/data/g.txt"); f("Other/h.txt"); f("Documents/doc.pdf")
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun names(folders: List<FolderSpec>, kinds: Set<ItemKind> = setOf(ItemKind.TEXT)) =
        runBlocking { FileSource(root).list(folders, kinds).map { it.displayName }.sorted() }

    @Test fun recursiveWalkFindsTextFilesOnlyAndSkipsHidden() =
        assertEquals(listOf("a.txt", "b.md", "d.csv", "f.TXT"), names(listOf(FolderSpec("Documents/"))))

    @Test fun nonRecursiveStaysInTheFolder() =
        assertEquals(listOf("a.txt", "b.md"), names(listOf(FolderSpec("Documents/", recursive = false))))

    @Test fun documentKindIsOnlyListedWhenRequested() {
        assertEquals(listOf("doc.pdf"), names(listOf(FolderSpec("Documents/", recursive = false)), setOf(ItemKind.DOC)))
    }

    @Test fun entriesCarryStableKeysFoldersAndTimes() = runBlocking {
        val e = FileSource(root).list(listOf(FolderSpec("Documents/")), setOf(ItemKind.TEXT)).first { it.displayName == "d.csv" }
        assertEquals("fs:${File(root, "Documents/deep/d.csv").absolutePath}", e.sourceKey)
        assertEquals("Documents/deep/", e.folderKey)
        assertEquals(ItemKind.TEXT, e.kind); assertTrue(e.modifiedAt > 0); assertEquals(1L, e.sizeBytes)
    }

    @Test fun overlappingSelectionsDoNotDuplicate() =
        assertEquals(4, names(listOf(FolderSpec("Documents/"), FolderSpec("Documents/deep/"))).size)

    @Test fun nothingIsListedWithoutPermissionOrKinds() = runBlocking {
        assertTrue(FileSource(root, allowed = { false }).list(listOf(FolderSpec("Documents/")), setOf(ItemKind.TEXT)).isEmpty())
        assertTrue(FileSource(root).list(listOf(FolderSpec("Documents/")), setOf(ItemKind.IMAGE)).isEmpty())
    }

    @Test fun androidDirectoryAtTheRootIsSkipped() =
        assertTrue(names(listOf(FolderSpec(""))).none { it == "g.txt" })
}
