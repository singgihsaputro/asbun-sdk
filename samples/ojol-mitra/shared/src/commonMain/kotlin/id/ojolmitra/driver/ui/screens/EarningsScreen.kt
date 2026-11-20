package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.model.CompletedTrip
import id.ojolmitra.driver.model.Earnings
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.formatKm
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.ui.components.StatItem
import id.ojolmitra.driver.ui.components.VerticalDivider
import id.ojolmitra.driver.ui.components.icon
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color

@Composable
fun EarningsScreen(earnings: Earnings, trips: List<CompletedTrip>) {
    LazyColumn(Modifier.fillMaxSize().background(Ojol.Background), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Ojol.GreenDark, Ojol.Green)))
                        .statusBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 64.dp),
                ) {
                    Text("Pendapatan hari ini", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                    Text(formatRupiah(earnings.today), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${earnings.trips} trip selesai • Tunai diterima ${formatRupiah(earnings.cashCollected)}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 36.dp)
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Ojol.GreenLight), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = Ojol.GreenDark)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Saldo dompet", fontSize = 12.sp, color = Ojol.TextSecondary)
                        Text(formatRupiah(earnings.wallet), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Text("Potongan order tunai diambil dari saldo", fontSize = 10.sp, color = Ojol.TextHint, textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
                }
            }
            Spacer(Modifier.height(52.dp))
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                Text("Performa", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    StatItem("Penerimaan", "${earnings.acceptanceRate}%", Modifier.weight(1f), Ojol.GreenDark)
                    VerticalDivider()
                    StatItem("Penyelesaian", "${earnings.completionRate}%", Modifier.weight(1f), Ojol.GreenDark)
                    VerticalDivider()
                    StatItem("Rating", ((earnings.rating * 100).toInt() / 100.0).toString(), Modifier.weight(1f), Ojol.GreenDark)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Hari ini: ${earnings.offers} order masuk • ${earnings.accepted} diterima • ${earnings.rejected} dilewati • ${earnings.missed} terlewat",
                    fontSize = 12.sp,
                    color = Ojol.TextSecondary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Riwayat order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
        }
        if (trips.isEmpty()) {
            item {
                Text(
                    "Belum ada order selesai. Aktifkan akun dan ucapkan “Terima” saat order masuk!",
                    color = Ojol.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                )
            }
        }
        items(trips) { TripRow(it) }
    }
}

@Composable
private fun TripRow(trip: CompletedTrip) {
    val order = trip.order
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(order.service.color().copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(order.service.icon(), contentDescription = null, tint = order.service.color())
        }
        Column(Modifier.weight(1f)) {
            Text("${order.pickup.name} → ${order.dropoff.name}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${trip.timeLabel} • ${order.service.label} • ${formatKm(order.tripDistanceKm)} • ${if (order.payment == PaymentMethod.CASH) "Tunai" else "Non-tunai"} • ",
                    fontSize = 11.sp,
                    color = Ojol.TextSecondary,
                )
                Icon(Icons.Filled.Star, null, tint = Ojol.Amber, modifier = Modifier.size(12.dp))
                Text("${trip.customerRating}", fontSize = 11.sp, color = Ojol.TextSecondary)
            }
        }
        Text("+${formatRupiah(trip.earning)}", fontWeight = FontWeight.Bold, color = Ojol.GreenDark)
    }
}
