package id.ojolmitra.driver.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Titik di "dunia" peta simulasi. 1 unit = 5 meter. */
data class MapPoint(val x: Float, val y: Float) {
    fun distanceTo(other: MapPoint): Float {
        val dx = other.x - x
        val dy = other.y - y
        return sqrt(dx * dx + dy * dy)
    }

    fun lerp(other: MapPoint, t: Float): MapPoint = MapPoint(x + (other.x - x) * t, y + (other.y - y) * t)

    /** Arah dalam derajat (0 = kanan, 90 = bawah) menuju [other]. */
    fun headingTo(other: MapPoint): Float =
        (atan2((other.y - y).toDouble(), (other.x - x).toDouble()) * 180.0 / kotlin.math.PI).toFloat()
}

data class MapRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: MapPoint get() = MapPoint((left + right) / 2f, (top + bottom) / 2f)
}

data class Road(
    val name: String,
    val vertical: Boolean,
    /** x untuk jalan vertikal, y untuk jalan horizontal. */
    val coord: Float,
    val major: Boolean,
    /** Kata kunci untuk mencocokkan ucapan, mis. "gatsu" -> Jl. Gatot Subroto. */
    val keywords: List<String>,
) {
    fun nearestPoint(p: MapPoint): MapPoint =
        if (vertical) MapPoint(coord, p.y.coerceIn(CityMap.MIN, CityMap.MAX))
        else MapPoint(p.x.coerceIn(CityMap.MIN, CityMap.MAX), coord)

    fun contains(p: MapPoint): Boolean = if (vertical) abs(p.x - coord) < 0.5f else abs(p.y - coord) < 0.5f
}

enum class PlaceKind { STATION, MALL, OFFICE, RESIDENTIAL, CAMPUS, HOSPITAL, MARKET, HOTEL, SCHOOL, RESTAURANT }

data class Place(
    val id: String,
    val name: String,
    val address: String,
    val position: MapPoint,
    val kind: PlaceKind,
)

data class Park(val name: String, val area: MapRect)

data class LocationMatch(val label: String, val position: MapPoint, val isPlace: Boolean)

/**
 * Peta kota fiktif berbentuk grid (terinspirasi Jakarta Selatan) yang dipakai
 * untuk simulasi: rute, posisi driver, titik jemput/tujuan, dan laporan lalu lintas.
 * Semua murni Kotlin common sehingga sama persis di Android dan iOS.
 */
object CityMap {
    const val SIZE = 2000f
    const val MIN = 100f
    const val MAX = 1900f
    const val METERS_PER_UNIT = 5f

    val gridLines: List<Float> = listOf(100f, 400f, 700f, 1000f, 1300f, 1600f, 1900f)

    val roads: List<Road> = listOf(
        Road("Jl. Letjen S. Parman", vertical = true, coord = 100f, major = false, keywords = listOf("parman", "s parman", "slipi")),
        Road("Jl. Palmerah Barat", vertical = true, coord = 400f, major = false, keywords = listOf("palmerah")),
        Road("Jl. Jend. Sudirman", vertical = true, coord = 700f, major = true, keywords = listOf("sudirman")),
        Road("Jl. HR Rasuna Said", vertical = true, coord = 1000f, major = true, keywords = listOf("rasuna", "rasuna said", "said")),
        Road("Jl. Kuningan Barat", vertical = true, coord = 1300f, major = false, keywords = listOf("kuningan")),
        Road("Jl. Tendean", vertical = true, coord = 1600f, major = false, keywords = listOf("tendean")),
        Road("Jl. Mampang Prapatan", vertical = true, coord = 1900f, major = false, keywords = listOf("mampang", "prapatan")),
        Road("Jl. Kebon Sirih", vertical = false, coord = 100f, major = false, keywords = listOf("kebon sirih", "sirih")),
        Road("Jl. KH Mas Mansyur", vertical = false, coord = 400f, major = false, keywords = listOf("mas mansyur", "mansyur", "mansur")),
        Road("Jl. Gatot Subroto", vertical = false, coord = 700f, major = true, keywords = listOf("gatot subroto", "gatot", "gatsu", "subroto")),
        Road("Jl. Casablanca", vertical = false, coord = 1000f, major = true, keywords = listOf("casablanca", "kasablanka", "casablanka", "kasablanca")),
        Road("Jl. Senopati", vertical = false, coord = 1300f, major = false, keywords = listOf("senopati")),
        Road("Jl. Wolter Monginsidi", vertical = false, coord = 1600f, major = false, keywords = listOf("wolter", "monginsidi")),
        Road("Jl. Kemang Raya", vertical = false, coord = 1900f, major = false, keywords = listOf("kemang")),
    )

