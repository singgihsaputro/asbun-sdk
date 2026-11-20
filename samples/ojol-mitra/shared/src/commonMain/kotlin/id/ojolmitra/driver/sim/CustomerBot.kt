package id.ojolmitra.driver.sim

import id.ojolmitra.driver.model.Order
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.model.TripStage
import kotlin.random.Random

/** Customer simulasi yang membalas chat driver secara kontekstual. */
class CustomerBot(private val random: Random) {

    fun replyTo(driverMessage: String, order: Order, stage: TripStage): String {
        val text = " " + driverMessage.lowercase() + " "
        fun has(vararg words: String) = words.any { text.contains(it) }
        return when {
            has("macet", "telat", "terlambat", "lama", "maaf") -> pick(
                "Gapapa pak, hati-hati di jalan ya 🙏",
                "Oke pak, santai aja. Saya tunggu",
            )
            has("sampai", "sudah di", "depan", "tiba", "lokasi") -> when (order.service) {
                ServiceType.RIDE -> pick("Oke pak, saya turun sekarang", "Siap, saya ke depan ya pak")
                ServiceType.FOOD -> pick("Oke pak, saya keluar ambil ya", "Makasih pak, saya ke bawah")
                ServiceType.SEND -> pick("Oke pak, paketnya saya ambil", "Siap pak, saya ke sana")
            }
            has("dimana", "di mana", "posisi", "mana") -> pick(
                "Saya di ${order.pickup.name}, pakai baju biru pak",
                "Sesuai titik di aplikasi ya pak",
            )
            has("otw", "jalan", "menuju", "meluncur", "on the way") -> pick("Oke pak, ditunggu", "Siap pak 👍")
            has("uang pas", "kembalian", "tunai", "cash") -> pick("Ada uang pas kok pak", "Nanti saya bayar pas ya pak")
            has("makanan", "pesanan", "antri", "antre") -> pick("Oke pak, makasih infonya", "Siap, ditunggu ya pak")
            has("helm", "masker") -> pick("Oke pak, makasih", "Siap pak")
            has("terima kasih", "makasih", "thanks") -> pick("Sama-sama pak 😊", "Makasih juga pak")
            else -> when (stage) {
                TripStage.TO_DROPOFF, TripStage.AT_DROPOFF -> pick("Oke pak 👍", "Baik pak, makasih")
                else -> pick("Oke pak, ditunggu ya", "Baik pak 👍", "Siap pak")
            }
        }
    }

    /** Pesan pertama dari customer setelah order diterima (tidak selalu ada). */
    fun openingMessage(order: Order): String? {
        if (random.nextInt(100) >= 70) return null
        return when (order.service) {
            ServiceType.RIDE -> pick(
                "Pak, saya tunggu di depan ${order.pickup.name} ya",
                "Halo pak, sesuai titik ya. Saya pakai jaket hitam",
                "Pak, saya di lobby utama ya",
            )
            ServiceType.FOOD -> pick(
                "Pak, tolong sambalnya dipisah ya 🙏",
                "Halo pak, minta sendok garpu ya",
                "Pak, kalau menu habis kabari saya ya",
            )
            ServiceType.SEND -> pick(
                "Pak, paketnya mudah pecah, tolong hati-hati ya",
                "Halo pak, nanti titip ke satpam saja",
            )
        }
    }

    /** Pesan customer ketika driver sudah sampai di titik jemput. */
    fun atPickupMessage(order: Order): String? = when (order.service) {
        ServiceType.RIDE -> pick("Sebentar ya pak, saya turun", "Bapak yang pakai jaket hijau ya?")
        ServiceType.FOOD -> null
        ServiceType.SEND -> pick("Pak, saya keluar sekarang ya")
    }

    private fun pick(vararg options: String): String = options[random.nextInt(options.size)]
}
