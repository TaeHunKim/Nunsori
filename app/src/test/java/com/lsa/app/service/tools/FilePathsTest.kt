package com.lsa.app.service.tools

import com.lsa.app.data.media.FolderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePathsTest {
    @Test fun idsAreParsedFromCommaListsAndArrays() {
        assertEquals(listOf(12L, 15L, 18L), FilePaths.parseIds("12, 15;18"))
        assertEquals(listOf(1L, 2L), FilePaths.parseIds("[1,2,2]"))
        assertEquals(emptyList<Long>(), FilePaths.parseIds("none"))
        assertEquals(FilePaths.MAX_FILES, FilePaths.parseIds((1..80).joinToString(",")).size)
    }

    @Test fun destinationsMustBeSafeRelativeFolders() {
        assertEquals("Pictures/Wedding", FilePaths.safeDestination(" /Pictures//Wedding/ "))
        assertEquals("Documents", FilePaths.safeDestination("Documents"))
        for (bad in listOf("", "  ", "/", "../x", "a/../b", ".trash", "a/.hidden", "Android/data", "android", "/storage/emulated/0/x", "sdcard/x")) {
            assertNull(bad, FilePaths.safeDestination(bad))
        }
    }

    @Test fun clashingNamesGetANumberBeforeTheExtension() {
        val taken = setOf("a.txt", "a (1).txt", "noext")
        assertEquals("b.txt", FilePaths.uniqueName("b.txt") { it in taken })
        assertEquals("a (2).txt", FilePaths.uniqueName("a.txt") { it in taken })
        assertEquals("noext (1)", FilePaths.uniqueName("noext") { it in taken })
    }

    @Test fun indexedFoldersMatchExactlyOrRecursively() {
        val folders = listOf(FolderSpec("Documents/", true), FolderSpec("Pictures/Shots", false))
        assertTrue(FilePaths.isIndexed("Documents/", folders))
        assertTrue(FilePaths.isIndexed("Documents/Taxes/", folders))
        assertTrue(FilePaths.isIndexed("Pictures/Shots/", folders))
        assertFalse(FilePaths.isIndexed("Pictures/Shots/Sub/", folders))
        assertFalse(FilePaths.isIndexed("Archive/", folders))
        assertTrue(FilePaths.isIndexed("Anything/", listOf(FolderSpec("", true))))
    }
}