    private fun v(index: Int) = roads[index]
    private fun h(index: Int) = roads[7 + index]

    private fun onV(i: Int, y: Float) = MapPoint(v(i).coord, y)
    private fun onH(i: Int, x: Float) = MapPoint(x, h(i).coord)

    val places: List<Place> = listOf(
        Place("stasiun", "Stasiun Dukuh Atas", "Jl. Jend. Sudirman, Dukuh Atas", onV(2, 220f), PlaceKind.STATION),
        Place("mal-senayan", "Mal Senayan Indah", "Jl. Gatot Subroto No. 12", onH(2, 520f), PlaceKind.MALL),
        Place("menara", "Menara Sudirman Office", "Jl. Jend. Sudirman Kav. 45", onV(2, 560f), PlaceKind.OFFICE),
        Place("taman-rasuna", "Apartemen Taman Rasuna", "Jl. HR Rasuna Said Blok C", onV(3, 1150f), PlaceKind.RESIDENTIAL),
        Place("rs-kuningan", "RS Medika Kuningan", "Jl. Kuningan Barat No. 8", onV(4, 820f), PlaceKind.HOSPITAL),
        Place("kampus", "Kampus Atma Bakti", "Jl. Jend. Sudirman No. 51", onV(2, 880f), PlaceKind.CAMPUS),
        Place("pasar-mampang", "Pasar Mampang", "Jl. Mampang Prapatan Raya", onV(6, 1450f), PlaceKind.MARKET),
        Place("mal-kasablanka", "Mal Kasablanka", "Jl. Casablanca Kav. 88", onH(3, 1150f), PlaceKind.MALL),
        Place("kemang-village", "Kemang Village Residence", "Jl. Kemang Raya No. 3", onH(6, 1500f), PlaceKind.RESIDENTIAL),
        Place("palmerah-indah", "Perumahan Palmerah Indah", "Jl. Palmerah Barat Gg. 5", onV(1, 1450f), PlaceKind.RESIDENTIAL),
        Place("kantor-pajak", "Kantor Pajak Tendean", "Jl. Tendean No. 20", onV(5, 520f), PlaceKind.OFFICE),
        Place("kos-melati", "Kos Melati Senopati", "Jl. Senopati No. 17", onH(4, 850f), PlaceKind.RESIDENTIAL),
        Place("hotel-sirih", "Hotel Kebon Sirih", "Jl. Kebon Sirih No. 33", onH(0, 600f), PlaceKind.HOTEL),
        Place("sma-bina", "SMA Bina Bangsa", "Jl. Wolter Monginsidi No. 9", onH(5, 1150f), PlaceKind.SCHOOL),
        Place("mansyur-tower", "Mas Mansyur Tower", "Jl. KH Mas Mansyur Kav. 2", onH(1, 1450f), PlaceKind.OFFICE),
        Place("slipi-jaya", "Plaza Slipi Jaya", "Jl. Letjen S. Parman Kav. 17", onV(0, 600f), PlaceKind.MALL),
        Place("kemang-asri", "Apartemen Kemang Asri", "Jl. Mampang Prapatan No. 71", onV(6, 1800f), PlaceKind.RESIDENTIAL),
        Place("cluster-wolter", "Cluster Wolter Residence", "Jl. Wolter Monginsidi No. 40", onH(5, 1750f), PlaceKind.RESIDENTIAL),
        // Resto untuk order Food
        Place("bakmi", "Bakmi Ayam Pak Kumis", "Jl. Senopati No. 5", onH(4, 550f), PlaceKind.RESTAURANT),
        Place("nasgor", "Nasi Goreng Kambing Bang Jali", "Jl. KH Mas Mansyur No. 21", onH(1, 850f), PlaceKind.RESTAURANT),
        Place("martabak", "Martabak Bangka Asoy", "Jl. HR Rasuna Said No. 14", onV(3, 520f), PlaceKind.RESTAURANT),
        Place("geprek", "Ayam Geprek Juara", "Jl. Kuningan Barat No. 30", onV(4, 1450f), PlaceKind.RESTAURANT),
        Place("soto", "Soto Betawi Bang Udin", "Jl. Gatot Subroto No. 77", onH(2, 1750f), PlaceKind.RESTAURANT),
        Place("kopi", "Kopi Susu Senja", "Jl. Palmerah Barat No. 2", onV(1, 850f), PlaceKind.RESTAURANT),
        Place("sate", "Sate Padang Uda Rizal", "Jl. Kemang Raya No. 15", onH(6, 850f), PlaceKind.RESTAURANT),
        Place("warteg", "Warteg Bahari Rasa", "Jl. Tendean No. 4", onV(5, 1150f), PlaceKind.RESTAURANT),
    )

