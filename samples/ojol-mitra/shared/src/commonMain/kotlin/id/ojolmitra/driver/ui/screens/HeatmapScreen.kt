package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.core.DriverLocation
import id.ojolmitra.driver.core.DriverUiState
import id.ojolmitra.driver.model.Hotspot
import id.ojolmitra.driver.model.HotspotLevel
import id.ojolmitra.driver.model.formatCount
import id.ojolmitra.driver.model.formatDuration
import id.ojolmitra.driver.model.formatKm
import id.ojolmitra.driver.model.formatTimeOfDay
import id.ojolmitra.driver.model.formatTimeRange
import id.ojolmitra.driver.ui.components.CityMapView
import id.ojolmitra.driver.ui.components.MicFab
import id.ojolmitra.driver.ui.components.Pill
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color
import id.ojolmitra.driver.voice.VoicePhase
import id.ojolmitra.driver.voice.VoiceUiState

/**
 * Halaman Area Ramai: heat map perkiraan keramaian 3 jam ke depan dan daftar area terdekat,
 * masing-masing dengan jam ramai, perkiraan calon penumpang, driver di sekitar, dan acaranya.
 * Daftarnya bisa dibacakan asisten (tombol di atas, tombol speaker per kartu, atau perintah suara).
 */
@Composable
fun HeatmapScreen(
    state: DriverUiState,
    location: DriverLocation,
    voice: VoiceUiState,
    onReadAll: () -> Unit,
    onReadOne: (Int) -> Unit,
    onStopReading: () -> Unit,
    onMic: () -> Unit,
) {
    val spots = state.hotspots
    val speaking = voice.phase == VoicePhase.SPEAKING
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().background(Ojol.Background),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { HeatmapHeader(spots, state.hotspotsAtMinute) }
        item {
            ReadAloudCard(
                speaking = speaking,
                onClick = if (speaking) onStopReading else onReadAll,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        item {
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Ojol.Divider, RoundedCornerShape(18.dp)),
            ) {
                CityMapView(
                    location = location,
                    reports = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    online = state.isOnline,
                    overview = listOf(location.position) + spots.take(5).map { it.position },
                    hotspots = spots,
                )
            }
            HeatLegend(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Terdekat dari kamu", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Diperbarui ${formatTimeOfDay(state.hotspotsAtMinute)}", fontSize = 12.sp, color = Ojol.TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (spots.isEmpty()) {
            item {
                Text(
                    "Belum ada area ramai dalam 3 jam ke depan.",
                    color = Ojol.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        itemsIndexed(spots, key = { _, spot -> spot.id }) { index, spot ->
            HotspotCard(spot, number = index + 1, onRead = { onReadOne(index) })
        }
        item {
            Text(
                "Perkiraan dari riwayat order, driver online, dan jadwal acara (simulasi). Angka bisa berubah.",
                fontSize = 11.sp,
                color = Ojol.TextHint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
    // Mic untuk tanya lanjut: "detail nomor 2", "berikutnya", "ada acara apa".
    MicFab(voice, onMic, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
    }
}

@Composable
private fun HeatmapHeader(spots: List<Hotspot>, atMinute: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Ojol.GreenDark, Ojol.Green)))
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Text("Area Ramai", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Perkiraan keramaian 3 jam ke depan: jam ramai, calon penumpang, driver, dan acara.",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Jam ${formatTimeOfDay(atMinute)}", Color.White.copy(alpha = 0.2f), Color.White)
            Pill("${spots.size} area", Color.White.copy(alpha = 0.2f), Color.White)
            Pill("${spots.count { it.ongoing }} ramai sekarang", Color.White.copy(alpha = 0.2f), Color.White)
            val events = spots.count { it.event != null }
            if (events > 0) Pill("$events acara", Color.White.copy(alpha = 0.2f), Color.White)
        }
    }
}

@Composable
private fun ReadAloudCard(speaking: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Ojol.GreenSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (speaking) Ojol.Red else Ojol.Green),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (speaking) "Berhenti membacakan" else "Bacakan area ramai",
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (speaking) "Sedang membacakan… ketuk untuk berhenti" else "Bacakan area terdekat",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Atau ucapkan “Area ramai”, “Ada acara apa?”, “Detail nomor 2”, “Berikutnya”",
                fontSize = 12.sp,
                color = Ojol.TextSecondary,
            )
        }
    }
}

@Composable
private fun HeatLegend(modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HotspotLevel.entries.forEach { level ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(level.color()))
                Spacer(Modifier.width(4.dp))
                Text(level.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ojol.TextSecondary)
            }
        }
    }
}

@Composable
private fun HotspotCard(spot: Hotspot, number: Int, onRead: () -> Unit) {
    val levelColor = spot.level.color()
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Ojol.Divider, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(levelColor), contentAlignment = Alignment.Center) {
            Text("$number", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spot.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Pill(spot.level.label, levelColor.copy(alpha = 0.13f), levelColor)
            }
            Text(
                "${spot.area} • ${formatKm(spot.distanceKm)} • ±${spot.etaMinutes} mnt",
                fontSize = 12.sp,
                color = Ojol.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Ojol.TextPrimary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(formatTimeRange(spot.startMinute, spot.endMinute), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                if (spot.ongoing) {
                    Pill("Ramai sekarang", Ojol.Red.copy(alpha = 0.1f), Ojol.Red)
                } else {
                    Pill("${formatDuration(spot.startsInMinutes)} lagi", Ojol.Amber.copy(alpha = 0.16f), Color(0xFFA86B00))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ojol.Background).padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HotspotStat("±${formatCount(spot.people)}", "orang", Modifier.weight(1f))
                HotspotStat("${spot.drivers}", "driver", Modifier.weight(1f))
                HotspotStat("${spot.peoplePerDriver}", "per driver", Modifier.weight(1f), valueColor = levelColor)
            }
            Spacer(Modifier.height(8.dp))
            val event = spot.event
            Text(
                if (event != null) {
                    "${event.emoji} ${event.name} • ${formatTimeRange(event.startMinute, event.endMinute)} • ${formatCount(event.attendees)} pengunjung"
                } else {
                    "${spot.reason.emoji} ${spot.reason.label}"
                },
                fontSize = 12.sp,
                fontWeight = if (event != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (event != null) Ojol.Purple else Ojol.TextSecondary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Ojol.GreenLight).clickable(onClick = onRead),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Bacakan nomor $number",
                tint = Ojol.GreenDark,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun HotspotStat(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Ojol.TextPrimary) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = valueColor)
        Text(label, fontSize = 10.sp, color = Ojol.TextSecondary, maxLines = 1)
    }
}
