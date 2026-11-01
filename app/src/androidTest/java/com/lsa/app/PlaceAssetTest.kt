package com.lsa.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.indexing.places.PlaceFinder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled place data must be readable from the installed APK (the build tools rewrite some asset names). */
@RunWith(AndroidJUnit4::class)
class PlaceAssetTest {
    @Test fun bundledPlacesAreReadableOnTheDevice() {
        val finder = PlaceFinder.fromAssets(InstrumentationRegistry.getInstrumentation().targetContext)
        val dubai = finder.find(25.2048, 55.2708)
        assertNotNull(dubai)
        assertTrue(dubai!!, "Dubai" in dubai && "두바이" in dubai && "중동" in dubai)
    }
}
