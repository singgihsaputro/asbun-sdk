package id.ojolmitra.driver

/** Waktu sekarang dalam milidetik sejak epoch (implementasi per platform). */
expect fun epochMillis(): Long

/** Format jam lokal "HH:mm" untuk label chat & laporan. */
expect fun formatClock(epochMillis: Long): String

/** Menit sejak tengah malam waktu lokal (0..1439), untuk perkiraan area ramai. */
expect fun localMinuteOfDay(epochMillis: Long): Int

/** Nama platform, untuk ditampilkan di layar Asisten Suara. */
expect val platformName: String
