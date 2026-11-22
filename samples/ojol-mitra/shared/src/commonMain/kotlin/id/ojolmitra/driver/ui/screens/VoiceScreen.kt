package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import id.asbun.voice.WakeUnavailableReason
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.core.DriverUiState
import id.ojolmitra.driver.platformName
import id.ojolmitra.driver.ui.components.SuggestionChips
import id.ojolmitra.driver.ui.components.VoiceOrb
import id.ojolmitra.driver.ui.components.label
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.voice.OjolMitraVoice
import id.ojolmitra.driver.voice.VoiceUiState

private data class CommandGroup(val emoji: String, val title: String, val description: String, val examples: List<String>)

private val COMMAND_GROUPS = listOf(
    CommandGroup(
        "📥", "Terima / tolak order",
        "Saat order masuk, asisten membacakan detail lalu langsung mendengarkan jawabanmu.",
        listOf("Terima", "Gas ambil", "Oke", "Tolak", "Nggak dulu", "Ulangi"),
    ),
    CommandGroup(
        "💬", "Chat ke customer",
        "Ucapkan pesan setelah kata “chat”, “kirim pesan”, “bilang ke penumpang”, atau “balas”.",
        listOf("Chat saya sudah di depan", "Bilang ke penumpang tunggu 5 menit", "Balas oke siap", "Kirim pesan", "Baca pesan"),
    ),
    CommandGroup(
        "🚦", "Lapor lalu lintas",
        "Sebut jenis kejadian, tingkat keparahan, dan lokasi. Tanpa lokasi = jalan tempatmu sekarang.",
        listOf(
            "Lapor macet parah di Jalan Sudirman", "Ada kecelakaan di depan", "Lapor banjir di Kemang Raya",
            "Jalan ditutup dekat Stasiun Dukuh Atas", "Info razia di Gatot Subroto",
        ),
    ),
    CommandGroup(
        "🛵", "Selama perjalanan",
        "Konfirmasi tiap tahap tanpa geser tombol.",
        listOf("Sudah sampai", "Mulai perjalanan", "Selesai", "Buka navigasi", "Telepon penumpang"),
    ),
    CommandGroup(
        "⚙️", "Lainnya",
        "Atur status dan cek pendapatan.",
        listOf("Mulai narik", "Offline", "Pendapatan hari ini", "Nyalakan autobid", "Bantuan"),
    ),
)

@Composable
fun VoiceScreen(
    state: DriverUiState,
    voice: VoiceUiState,
    onMic: () -> Unit,
    onTry: (String) -> Unit,
    onHandsFree: (Boolean) -> Unit,
    onWakeWord: (Boolean) -> Unit,
    onReadAloud: (Boolean) -> Unit,
    onAutobid: (Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(Ojol.Background), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Ojol.GreenDeep, Ojol.Green)))
                    .statusBarsPadding()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Asisten Suara", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Terima order, chat customer, dan lapor lalu lintas tanpa sentuh layar — mata tetap di jalan.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                VoiceOrb(voice.phase, voice.level, onMic, size = 76.dp)
                Text(
                    if (voice.phase.name == "IDLE") "Ketuk untuk bicara" else voice.phase.label(),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Pengenalan suara: ${if (voice.recognitionAvailable) "tersedia" else "tidak tersedia (pakai mode ketik)"} • ${OjolMitraVoice.LOCALE} • $platformName",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp)) {
                SettingRow("Mode hands-free", "Order dibacakan & asisten langsung mendengarkan “terima/tolak”.", state.handsFree, onHandsFree)
                HorizontalDivider(color = Ojol.Divider)
                SettingRow(
                    "Panggil dengan “Halo Mitra”",
                    when (voice.wakeUnavailable) {
                        WakeUnavailableReason.NO_ON_DEVICE_RECOGNITION -> "Tidak tersedia: HP ini belum punya pengenalan suara offline bahasa Indonesia."
                        WakeUnavailableReason.PERMISSION_DENIED -> "Tidak tersedia: izin mikrofon belum diberikan."
                        else -> "Ucapkan “Halo Mitra” lalu perintahnya, tanpa menyentuh HP."
                    },
                    state.wakeWord,
                    onWakeWord,
                )
                HorizontalDivider(color = Ojol.Divider)
                SettingRow("Bacakan pesan customer", "Pesan masuk dibacakan otomatis saat hands-free aktif.", state.readMessagesAloud, onReadAloud)
                HorizontalDivider(color = Ojol.Divider)
                SettingRow("Autobid", "Order diterima otomatis tanpa perlu konfirmasi.", state.autobid, onAutobid)
            }
        }
        item {
            Text("Daftar perintah (ketuk untuk coba)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
        }
        items(COMMAND_GROUPS) { group ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                }
                Text(group.description, fontSize = 12.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
                SuggestionChips(group.examples, onClick = onTry)
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Text("Riwayat perintah", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
            if (voice.log.isEmpty()) {
                Text("Belum ada perintah suara.", color = Ojol.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        items(voice.log, key = { it.id }) { entry ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.success) "✅" else "⚠️", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("“${entry.heard}”", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(entry.timeLabel, fontSize = 11.sp, color = Ojol.TextHint)
                }
                Text(entry.response, fontSize = 12.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, fontSize = 12.sp, color = Ojol.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ojol.Green, checkedThumbColor = Color.White),
        )
    }
}
