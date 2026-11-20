package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.core.DriverLocation
import id.ojolmitra.driver.core.DriverUiState
import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.model.actionLabel
import id.ojolmitra.driver.model.etaMinutes
import id.ojolmitra.driver.model.formatKm
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.model.title
import id.ojolmitra.driver.model.voiceHint
import id.ojolmitra.driver.ui.components.Avatar
import id.ojolmitra.driver.ui.components.CityMapView
import id.ojolmitra.driver.ui.components.MicFab
import id.ojolmitra.driver.ui.components.PaymentChip
import id.ojolmitra.driver.ui.components.ServiceBadge
import id.ojolmitra.driver.ui.components.SlideToConfirm
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color
import id.ojolmitra.driver.voice.VoiceUiState

@Composable
fun TripScreen(
    state: DriverUiState,
    trip: ActiveTrip,
    location: DriverLocation,
    voice: VoiceUiState,
    controller: DriverController,
    onOpenReport: () -> Unit,
) {
    val order = trip.order
    val toPickup = trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.AT_PICKUP
    Box(Modifier.fillMaxSize()) {
        CityMapView(
            location = location,
            reports = state.reports,
            modifier = Modifier.fillMaxSize(),
            pickup = if (toPickup) order.pickup.position else null,
            dropoff = order.dropoff.position,
            zoom = 1.25f,
            topInset = 150.dp,
            bottomInset = 330.dp,
        )

        TripTopCard(trip, location, controller::openNavigation, Modifier.align(Alignment.TopCenter))

        Column(Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                MapRoundButton(Icons.Filled.Warning, "Lapor", Ojol.Orange, onOpenReport, Modifier.padding(bottom = 14.dp))
                MicFab(voice, controller::onMicPressed)
            }
            TripBottomCard(state, trip, controller)
        }
    }
}

@Composable
private fun TripTopCard(trip: ActiveTrip, location: DriverLocation, onNavigate: () -> Unit, modifier: Modifier = Modifier) {
    val order = trip.order
    val target = if (trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.AT_PICKUP) order.pickup else order.dropoff
    val moving = trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.TO_DROPOFF
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Ojol.GreenDark, Ojol.Green)))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.stage.title(order.service).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(target.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(target.address, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (moving && !trip.reachedTarget) {
                    Text(formatKm(location.remainingKm), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text("±${etaMinutes(location.remainingKm)} mnt", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                } else {
                    Text("Sampai", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text("di lokasi", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Navigasi",
                color = Ojol.GreenDark,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onNavigate)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "🎙 Ucapkan “${trip.stage.voiceHint()}”",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TripBottomCard(state: DriverUiState, trip: ActiveTrip, controller: DriverController) {
    val order = trip.order
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(order.customer.initials, size = 46.dp, color = order.service.color())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(order.customer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Ojol.Amber, modifier = Modifier.size(14.dp))
                    Text(" ${order.customer.rating}", fontSize = 12.sp, color = Ojol.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    ServiceBadge(order.service)
                }
            }
            CircleAction(Icons.AutoMirrored.Filled.Chat, "Chat", badge = state.unreadChat, onClick = controller::openChat)
            Spacer(Modifier.width(10.dp))
            CircleAction(Icons.Filled.Call, "Telepon", onClick = controller::callCustomer)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Ojol.Divider)

        Row(verticalAlignment = Alignment.CenterVertically) {
            PaymentChip(order.payment)
            Spacer(Modifier.width(8.dp))
            Text(
                if (order.payment == PaymentMethod.CASH) "Terima tunai ${formatRupiah(order.fare)}" else "Sudah dibayar non-tunai",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text("Pendapatan", fontSize = 11.sp, color = Ojol.TextSecondary)
                Text(formatRupiah(order.driverEarning), fontWeight = FontWeight.Bold, color = Ojol.GreenDark)
            }
        }

        val details = buildList {
            if (order.service != ServiceType.RIDE) addAll(order.items)
            order.note?.let { add("Catatan: $it") }
        }
        if (details.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Ojol.Background).padding(10.dp),
            ) {
                details.take(4).forEach {
                    Text("• $it", fontSize = 12.sp, color = Ojol.TextSecondary, fontStyle = if (it.startsWith("Catatan")) FontStyle.Italic else FontStyle.Normal)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SlideToConfirm(
            text = trip.stage.actionLabel(order.service, order.payment),
            onConfirm = controller::advanceTrip,
            color = if (trip.stage == TripStage.AT_DROPOFF) Ojol.GreenDark else Ojol.Green,
        )
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit, badge: Int = 0) {
    Box {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Ojol.GreenLight).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Ojol.GreenDark)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(18.dp).clip(CircleShape).background(Ojol.Red),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