    private fun cell(col: Int, row: Int) = MapRect(gridLines[col], gridLines[row], gridLines[col + 1], gridLines[row + 1])

    val parks: List<Park> = listOf(
        Park("Hutan Kota Senayan", cell(0, 2)),
        Park("Taman Suropati", cell(3, 1)),
        Park("Taman Langsat", cell(4, 4)),
        Park("Taman Ayodya", cell(1, 5)),
    )

    /** Sungai yang berkelok melintasi kota (dekoratif). */
    val river: List<MapPoint> = (0..15).map { i ->
        val x = -50f + i * 140f
        MapPoint(x, 1150f + 55f * sin(x / 230f))
    }

    /** Bangunan dekoratif per blok, deterministik supaya peta selalu sama. */
    val buildings: List<MapRect> = buildList {
        for (col in 0 until gridLines.size - 1) {
            for (row in 0 until gridLines.size - 1) {
                val block = cell(col, row)
                if (parks.any { it.area == block }) continue
                val rnd = Random(col * 31 + row * 7 + 3)
                val inset = 22f
                val count = 3 + rnd.nextInt(3)
                repeat(count) {
                    val w = 50f + rnd.nextFloat() * 80f
                    val hgt = 40f + rnd.nextFloat() * 80f
                    val left = block.left + inset + rnd.nextFloat() * (block.right - block.left - 2 * inset - w)
                    val top = block.top + inset + rnd.nextFloat() * (block.bottom - block.top - 2 * inset - hgt)
                    add(MapRect(left, top, left + w, top + hgt))
                }
            }
        }
    }

    val restaurants: List<Place> get() = places.filter { it.kind == PlaceKind.RESTAURANT }
    val destinations: List<Place> get() = places.filter { it.kind != PlaceKind.RESTAURANT }

    fun unitsToKm(units: Float): Double = units * METERS_PER_UNIT / 1000.0

    fun pathLength(path: List<MapPoint>): Float {
        var total = 0f
        for (i in 1 until path.size) total += path[i - 1].distanceTo(path[i])
        return total
    }

    fun snapToRoad(p: MapPoint): MapPoint = roads.map { it.nearestPoint(p) }.minBy { it.distanceTo(p) }

    fun roadAt(p: MapPoint): Road {
        val snapped = snapToRoad(p)
        return roads.minBy { it.nearestPoint(snapped).distanceTo(snapped) }
    }

