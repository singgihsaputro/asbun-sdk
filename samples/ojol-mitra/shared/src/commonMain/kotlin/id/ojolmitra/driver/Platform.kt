package id.ojolmitra.driver

/** Waktu sekarang dalam milidetik sejak epoch (implementasi per platform). */
expect fun epochMillis(): Long

/** Format jam lokal "HH:mm" untuk label chat & laporan. */
expect fun formatClock(epochMillis: Long): String

/** Nama platform, untuk ditampilkan di layar Asisten Suara. */
expect val platformName: String
