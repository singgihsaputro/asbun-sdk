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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import id.ojolmitra.driver.epochMillis
import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficReport
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.formatAgo
import id.ojolmitra.driver.ui.components.Pill
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color
import kotlinx.coroutines.delay

@Composable
fun TrafficScreen(
    reports: List<TrafficReport>,
    onQuickReport: (TrafficType) -> Unit,
    onVoiceReport: () -> Unit,
) {
    val now by produceState(epochMillis()) {
        while (true) {
            delay(30_000)
            value = epochMillis()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Ojol.Background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Ojol.GreenDark, Ojol.Green)))
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Text("Laporan Lalu Lintas", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text("Bantu sesama mitra hindari macet & bahaya di jalan.", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${reports.size} laporan aktif", Color.White.copy(alpha = 0.2f), Color.White)
                    Pill("${reports.count { it.isMine }} darimu", Color.White.copy(alpha = 0.2f), Color.White)
                }
            }
        }
        item {
            Row(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Ojol.GreenSoft, RoundedCornerShape(16.dp))
                    .clickable(onClick = onVoiceReport)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Ojol.Green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lapor pakai suara", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Contoh: “Lapor macet parah di Jalan Sudirman”, “Ada kecelakaan di depan”, “Banjir di Kemang Raya”",
                        fontSize = 12.sp,
                        color = Ojol.TextSecondary,
                    )
                }
            }
        }
        item {
            Text("Lapor cepat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
            TrafficTypeGrid(onQuickReport)
            Spacer(Modifier.height(18.dp))
            Text("Laporan terbaru", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
        }
        if (reports.isEmpty()) {
            item {
                Text(
                    "Belum ada laporan. Jalanan lancar! 🎉",
                    color = Ojol.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(reports, key = { it.id }) { ReportRow(it, now) }
    }
}

@Composable
private fun TrafficTypeGrid(onClick: (TrafficType) -> Unit) {
    val rows = TrafficType.entries.chunked(4)
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { type ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onClick(type) }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(type.color().copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(type.emoji, fontSize = 24.sp)
                        }
                        Text(
                            type.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReportRow(report: TrafficReport, now: Long) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (report.isMine) Ojol.GreenLight else Color.White)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(report.type.color().copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(report.type.emoji, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(report.type.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Pill(report.severity.label, report.severity.color().copy(alpha = 0.14f), report.severity.color())
            }
            Text(report.locationLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${report.reporter}${if (report.viaVoice) " • 🎙 via suara" else ""} • ${formatAgo(report.createdAtMillis, now)}",
                fontSize = 11.sp,
                color = Ojol.TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ThumbUp, contentDescription = "Konfirmasi", tint = Ojol.TextHint, modifier = Modifier.size(16.dp))
            Text("${report.confirmations}", fontSize = 11.sp, color = Ojol.TextSecondary)
        }
    }
}

/** Form lapor manual (ketuk), dengan jenis, tingkat keparahan, dan lokasi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficReportSheet(
    initialType: TrafficType,
    currentRoad: String,
    onSubmit: (TrafficType, Severity, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var severity by remember { mutableStateOf(Severity.SEDANG) }
    var location by remember { mutableStateOf(currentRoad) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Laporkan kondisi jalan", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TrafficType.entries) { t ->
                    val selected = t == type
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) t.color() else Ojol.Chip)
                            .clickable { type = t }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.emoji, fontSize = 15.sp)
                        Spacer(Modifier.width(5.dp))
                        Text(t.label, color = if (selected) Color.White else Ojol.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Tingkat keparahan", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Severity.entries.forEach { s ->
                    val selected = s == severity
                    Text(
                        s.label,
                        color = if (selected) Color.White else s.color(),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) s.color() else s.color().copy(alpha = 0.1f))
                            .clickable { severity = s }
                            .padding(vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Lokasi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ojol.Green, focusedLabelColor = Ojol.Green),
            )
            Text(
                "🎙 Lebih cepat: ucapkan “Lapor ${type.spoken} di ${location.ifBlank { currentRoad }}”",
                fontSize = 12.sp,
                color = Ojol.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSubmit(type, severity, location) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ojol.Green),
            ) {
                Text("Kirim laporan", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
