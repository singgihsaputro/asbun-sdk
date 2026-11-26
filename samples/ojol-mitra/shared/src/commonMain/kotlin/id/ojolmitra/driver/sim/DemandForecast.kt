package id.ojolmitra.driver.sim

import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.model.Hotspot
import id.ojolmitra.driver.model.HotspotEvent
import id.ojolmitra.driver.model.HotspotReason
import id.ojolmitra.driver.model.MINUTES_PER_DAY
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Perkiraan area ramai (heat map) untuk simulasi: jadwal harian keramaian di tempat-tempat
 * [CityMap] (stasiun, kantor, mal, kampus, sekolah, pasar, rumah sakit, kuliner) ditambah
 * acara fiktif (konser, bazar, nobar, resepsi). Di aplikasi sungguhan angka ini datang dari
 * backend (riwayat order, driver online, kalender acara); di sini dihitung di perangkat.
 *
 * Murni fungsi: hasilnya hanya bergantung pada jam dan posisi, jadi layar dan suara selalu sama.
 */
object DemandForecast {
    /** Seberapa jauh ke depan perkiraannya. */
    const val HORIZON_MINUTES = 180
    const val MAX_SPOTS = 8

    /**
     * Area yang ramai sekarang atau dalam [horizonMinutes] ke depan, urut dari yang terdekat ke [from].
     * Bila terlalu sedikit (mis. dini hari), jangkauannya diperpanjang sampai 6 jam.
     */
    fun forecast(
        nowMinute: Int,
        from: MapPoint,
        horizonMinutes: Int = HORIZON_MINUTES,
        limit: Int = MAX_SPOTS,
    ): List<Hotspot> {
        val now = ((nowMinute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val spots = collect(now, from, horizonMinutes)
        val enough = if (spots.size < MIN_SPOTS) collect(now, from, horizonMinutes * 2) else spots
        return enough.take(limit)
    }

    private fun collect(now: Int, from: MapPoint, horizon: Int): List<Hotspot> =
        templates.mapNotNull { t -> t.toHotspot(now, from, horizon) }
            // Satu tempat cukup muncul sekali: ambil keramaian yang paling dekat waktunya.
            .groupBy { it.name }.values.map { group -> group.minBy { it.startsInMinutes } }
            .sortedBy { it.distanceKm }

    private fun Template.toHotspot(now: Int, from: MapPoint, horizon: Int): Hotspot? {
        // Jadwal berulang tiap hari: cari salinan kemarin/hari ini/besok yang masuk jendela waktu.
        // Acara sudah terlihat sejak acaranya mulai, walau ramai cari ojeknya baru saat bubaran.
        val offset = listOf(-MINUTES_PER_DAY, 0, MINUTES_PER_DAY)
            .firstOrNull { off -> end + off > now && visibleFrom + off < now + horizon } ?: return null
        val startAt = start + offset
        val endAt = end + offset
        // Angka bergeser sedikit tiap 15 menit, seperti data yang diperbarui.
        val rnd = Random(id.hashCode() * 31 + now / 15)
        val peopleNow = (people * (0.85 + rnd.nextDouble() * 0.3) / 10).roundToInt() * 10
        val driversNow = (drivers * (0.8 + rnd.nextDouble() * 0.4)).roundToInt().coerceAtLeast(1)
        return Hotspot(
            id = id,
            name = name,
            area = CityMap.roadAt(position).name,
            position = position,
            startMinute = startAt.wrap(),
            endMinute = endAt.wrap(),
            startsInMinutes = startAt - now,
            endsInMinutes = endAt - now,
            people = peopleNow,
            drivers = driversNow,
            reason = if (event != null) HotspotReason.ACARA else reason,
            event = event,
            distanceKm = CityMap.unitsToKm(CityMap.pathLength(CityMap.route(from, position))),
        )
    }

    private const val MIN_SPOTS = 3

    private class Template(
        val id: String,
        val name: String,
        val position: MapPoint,
        /** Menit sejak tengah malam; [end] boleh lewat 1440 untuk keramaian yang melewati tengah malam. */
        val start: Int,
        val end: Int,
        val people: Int,
        val drivers: Int,
        val reason: HotspotReason,
        val event: HotspotEvent? = null,
        /** Sejak kapan muncul di perkiraan (jam mulai acara bila ada). */
        val visibleFrom: Int = start,
    )

    private fun place(id: String) = CityMap.places.first { it.id == id }
    private fun park(name: String) = CityMap.parks.first { it.name == name }

    /** "16:30" -> 990; "24:30" -> 1470 (lewat tengah malam). */
    private fun t(hhmm: String): Int = hhmm.substringBefore(':').toInt() * 60 + hhmm.substringAfter(':').toInt()

    private fun Int.wrap(): Int = ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private fun atPlace(
        id: String,
        placeId: String,
        start: String,
        end: String,
        people: Int,
        drivers: Int,
        reason: HotspotReason,
        name: String? = null,
        event: Pair<HotspotEvent, Int>? = null,
    ): Template {
        val p = place(placeId)
        return Template(
            id, name ?: p.name, p.position, t(start), t(end), people, drivers, reason,
            event?.first, visibleFrom = minOf(t(start), event?.second ?: t(start)),
        )
    }

    private fun atPark(id: String, parkName: String, start: String, end: String, people: Int, drivers: Int, event: Pair<HotspotEvent, Int>) =
        Template(
            id, parkName, park(parkName).area.center, t(start), t(end), people, drivers, HotspotReason.ACARA,
            event.first, visibleFrom = minOf(t(start), event.second),
        )

    /** Acara beserta menit mulainya pada hari yang sama dengan jam ramainya. */
    private fun event(name: String, emoji: String, start: String, end: String, attendees: Int): Pair<HotspotEvent, Int> =
        HotspotEvent(name, emoji, t(start).wrap(), t(end).wrap(), attendees) to t(start)

    private val templates: List<Template> by lazy {
        listOf(
            // Pagi
            atPlace("pasar-subuh", "pasar-mampang", "03:00", "08:30", 140, 7, HotspotReason.PASAR),
            atPlace("stasiun-pagi", "stasiun", "04:45", "09:00", 380, 20, HotspotReason.BERANGKAT_KERJA),
            atPlace("sma-pagi", "sma-bina", "06:00", "07:15", 120, 9, HotspotReason.SEKOLAH),
            atPlace("palmerah-pagi", "palmerah-indah", "06:15", "08:00", 110, 6, HotspotReason.BERANGKAT_KERJA),
            atPlace("rasuna-pagi", "taman-rasuna", "06:30", "08:30", 160, 9, HotspotReason.BERANGKAT_KERJA),
            atPlace("kemang-village-pagi", "kemang-village", "06:45", "08:45", 130, 8, HotspotReason.BERANGKAT_KERJA),
            atPlace("rs-pagi", "rs-kuningan", "07:00", "10:30", 95, 10, HotspotReason.RUMAH_SAKIT),
            atPark("acara-lari", "Taman Langsat", "07:30", "09:00", 90, 6, event("Lari Pagi Komunitas", "🏃", "05:30", "08:00", 600)),
            // Siang
            atPlace("kasablanka-siang", "mal-kasablanka", "10:00", "14:00", 150, 16, HotspotReason.BELANJA),
            atPlace(
                "acara-jobfair", "kampus", "11:00", "15:30", 210, 12, HotspotReason.ACARA,
                event = event("Job Fair Atma Bakti", "💼", "09:00", "15:00", 1_800),
            ),
            atPlace("menara-siang", "menara", "11:30", "13:30", 260, 14, HotspotReason.MAKAN_SIANG),
            atPlace("mansyur-siang", "mansyur-tower", "11:30", "13:15", 210, 12, HotspotReason.MAKAN_SIANG),
            atPlace("pajak-siang", "kantor-pajak", "11:45", "13:15", 120, 9, HotspotReason.MAKAN_SIANG),
            atPlace("sma-siang", "sma-bina", "13:45", "15:15", 180, 10, HotspotReason.SEKOLAH),
            // Sore
            atPlace("kampus-sore", "kampus", "15:30", "17:30", 200, 15, HotspotReason.KAMPUS),
            atPlace("rs-sore", "rs-kuningan", "16:00", "19:00", 110, 8, HotspotReason.RUMAH_SAKIT),
            atPlace("pajak-sore", "kantor-pajak", "16:00", "17:30", 140, 8, HotspotReason.PULANG_KANTOR),
            atPlace("stasiun-sore", "stasiun", "16:30", "19:30", 420, 18, HotspotReason.PULANG_KANTOR),
            atPlace("menara-sore", "menara", "16:45", "18:45", 300, 13, HotspotReason.PULANG_KANTOR),
            atPlace("mansyur-sore", "mansyur-tower", "17:00", "19:00", 260, 16, HotspotReason.PULANG_KANTOR),
            atPark("acara-bazar", "Taman Suropati", "17:00", "21:30", 160, 9, event("Bazar UMKM & Kuliner", "🛍️", "15:00", "21:00", 1_200)),
            // Malam
            atPlace("senayan-malam", "mal-senayan", "17:30", "21:30", 320, 30, HotspotReason.BELANJA),
            atPlace("slipi-malam", "slipi-jaya", "18:00", "21:00", 180, 18, HotspotReason.BELANJA),
            atPlace("kuliner-senopati", "bakmi", "18:00", "21:30", 230, 17, HotspotReason.MAKAN_MALAM, name = "Kawasan Kuliner Senopati"),
            atPlace("kuliner-mansyur", "nasgor", "18:30", "22:00", 170, 10, HotspotReason.MAKAN_MALAM, name = "Sentra Kuliner Mas Mansyur"),
            atPlace("kemang-nongkrong", "sate", "20:00", "25:00", 260, 17, HotspotReason.NONGKRONG, name = "Kafe & Resto Kemang Raya"),
            atPlace(
                "acara-nikahan", "hotel-sirih", "20:30", "22:00", 120, 6, HotspotReason.ACARA,
                event = event("Resepsi Pernikahan", "💍", "18:30", "21:30", 800),
            ),
            atPlace("senopati-nongkrong", "kos-melati", "21:00", "24:30", 150, 9, HotspotReason.NONGKRONG, name = "Kafe-kafe Senopati"),
            atPlace(
                "acara-midnight-sale", "mal-kasablanka", "21:30", "24:30", 200, 11, HotspotReason.ACARA,
                event = event("Midnight Sale", "🛒", "20:00", "24:00", 1_500),
            ),
            atPark("acara-konser", "Hutan Kota Senayan", "21:30", "23:00", 420, 20, event("Konser Musik Senja", "🎤", "19:00", "22:00", 3_500)),
            atPark("acara-nobar", "Taman Ayodya", "21:45", "23:15", 150, 8, event("Nobar Final Liga", "⚽", "19:30", "22:00", 900)),
            // Larut malam
            atPlace("warteg-malam", "warteg", "22:00", "27:00", 80, 5, HotspotReason.MAKAN_LARUT, name = "Warteg 24 Jam Tendean"),
            atPlace("rs-shift", "rs-kuningan", "22:30", "24:00", 70, 4, HotspotReason.SHIFT_MALAM),
            atPlace("kemang-asri-shift", "kemang-asri", "23:30", "26:30", 90, 5, HotspotReason.SHIFT_MALAM),
        )
    }
}
