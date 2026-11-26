package id.ojolmitra.driver

import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.model.HotspotLevel
import id.ojolmitra.driver.model.HotspotReason
import id.ojolmitra.driver.model.formatCount
import id.ojolmitra.driver.model.formatTimeOfDay
import id.ojolmitra.driver.model.formatTimeRange
import id.ojolmitra.driver.model.spokenClock
import id.ojolmitra.driver.model.spokenNumber
import id.ojolmitra.driver.model.spokenRupiah
import id.ojolmitra.driver.model.spokenTimeRange
import id.ojolmitra.driver.sim.DemandForecast
import id.ojolmitra.driver.voice.Speech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HotspotForecastTest {
    private val home = DriverController.START_POSITION
    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    // ------------------------------------------------------------ spoken and shown time

    @Test
    fun clockIsSpokenTheWayPeopleSayIt() {
        assertEquals("jam 4 sore", spokenClock(hm(16)))
        assertEquals("jam setengah 5 sore", spokenClock(hm(16, 30)))
        assertEquals("jam 4 lewat 15 sore", spokenClock(hm(16, 15)))
        assertEquals("jam 5 kurang 15 sore", spokenClock(hm(16, 45)))
        assertEquals("jam 12 siang", spokenClock(hm(12)))
        assertEquals("jam setengah 12 malam", spokenClock(hm(23, 30)))
        assertEquals("jam 12 malam", spokenClock(0))
        assertEquals("jam setengah 1 dini hari", spokenClock(hm(0, 30)))
        assertEquals("jam 7 pagi", spokenClock(hm(7)))
        assertEquals("jam 1 dini hari", spokenClock(hm(25)), "lewat tengah malam ikut terbungkus")
    }

    @Test
    fun rangesDropTheRepeatedDayPeriod() {
        assertEquals("jam 4 sore sampai jam 7 malam", spokenTimeRange(hm(16), hm(19)))
        assertEquals("jam setengah 10 sampai jam 11 malam", spokenTimeRange(hm(21, 30), hm(23)))
        assertEquals("jam 8 sampai jam 12 malam", spokenTimeRange(hm(20), hm(24)))
    }

    @Test
    fun shownTimeAndNumbersUseIndonesianSeparators() {
        assertEquals("16.30", formatTimeOfDay(hm(16, 30)))
        assertEquals("00.30", formatTimeOfDay(hm(24, 30)))
        assertEquals("21.30–23.00", formatTimeRange(hm(21, 30), hm(23)))
        assertEquals("3.500", formatCount(3_500))
        assertEquals("3 ribu 500", spokenNumber(3_500))
        assertEquals("420", spokenNumber(420))
        assertEquals("18 ribu 500 rupiah", spokenRupiah(18_500))
    }

    // ------------------------------------------------------------ forecast

    @Test
    fun afternoonForecastIsNearestFirstAndInsideTheWindow() {
        val spots = DemandForecast.forecast(hm(17), home)
        assertTrue(spots.size in 3..DemandForecast.MAX_SPOTS, "${spots.size} area")
        assertEquals(spots.sortedBy { it.distanceKm }, spots, "urut dari yang terdekat")
        assertEquals(spots.size, spots.map { it.name }.toSet().size, "satu tempat muncul sekali")
        spots.forEach { spot ->
            assertTrue(spot.endsInMinutes > 0, "${spot.name} sudah lewat")
            assertTrue(spot.people > 0 && spot.drivers > 0, spot.name)
            assertTrue(spot.distanceKm > 0.0, spot.name)
        }
    }

    @Test
    fun stationIsBusyWhenOfficesEmpty() {
        val station = DemandForecast.forecast(hm(17, 40), home, limit = 50).first { it.name == "Stasiun Dukuh Atas" }
        assertTrue(station.ongoing)
        assertEquals(HotspotReason.PULANG_KANTOR, station.reason)
        assertEquals("16.30–19.30", formatTimeRange(station.startMinute, station.endMinute))
        assertEquals(hm(19, 30) - hm(17, 40), station.endsInMinutes)
    }

    @Test
    fun eventIsListedFromItsStartEvenBeforeTheRideRush() {
        // Konser 19.00-22.00; ramai cari ojek baru 21.30, di luar 3 jam dari 18.00.
        val concert = DemandForecast.forecast(hm(18), home, limit = 50).firstOrNull { it.event?.name == "Konser Musik Senja" }
        assertNotNull(concert)
        assertEquals(HotspotReason.ACARA, concert.reason)
        assertEquals(hm(21, 30) - hm(18), concert.startsInMinutes)
        assertTrue(!concert.ongoing)
        assertEquals("19.00–22.00", formatTimeRange(concert.event!!.startMinute, concert.event!!.endMinute))
    }

    @Test
    fun windowsCrossMidnight() {
        val kemang = DemandForecast.forecast(hm(0, 15), home, limit = 50).first { it.name == "Kafe & Resto Kemang Raya" }
        assertTrue(kemang.ongoing)
        assertEquals("01.00", formatTimeOfDay(kemang.endMinute))
        assertEquals(45, kemang.endsInMinutes)
    }

    @Test
    fun quietHoursLookFurtherAhead() {
        assertTrue(DemandForecast.forecast(hm(2), home).size >= 3, "dini hari tetap ada beberapa area")
    }

    @Test
    fun sameTimeAndPlaceGiveTheSameForecast() {
        assertEquals(DemandForecast.forecast(hm(12, 5), home), DemandForecast.forecast(hm(12, 5), home))
    }

    @Test
    fun levelFollowsPeoplePerDriver() {
        DemandForecast.forecast(hm(18), home, limit = 50).forEach { spot ->
            val expected = when {
                spot.people / spot.drivers >= 18 -> HotspotLevel.SANGAT_RAMAI
                spot.people / spot.drivers >= 11 -> HotspotLevel.RAMAI
                else -> HotspotLevel.SEDANG
            }
            assertEquals(expected, spot.level, spot.name)
        }
    }

    // ------------------------------------------------------------ spoken list

    @Test
    fun listReadsThreeAtATimeWithNumbers() {
        val spots = DemandForecast.forecast(hm(17, 40), home)
        val first = Speech.hotspotList(spots)
        assertTrue(first.startsWith("Ada ${spots.size} area ramai di sekitarmu. Yang terdekat: Nomor 1, "), first)
        assertTrue(first.contains("Nomor 1, ") && first.contains("Nomor 3, ") && !first.contains("Nomor 4, "), first)
        assertTrue(first.endsWith("Ucapkan berikutnya untuk area lain, atau detail nomor 1 untuk info lengkap."), first)
        val next = Speech.hotspotList(spots, from = 3)
        assertTrue(next.startsWith("Berikutnya: Nomor 4, "), next)
        assertTrue(!first.contains("Jl."), "TTS reads 'jalan', not 'Jl.'")
        assertEquals("Itu semua area ramai di sekitarmu.", Speech.hotspotList(spots, from = spots.size))
    }

    @Test
    fun levelsAreMixedNotAllVeryBusy() {
        val levels = listOf(hm(7, 30), hm(12), hm(17, 40), hm(20)).flatMap { t ->
            DemandForecast.forecast(t, home, limit = 50).map { it.level }
        }.toSet()
        assertEquals(HotspotLevel.entries.toSet(), levels)
    }

    @Test
    fun eventNamesAreCleanedForSpeech() {
        val bazar = DemandForecast.forecast(hm(18), home, limit = 50).first { it.event?.name == "Bazar UMKM & Kuliner" }
        val text = Speech.hotspotBrief(bazar, 1) + Speech.hotspotDetail(bazar, 1) + Speech.hotspotEvents(listOf(bazar))
        assertTrue(!text.contains("&"), text)
        assertTrue(text.contains("Bazar UMKM dan Kuliner"), text)
    }

    @Test
    fun detailSaysEventCrowdAndAdvice() {
        val concert = DemandForecast.forecast(hm(21), home, limit = 50).first { it.event != null && it.name == "Hutan Kota Senayan" }
        val text = Speech.hotspotDetail(concert, 2)
        assertTrue(text.startsWith("Nomor 2, Hutan Kota Senayan, dekat jalan "), text)
        assertTrue(text.contains("Ada Konser Musik Senja jam 7 sampai jam 10 malam, sekitar 3 ribu 500 pengunjung."), text)
        assertTrue(text.contains("Ramai cari ojek jam setengah 10 sampai jam 11 malam, 30 menit lagi."), text)
        assertTrue(text.contains("calon penumpang") && text.contains("orang per driver"), text)
    }
}
