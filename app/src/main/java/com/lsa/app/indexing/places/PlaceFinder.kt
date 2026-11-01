package com.lsa.app.indexing.places

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline reverse geocoder: finds the nearest city (GeoNames cities15000, CC BY 4.0, see scripts/make-places.py)
 * and returns its searchable names in English and Korean. No network, no Android geocoder.
 */
class PlaceFinder private constructor(private val rows: List<Row>) {
    class Row(
        val lat: Double, val lon: Double, val population: Long,
        val cityEn: String, val cityKo: String, val countryEn: String, val countryKo: String,
        /** Extra words for the country: abbreviations, continent and region names (e.g. "UAE Middle East 중동"). */
        val aliases: String = "",
    )

    /** 1 degree cells (latitude row, wrapped longitude column) -> row indexes. */
    private val grid: Map<Long, IntArray> = HashMap<Long, MutableList<Int>>().also { m ->
        rows.forEachIndexed { i, r -> m.getOrPut(cell(r.lat, r.lon)) { ArrayList() } += i }
    }.mapValues { it.value.toIntArray() }

    /**
     * Names to search for a photo taken at [lat], [lon]: the nearest city within [MAX_KM], plus the largest big city
     * within [BIG_CITY_KM] (a suburb of Dubai should also be found by "Dubai"). Null when nothing is near.
     */
    fun find(lat: Double, lon: Double): String? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val latCell = floor(lat).toInt()
        val lonSpan = (1.0 / cos(lat * PI / 180).coerceAtLeast(0.02)).toInt().coerceIn(1, 60) + 1
        var nearest: Row? = null; var nearestKm = Double.MAX_VALUE
        var big: Row? = null
        for (dLat in -2..2) for (dLon in -lonSpan..lonSpan) {
            val ids = grid[key(latCell + dLat, wrap(floor(lon).toInt() + dLon))] ?: continue
            for (i in ids) {
                val r = rows[i]
                val km = distanceKm(lat, lon, r.lat, r.lon)
                if (km < nearestKm) { nearest = r; nearestKm = km }
                if (km <= BIG_CITY_KM && r.population >= BIG_CITY_POPULATION && (big == null || r.population > big.population)) big = r
            }
        }
        if (nearest == null || nearestKm > MAX_KM) return null
        return listOfNotNull(nearest, big).distinct().flatMap { names(it) }.distinct().joinToString(" ")
    }

    private fun names(r: Row) = listOf(r.cityEn, r.cityKo, r.countryEn, r.countryKo, r.aliases).filter { it.isNotBlank() }

    companion object {
        const val MAX_KM = 100.0
        const val BIG_CITY_KM = 50.0
        const val BIG_CITY_POPULATION = 500_000L

        private fun wrap(lonCell: Int) = Math.floorMod(lonCell + 180, 360) - 180
        private fun key(latCell: Int, lonCell: Int) = latCell * 1000L + lonCell
        private fun cell(lat: Double, lon: Double) = key(floor(lat).toInt(), wrap(floor(lon).toInt()))

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
            val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
            val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
            return 2 * 6371.0 * asin(sqrt(a))
        }

        /** Parses rows `lat \t lon \t population \t cityEn \t cityKo \t countryEn \t countryKo [\t aliases]`. */
        fun parse(lines: Sequence<String>): PlaceFinder = PlaceFinder(
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) return@mapNotNull null
                val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
                val lon = p[1].toDoubleOrNull() ?: return@mapNotNull null
                Row(lat, lon, p[2].toLongOrNull() ?: 0, p[3], p[4], p[5], p[6], p.getOrElse(7) { "" })
            }.toList(),
        )

        fun parse(stream: InputStream): PlaceFinder =
            stream.bufferedReader(Charsets.UTF_8).use { parse(it.lineSequence()) }

        /**
         * Loads the bundled `places.bin` (gzip-compressed TSV). The file is deliberately not called `*.gz`: the Android Gradle
         * plugin decompresses such assets and drops the extension, so `assets.open("places.tsv.gz")` fails at runtime.
         */
        fun fromAssets(context: Context): PlaceFinder =
            context.assets.open("places.bin").use { parse(GZIPInputStream(it)) }
    }
}
