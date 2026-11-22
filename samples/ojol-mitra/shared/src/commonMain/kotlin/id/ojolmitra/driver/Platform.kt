package id.ojolmitra.driver

/** Waktu sekarang dalam milidetik sejak epoch (implementasi per platform). */
expect fun epochMillis(): Long

/** Format jam lokal "HH:mm" untuk label chat & laporan. */
expect fun formatClock(epochMillis: Long): String

/** Bunyi pendek: "Halo Mitra" terdengar tanpa perintah, asisten menunggu. */
expect fun playWakeTone()

/** Layar tetap menyala selama [enabled] (driver online, HP di dudukan). */
@androidx.compose.runtime.Composable
expect fun KeepScreenOn(enabled: Boolean)

/** Nama platform, untuk ditampilkan di layar Asisten Suara. */
expect val platformName: String
