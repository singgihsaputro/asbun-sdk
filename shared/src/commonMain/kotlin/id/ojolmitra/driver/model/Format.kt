package id.ojolmitra.driver.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** "Rp18.500" */
fun formatRupiah(amount: Int): String {
    val sign = if (amount < 0) "-" else ""
    val digits = abs(amount).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return "${sign}Rp$grouped"
}

/** "1,2 km" (desimal koma ala Indonesia). */
fun formatKm(km: Double): String {
    val tenths = (km * 10).roundToInt()
    return "${tenths / 10},${tenths % 10} km"
}

/** Ejaan rupiah yang enak dibacakan TTS, mis. "18 ribu 500 rupiah". */
fun spokenRupiah(amount: Int): String {
    val juta = amount / 1_000_000
    val ribu = (amount % 1_000_000) / 1_000
    val sisa = amount % 1_000
    val parts = buildList {
        if (juta > 0) add("$juta juta")
        if (ribu > 0) add("$ribu ribu")
        if (sisa > 0 || isEmpty()) add("$sisa")
    }
    return parts.joinToString(" ") + " rupiah"
}

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
