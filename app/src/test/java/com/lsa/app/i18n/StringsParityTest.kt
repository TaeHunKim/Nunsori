package com.lsa.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StringsParityTest {
    private fun keys(path: String): Set<String> {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test fun englishAndKoreanHaveSameKeys() {
        val en = keys("src/main/res/values/strings.xml")
        val ko = keys("src/main/res/values-ko/strings.xml")
        assertEquals("missing in ko", emptySet<String>(), en - ko)
        assertEquals("missing in en", emptySet<String>(), ko - en)
    }
}
