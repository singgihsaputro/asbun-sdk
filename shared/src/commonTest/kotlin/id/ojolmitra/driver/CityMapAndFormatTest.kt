package id.ojolmitra.driver

import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.model.formatKm
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.model.spokenRupiah
import id.ojolmitra.driver.sim.OrderFactory
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CityMapAndFormatTest {

    @Test
    fun routeStartsAndEndsAtPlacesAndFollowsRoads() {
        for (a in CityMap.places) for (b in CityMap.places) {
            if (a == b) continue
            val route = CityMap.route(a.position, b.position)
            assertEquals(a.position, route.first())
            assertEquals(b.position, route.last())
            // setiap segmen harus horizontal atau vertikal (mengikuti grid jalan)
            for (i in 1 until route.size) {
                val p = route[i - 1]
                val q = route[i]
                assertTrue(abs(p.x - q.x) < 0.5f || abs(p.y - q.y) < 0.5f, "segmen diagonal ${a.id}->${b.id}")
            }
            assertTrue(CityMap.pathLength(route) >= a.position.distanceTo(b.position) - 0.5f)
        }
    }

    @Test
    fun placesAreOnRoads() {
        CityMap.places.forEach { place ->
            assertTrue(CityMap.roads.any { it.contains(place.position) }, "${place.name} tidak di jalan")
        }
    }

    @Test
    fun matchLocationFindsRoadsAndPlaces() {
        val here = MapPoint(700f, 820f)
        assertEquals("Jl. Gatot Subroto", CityMap.matchLocation("Gatsu", here)?.label)
        assertEquals("Jl. Jend. Sudirman", CityMap.matchLocation("Jl. Sudirman arah Blok M", here)?.label)
        val station = assertNotNull(CityMap.matchLocation("depan stasiun dukuh atas", here))
        assertTrue(station.isPlace)
        assertEquals("Stasiun Dukuh Atas", station.label)
        assertEquals(null, CityMap.matchLocation("antah berantah", here))
    }

    @Test
    fun generatedOrdersAreConsistent() {
        val factory = OrderFactory(Random(42))
        repeat(50) {
            val order = factory.create(MapPoint(700f, 820f))
            assertTrue(order.fare >= 10_000)
            assertEquals(0, order.fare % 500)
            assertTrue(order.driverEarning in 1..order.fare + order.bonus)
            assertTrue(order.tripDistanceKm > 0.0)
            assertTrue(order.pickup.id != order.dropoff.id)
            assertEquals(order.pickup.position, order.pickupRoute.last())
            assertEquals(order.dropoff.position, order.tripRoute.last())
        }
    }

    @Test
    fun indonesianFormatting() {
        assertEquals("Rp18.500", formatRupiah(18_500))
        assertEquals("Rp1.250.000", formatRupiah(1_250_000))
        assertEquals("Rp0", formatRupiah(0))
        assertEquals("1,2 km", formatKm(1.23))
        assertEquals("18 ribu 500 rupiah", spokenRupiah(18_500))
        assertEquals("1 juta 250 ribu rupiah", spokenRupiah(1_250_000))
        assertEquals("12 ribu rupiah", spokenRupiah(12_000))
    }
}