    /**
     * Rute terpendek di jaringan jalan (Dijkstra pada simpul persimpangan),
     * dengan jalan utama sedikit lebih disukai.
     */
    fun route(from: MapPoint, to: MapPoint): List<MapPoint> {
        val start = snapToRoad(from)
        val end = snapToRoad(to)
        if (start.distanceTo(end) < 1f) return listOf(start, end)

        val nodes = mutableListOf<MapPoint>()
        for (x in gridLines) for (y in gridLines) nodes += MapPoint(x, y)
        val startIndex = nodes.size.also { nodes += start }
        val endIndex = nodes.size.also { nodes += end }

        val edges = Array(nodes.size) { mutableListOf<Pair<Int, Float>>() }
        for (road in roads) {
            val onRoad = nodes.indices.filter { road.contains(nodes[it]) }
                .sortedBy { if (road.vertical) nodes[it].y else nodes[it].x }
            val factor = if (road.major) 0.85f else 1f
            for (k in 1 until onRoad.size) {
                val a = onRoad[k - 1]
                val b = onRoad[k]
                val w = nodes[a].distanceTo(nodes[b]) * factor
                edges[a] += b to w
                edges[b] += a to w
            }
        }

        val dist = FloatArray(nodes.size) { Float.MAX_VALUE }
        val prev = IntArray(nodes.size) { -1 }
        val done = BooleanArray(nodes.size)
        dist[startIndex] = 0f
        repeat(nodes.size) {
            var u = -1
            for (i in nodes.indices) if (!done[i] && (u == -1 || dist[i] < dist[u])) u = i
            if (u == -1 || dist[u] == Float.MAX_VALUE) return@repeat
            done[u] = true
            for ((nb, w) in edges[u]) {
                if (dist[u] + w < dist[nb]) {
                    dist[nb] = dist[u] + w
                    prev[nb] = u
                }
            }
        }

        val path = ArrayDeque<MapPoint>()
        var cur = endIndex
        while (cur != -1) {
            path.addFirst(nodes[cur])
            cur = prev[cur]
        }
        if (path.firstOrNull() != start) return listOf(start, end)
        return simplify(path.toList())
    }

    /** Buang titik duplikat & titik tengah yang segaris. */
    private fun simplify(points: List<MapPoint>): List<MapPoint> {
        val dedup = points.fold(mutableListOf<MapPoint>()) { acc, p ->
            if (acc.isEmpty() || acc.last().distanceTo(p) > 0.5f) acc += p
            acc
        }
        if (dedup.size < 3) return dedup
        val result = mutableListOf(dedup.first())
        for (i in 1 until dedup.size - 1) {
            val a = result.last()
            val b = dedup[i]
            val c = dedup[i + 1]
            val collinear = (abs(a.x - b.x) < 0.5f && abs(b.x - c.x) < 0.5f) || (abs(a.y - b.y) < 0.5f && abs(b.y - c.y) < 0.5f)
            if (!collinear) result += b
        }
        result += dedup.last()
        return result
    }

    /**
     * Cocokkan teks lokasi hasil ucapan ("jalan sudirman arah blok m", "stasiun dukuh atas")
     * ke tempat atau jalan di peta. Tempat lebih spesifik sehingga dicek lebih dulu.
     */
    fun matchLocation(text: String, near: MapPoint): LocationMatch? {
        val normalized = " " + text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotBlank() }.joinToString(" ") + " "
        if (normalized.isBlank()) return null

        val place = places.firstOrNull { place ->
            val words = place.name.lowercase().split(' ').filter { it.length > 2 && it !in GENERIC_WORDS }
            words.isNotEmpty() && words.count { normalized.contains(" $it ") } >= minOf(2, words.size)
        }
        if (place != null) return LocationMatch(place.name, place.position, isPlace = true)

        val road = roads.firstOrNull { road -> road.keywords.any { normalized.contains(" $it ") } }
        if (road != null) return LocationMatch(road.name, road.nearestPoint(near), isPlace = false)
        return null
    }

    private val GENERIC_WORDS = setOf("jalan", "the", "dan", "bang", "pak", "jl.")
}
