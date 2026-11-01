package com.lsa.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lsa.app.indexing.BitmapLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SvgDecodeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, text: String) = File(ctx.cacheDir, name).also { it.writeText(text) }

    @Test fun svgIsRenderedInsteadOfFailing() {
        val f = file("t.svg", """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="#ff0000"/></svg>""")
        val bmp = BitmapLoader.decode(ctx, Uri.fromFile(f), 1024)
        assertEquals(1024, bmp.width); assertEquals(512, bmp.height) // long side scaled to maxSide
        assertNotEquals("red rectangle, not blank", android.graphics.Color.WHITE, bmp.getPixel(10, 10))
    }

    @Test fun svgWithViewBoxOnlyGetsASize() {
        val f = file("v.svg", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20"/></svg>""")
        val bmp = BitmapLoader.decode(ctx, Uri.fromFile(f), 256)
        assertEquals(256, bmp.width)
    }

    @Test fun garbageIsNotSvg() {
        val f = file("g.svg", "not an image")
        assertNull(BitmapLoader.decodeSvg(ctx, Uri.fromFile(f), 256))
    }
}
