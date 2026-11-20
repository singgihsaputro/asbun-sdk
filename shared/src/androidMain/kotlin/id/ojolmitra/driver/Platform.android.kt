package id.ojolmitra.driver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun formatClock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

actual val platformName: String
    get() = "Android ${android.os.Build.VERSION.RELEASE ?: ""}".trim()
