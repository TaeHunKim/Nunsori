package com.lsa.app.indexing

import com.lsa.app.indexing.places.PlaceFinder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class PlaceFinderTest {
    private val small = PlaceFinder.parse(
        sequenceOf(
            "25.07725\t55.30927\t3790000\tDubai\t두바이\tUnited Arab Emirates\t아랍에미리트 연합\tAsia 아시아 UAE Middle East 중동",
            "25.0\t55.06\t5000\tJebel Ali\t\tUnited Arab Emirates\t아랍에미리트 연합",
            "37.566\t126.9784\t10349312\tSeoul\t서울특별시\tSouth Korea\t대한민국",
            "64.1\t-21.9\t130000\tReykjavik\t레이캬비크\tIceland\t아이슬란드",
            "-16.5\t179.9\t30000\tEastern\t\tFiji\t피지",
            "-16.5\t-179.9\t30000\tWestern\t\tFiji\t피지",
        ),
    )

    @Test fun findsTheNearestCityInBothLanguages() {
        val p = small.find(37.55, 127.0)!!
        assertTrue(p, "Seoul" in p && "서울특별시" in p && "South Korea" in p && "대한민국" in p)
    }

    @Test fun aSuburbAlsoGetsTheBigCityNearby() {
        // Closest to Jebel Ali, but Dubai (a big city) is within 50 km.
        val p = small.find(25.02, 55.10)!!
        assertTrue(p, "Jebel Ali" in p && "Dubai" in p && "두바이" in p)
    }

    @Test fun aliasesMakeCountriesAndRegionsSearchable() {
        val p = small.find(25.2, 55.27)!!
        assertTrue(p, "UAE" in p && "Middle East" in p && "중동" in p)
        assertTrue("rows without the alias column still parse", "Seoul" in small.find(37.55, 127.0)!!)
    }

    @Test fun farFromAnyCityThereIsNoPlace() {
        assertNull(small.find(0.0, -30.0)) // mid Atlantic
        assertNull(small.find(95.0, 10.0)) // invalid latitude
    }

    @Test fun highLatitudeAndTheDateLineAreHandled() {
        assertTrue("Reykjavik" in small.find(64.15, -21.8)!!)
        assertNotNull("across the date line from the west", small.find(-16.5, -179.95))
        assertNotNull("across the date line from the east", small.find(-16.5, 179.95))
    }

    @Test fun bundledDataKnowsDubaiAndSeoul() {
        val real = PlaceFinder.parse(GZIPInputStream(File("src/main/assets/places.bin").inputStream()))
        val dubai = real.find(25.2048, 55.2708)
        assertNotNull(dubai); assertTrue(dubai!!, "Dubai" in dubai && "두바이" in dubai && "중동" in dubai && "UAE" in dubai)
        val seoul = real.find(37.5665, 126.9780)
        assertNotNull(seoul); assertTrue(seoul!!, "Seoul" in seoul)
        assertNull(real.find(-60.0, -120.0)) // South Pacific
    }
}
