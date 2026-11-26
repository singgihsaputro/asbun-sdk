package id.ojolmitra.driver

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun formatClock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

actual fun localMinuteOfDay(epochMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

actual val platformName: String
    get() = "Android ${android.os.Build.VERSION.RELEASE ?: ""}".trim()
