package id.ojolmitra.driver.model

import id.ojolmitra.driver.map.MapPoint
import kotlin.math.max

/** Seberapa ramai sebuah area, dari perbandingan calon penumpang dengan driver di sekitarnya. */
enum class HotspotLevel(val label: String, val spoken: String) {
    SANGAT_RAMAI("Sangat ramai", "sangat ramai"),
    RAMAI("Ramai", "ramai"),
    LUMAYAN("Lumayan", "lumayan ramai"),
}

/** Kenapa sebuah area ramai. [spoken] dirangkai ke kalimat TTS: "ramai karena ...". */
enum class HotspotReason(val emoji: String, val label: String, val spoken: String) {
    BERANGKAT_KERJA("🚉", "Jam berangkat kerja", "jam berangkat kerja"),
    PULANG_KANTOR("🏢", "Jam pulang kantor", "jam pulang kantor"),
    MAKAN_SIANG("🍱", "Jam makan siang", "jam makan siang, banyak order Food"),
    MAKAN_MALAM("🍜", "Jam makan malam", "jam makan malam, banyak order Food"),
    MAKAN_LARUT("🌙", "Cari makan larut malam", "banyak yang cari makan larut malam"),
    SEKOLAH("🎒", "Jam antar-jemput sekolah", "jam antar jemput sekolah"),
    KAMPUS("🎓", "Kuliah bubar", "mahasiswa selesai kuliah"),
    BELANJA("🛍️", "Mal ramai pengunjung", "mal sedang ramai pengunjung"),
    PASAR("🧺", "Pasar subuh & pagi", "pasar sedang ramai"),
    RUMAH_SAKIT("🏥", "Jam besuk & berobat", "jam besuk dan berobat"),
    SHIFT_MALAM("🌃", "Pergantian shift malam", "pekerja shift malam pulang"),
    NONGKRONG("☕", "Nongkrong malam", "banyak yang nongkrong"),
    ACARA("🎉", "Ada acara", "ada acara"),
}

/** Acara yang membuat area ramai. Jam acara bisa beda dari jam ramainya (orang butuh ojek saat bubaran). */
data class HotspotEvent(
    val name: String,
    val emoji: String,
    val startMinute: Int,
    val endMinute: Int,
    val attendees: Int,
)

/**
 * Satu area ramai dalam perkiraan: kapan ramainya, berapa calon penumpang, berapa driver, dan kenapa.
 * Menit dihitung sejak tengah malam waktu lokal; [startsInMinutes] <= 0 berarti sedang berlangsung.
 */
data class Hotspot(
    val id: String,
    val name: String,
    /** Jalan terdekat, untuk subjudul. */
    val area: String,
    val position: MapPoint,
    val startMinute: Int,
    val endMinute: Int,
    val startsInMinutes: Int,
    val endsInMinutes: Int,
    /** Perkiraan calon penumpang dan pemesan di area itu selama jam ramai. */
    val people: Int,
    /** Perkiraan driver online di sekitar area itu. */
    val drivers: Int,
    val reason: HotspotReason,
    val event: HotspotEvent?,
    /** Jarak lewat jalan dari posisi driver. */
    val distanceKm: Double,
) {
    val ongoing: Boolean get() = startsInMinutes <= 0
    val peoplePerDriver: Int get() = people / max(1, drivers)
    val level: HotspotLevel
        get() = when {
            peoplePerDriver >= SANGAT_RAMAI_PER_DRIVER -> HotspotLevel.SANGAT_RAMAI
            peoplePerDriver >= RAMAI_PER_DRIVER -> HotspotLevel.RAMAI
            else -> HotspotLevel.LUMAYAN
        }
    val etaMinutes: Int get() = etaMinutes(distanceKm)

    /** 0..1, untuk warna dan ukuran heat map. */
    val heat: Float get() = (peoplePerDriver / 24f).coerceIn(0.25f, 1f)

    companion object {
        /** Calon penumpang per driver mulai dari sini disebut "Sangat ramai". */
        const val SANGAT_RAMAI_PER_DRIVER = 18
        const val RAMAI_PER_DRIVER = 11
    }
}
