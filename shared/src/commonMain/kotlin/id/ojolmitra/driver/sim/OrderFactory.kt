package id.ojolmitra.driver.sim

import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.map.Place
import id.ojolmitra.driver.model.Customer
import id.ojolmitra.driver.model.Order
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.ServiceType
import kotlin.math.roundToInt
import kotlin.random.Random

/** Membuat order acak yang masuk akal untuk simulasi. */
class OrderFactory(private val random: Random) {
    private var counter = 0

    fun create(driverPosition: MapPoint, forcedService: ServiceType? = null): Order {
        val service = forcedService ?: pickService()
        val pickup = pickPickup(service, driverPosition)
        val dropoff = pickDropoff(pickup)
        val pickupRoute = CityMap.route(driverPosition, pickup.position)
        val tripRoute = CityMap.route(pickup.position, dropoff.position)
        val pickupKm = CityMap.unitsToKm(CityMap.pathLength(pickupRoute))
        val tripKm = CityMap.unitsToKm(CityMap.pathLength(tripRoute))

        val fare = fareFor(service, tripKm)
        val bonus = if (random.nextInt(100) < 25) 2_000 else 0
        val earning = roundTo500((fare * 0.8).roundToInt()) + bonus
        val payment = if (random.nextInt(100) < 45) PaymentMethod.CASH else PaymentMethod.WALLET
        counter++

        return Order(
            id = "OM-${100_000 + random.nextInt(900_000)}-$counter",
            service = service,
            customer = Customer(CUSTOMER_NAMES.random(random), (45 + random.nextInt(6)) / 10.0),
            pickup = pickup,
            dropoff = dropoff,
            pickupRoute = pickupRoute,
            tripRoute = tripRoute,
            pickupDistanceKm = pickupKm,
            tripDistanceKm = tripKm,
            fare = fare,
            driverEarning = earning,
            bonus = bonus,
            payment = payment,
            note = when (service) {
                ServiceType.RIDE -> RIDE_NOTES.random(random)
                ServiceType.FOOD -> FOOD_NOTES.random(random)
                ServiceType.SEND -> SEND_NOTES.random(random)
            },
            items = when (service) {
                ServiceType.RIDE -> emptyList()
                ServiceType.FOOD -> FOOD_MENU.shuffled(random).take(1 + random.nextInt(3))
                    .map { "${1 + random.nextInt(2)}x $it" }
                ServiceType.SEND -> listOf(SEND_ITEMS.random(random))
            },
        )
    }

    private fun pickService(): ServiceType {
        val roll = random.nextInt(100)
        return when {
            roll < 50 -> ServiceType.RIDE
            roll < 85 -> ServiceType.FOOD
            else -> ServiceType.SEND
        }
    }

    private fun pickPickup(service: ServiceType, driver: MapPoint): Place {
        val candidates = if (service == ServiceType.FOOD) CityMap.restaurants else CityMap.destinations
        val near = candidates.filter {
            val d = CityMap.pathLength(CityMap.route(driver, it.position))
            d in 120f..760f
        }
        return (near.ifEmpty { candidates.sortedBy { it.position.distanceTo(driver) }.take(3) }).random(random)
    }

    private fun pickDropoff(pickup: Place): Place {
        val candidates = CityMap.destinations.filter {
            it.id != pickup.id && CityMap.pathLength(CityMap.route(pickup.position, it.position)) in 300f..1700f
        }
        return candidates.ifEmpty { CityMap.destinations.filter { it.id != pickup.id } }.random(random)
    }

    private fun fareFor(service: ServiceType, km: Double): Int {
        val raw = when (service) {
            ServiceType.RIDE -> 9_000 + 2_500 * km
            ServiceType.FOOD -> 8_000 + 2_400 * km
            ServiceType.SEND -> 10_000 + 2_600 * km
        }
        return roundTo500(maxOf(raw.roundToInt(), 10_000))
    }

    private fun roundTo500(value: Int): Int = ((value + 250) / 500) * 500

    companion object {
        val CUSTOMER_NAMES = listOf(
            "Rina Amelia", "Dimas Pratama", "Siti Rahmawati", "Andi Wijaya", "Putri Lestari",
            "Bayu Saputra", "Nadia Kusuma", "Fajar Nugroho", "Clara Tan", "Yusuf Hidayat",
            "Maya Sari", "Kevin Halim", "Intan Permata", "Reza Firmansyah",
        )
        private val RIDE_NOTES = listOf(
            "Saya pakai jaket hijau, tunggu di depan lobby ya",
            "Jemput di halte depan ya pak",
            "Tolong bawa masker ya",
            "Di pintu samping dekat ATM",
            null,
        )
        private val FOOD_NOTES = listOf(
            "Sambalnya dipisah ya",
            "Minta alat makan",
            "Jangan pakai bawang goreng",
            null,
        )
        private val SEND_NOTES = listOf(
            "Barang mudah pecah, hati-hati",
            "Titip ke satpam kalau saya tidak di tempat",
            null,
        )
        private val FOOD_MENU = listOf(
            "Bakmi ayam spesial", "Nasi goreng kambing", "Es teh manis", "Martabak manis keju",
            "Ayam geprek level 3", "Soto betawi", "Kopi susu gula aren", "Sate padang", "Nasi telur balado",
        )
        private val SEND_ITEMS = listOf(
            "Dokumen kantor (amplop coklat)", "Kue ulang tahun", "Paket pakaian 1 kg", "Laptop (fragile)", "Obat-obatan",
        )
    }
}
