package id.ojolmitra.driver.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** "Rp18.500" */
fun formatRupiah(amount: Int): String {
    val sign = if (amount < 0) "-" else ""
    return "${sign}Rp${groupThousands(abs(amount))}"
}

/** "3.500" (titik pemisah ribuan ala Indonesia). */
fun formatCount(count: Int): String = (if (count < 0) "-" else "") + groupThousands(abs(count))

private fun groupThousands(value: Int): String = value.toString().reversed().chunked(3).joinToString(".").reversed()

/** "1,2 km" (desimal koma ala Indonesia). */
fun formatKm(km: Double): String {
    val tenths = (km * 10).roundToInt()
    return "${tenths / 10},${tenths % 10} km"
}

/** Ejaan rupiah yang enak dibacakan TTS, mis. "18 ribu 500 rupiah". */
fun spokenRupiah(amount: Int): String = spokenNumber(amount) + " rupiah"

/** Angka yang enak dibacakan TTS, mis. 3500 -> "3 ribu 500" (bukan "3.500", yang bisa dibaca "tiga koma lima"). */
fun spokenNumber(value: Int): String {
    val juta = value / 1_000_000
    val ribu = (value % 1_000_000) / 1_000
    val sisa = value % 1_000
    val parts = buildList {
        if (juta > 0) add("$juta juta")
        if (ribu > 0) add("$ribu ribu")
        if (sisa > 0 || isEmpty()) add("$sisa")
    }
    return parts.joinToString(" ")
}

/** Menit sejak tengah malam -> "16.30" (titik pemisah jam ala Indonesia). Lewat tengah malam ikut terbungkus. */
fun formatTimeOfDay(minuteOfDay: Int): String {
    val m = wrapMinute(minuteOfDay)
    return "${(m / 60).toString().padStart(2, '0')}.${(m % 60).toString().padStart(2, '0')}"
}

/** "16.30–19.00" */
fun formatTimeRange(startMinute: Int, endMinute: Int): String = "${formatTimeOfDay(startMinute)}–${formatTimeOfDay(endMinute)}"

/** Bagian hari untuk jam lokal: pagi, siang, sore, malam, dini hari. */
fun dayPeriod(minuteOfDay: Int): String = when (wrapMinute(minuteOfDay) / 60) {
    in 0..3 -> "dini hari"
    in 4..10 -> "pagi"
    in 11..14 -> "siang"
    in 15..17 -> "sore"
    else -> "malam"
}

/**
 * Jam seperti orang mengucapkannya, untuk TTS: 16.00 "jam 4 sore", 16.30 "jam setengah 5 sore",
 * 16.15 "jam 4 lewat 15 sore", 16.45 "jam 5 kurang 15 sore", 00.00 "jam 12 malam".
 * [withPeriod] = false menghilangkan "sore"/"malam" (dipakai di awal rentang waktu).
 */
fun spokenClock(minuteOfDay: Int, withPeriod: Boolean = true): String {
    val m = wrapMinute(minuteOfDay)
    val hour = m / 60
    val minute = m % 60
    fun h12(h: Int) = (h % 12).let { if (it == 0) 12 else it }
    val period = if (m == 0) "malam" else dayPeriod(m)
    val clock = when (minute) {
        0 -> "jam ${h12(hour)}"
        30 -> "jam setengah ${h12(hour + 1)}"
        45 -> "jam ${h12(hour + 1)} kurang 15"
        else -> "jam ${h12(hour)} lewat $minute"
    }
    return if (withPeriod) "$clock $period" else clock
}

/** "jam 4 sampai jam 7 malam", atau "jam 10 malam sampai jam 1 dini hari" bila bagian harinya beda. */
fun spokenTimeRange(startMinute: Int, endMinute: Int): String {
    val samePeriod = dayPeriodSpoken(startMinute) == dayPeriodSpoken(endMinute)
    return "${spokenClock(startMinute, withPeriod = !samePeriod)} sampai ${spokenClock(endMinute)}"
}

private fun dayPeriodSpoken(minuteOfDay: Int): String = if (wrapMinute(minuteOfDay) == 0) "malam" else dayPeriod(minuteOfDay)

/** "40 menit", "1 jam", "1 jam 20 menit" */
fun spokenDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m menit"
        m == 0 -> "$h jam"
        else -> "$h jam $m menit"
    }
}

/** "40 mnt", "1 j 20 mnt" untuk label singkat. */
fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m mnt"
        m == 0 -> "$h jam"
        else -> "$h j $m mnt"
    }
}

private fun wrapMinute(minute: Int): Int = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

const val MINUTES_PER_DAY = 24 * 60

/** "1,2 kilometer" untuk TTS. */
fun spokenKm(km: Double): String = formatKm(km).replace(" km", " kilometer")

/** "Baru saja", "5 mnt lalu", "1 jam lalu". */
fun formatAgo(createdAtMillis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - createdAtMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Baru saja"
        minutes < 60 -> "$minutes mnt lalu"
        else -> "${minutes / 60} jam lalu"
    }
}

/** Huruf besar di awal kalimat, sisanya dibiarkan. */
fun String.capitalizeFirst(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
