package id.ojolmitra.driver.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.core.DriverLocation
import id.ojolmitra.driver.core.DriverUiState
import id.ojolmitra.driver.core.HomeTab
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.ui.components.Avatar
import id.ojolmitra.driver.ui.components.CityMapView
import id.ojolmitra.driver.ui.components.MicFab
import id.ojolmitra.driver.ui.components.StatItem
import id.ojolmitra.driver.ui.components.VerticalDivider
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.voice.VoiceUiState

@Composable
fun HomeScreen(
    state: DriverUiState,
    location: DriverLocation,
    voice: VoiceUiState,
    controller: DriverController,
    onOpenReport: () -> Unit,
) {
    val offer = state.offer
    Box(Modifier.fillMaxSize()) {
        CityMapView(
            location = location,
            reports = state.reports,
            modifier = Modifier.fillMaxSize(),
            online = state.isOnline,
            pickup = offer?.order?.pickup?.position,
            dropoff = offer?.order?.dropoff?.position,
            previewPickupLeg = offer?.order?.pickupRoute.orEmpty(),
            previewTripLeg = offer?.order?.tripRoute.orEmpty(),
            overview = offer?.let { it.order.pickupRoute + it.order.tripRoute },
            topInset = 120.dp,
            bottomInset = if (offer != null) 470.dp else 250.dp,
        )

        HomeTopBar(state, controller, Modifier.align(Alignment.TopCenter))

        if (offer == null) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    MapRoundButton(Icons.Filled.Warning, "Lapor", Ojol.Orange, onOpenReport, Modifier.padding(bottom = 14.dp))
                    MicFab(voice, controller::onMicPressed)
                }
                HomeBottomSheet(state, controller)
            }
        }
    }
}

@Composable
private fun HomeTopBar(state: DriverUiState, controller: DriverController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(state.profile.initials, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.profile.name, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.isOnline) Ojol.Green else Ojol.TextHint))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.isOnline) "Online" else "Offline",
                        fontSize = 12.sp,
                        color = if (state.isOnline) Ojol.GreenDark else Ojol.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" • ${state.profile.plate}", fontSize = 12.sp, color = Ojol.TextSecondary)
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { controller.selectTab(HomeTab.PENDAPATAN) }.padding(4.dp),
            ) {
                Text("Hari ini", fontSize = 11.sp, color = Ojol.TextSecondary)
                Text(formatRupiah(state.earnings.today), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ojol.GreenDark)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(
                icon = Icons.Filled.RecordVoiceOver,
                text = if (state.handsFree) "Hands-free ON" else "Hands-free OFF",
                active = state.handsFree,
                onClick = { controller.setHandsFree(!state.handsFree) },
            )
            ToggleChip(
                icon = Icons.Filled.Bolt,
                text = if (state.autobid) "Autobid ON" else "Autobid",
                active = state.autobid,
                onClick = { controller.setAutobid(!state.autobid) },
            )
        }
    }
}

@Composable
fun ToggleChip(icon: ImageVector, text: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .shadow(3.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(if (active) Ojol.Green else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Color.White else Ojol.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.White else Ojol.TextPrimary)
    }
}

@Composable
fun MapRoundButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).shadow(5.dp, CircleShape).clip(CircleShape).background(Color.White).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun HomeBottomSheet(state: DriverUiState, controller: DriverController) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(Ojol.Divider))
        Spacer(Modifier.height(12.dp))

        if (state.isOnline) OnlineStatus(state, controller) else OfflineStatus(controller)

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(Ojol.Background).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem("Penerimaan", "${state.earnings.acceptanceRate}%", Modifier.weight(1f))
            VerticalDivider()
            StatItem("Penyelesaian", "${state.earnings.completionRate}%", Modifier.weight(1f))
            VerticalDivider()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Ojol.Amber, modifier = Modifier.size(16.dp))
                    Text(((state.earnings.rating * 100).toInt() / 100.0).toString(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Text("Rating", fontSize = 11.sp, color = Ojol.TextSecondary)
            }
        }
    }
}

@Composable
private fun OfflineStatus(controller: DriverController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Kamu lagi nonaktif", style = MaterialTheme.typography.titleMedium)
            Text("Aktifkan untuk mulai terima order, atau ucapkan “Mulai narik”.", fontSize = 13.sp, color = Ojol.TextSecondary)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Brush.horizontalGradient(listOf(Ojol.Green, Ojol.GreenDark)))
            .clickable { controller.setOnline(true) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text("Mulai Narik", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun OnlineStatus(state: DriverUiState, controller: DriverController) {
    val sweep by rememberInfiniteTransition(label = "search").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Kamu lagi aktif", style = MaterialTheme.typography.titleMedium, color = Ojol.GreenDark)
            Text("Mencari order di sekitarmu…", fontSize = 13.sp, color = Ojol.TextSecondary)
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .border(2.dp, Ojol.Red.copy(alpha = 0.6f), CircleShape)
                .clickable { controller.setOnline(false) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Offline", tint = Ojol.Red)
        }
    }
    Spacer(Modifier.height(10.dp))
    // Bar pencarian beranimasi
    BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Ojol.GreenLight)) {
        val barWidth = maxWidth * 0.35f
        Box(
            Modifier
                .offset(x = (maxWidth + barWidth) * sweep - barWidth)
                .width(barWidth)
                .height(5.dp)
                .clip(CircleShape)
                .background(Ojol.Green),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Ucapkan “Terima” saat order masuk — tanpa sentuh layar.",
            fontSize = 12.sp,
            color = Ojol.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "⚡ Order sekarang",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Ojol.GreenDark,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Ojol.GreenLight)
                .clickable { controller.requestOrderNow() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
