package id.ojolmitra.driver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.voice.VoicePhase
import id.ojolmitra.driver.voice.VoiceUiState
import kotlin.math.PI
import kotlin.math.sin

private val SpeakingBlue = Color(0xFF1E88E5)

fun VoicePhase.label(): String = when (this) {
    VoicePhase.IDLE -> "Siap"
    VoicePhase.LISTENING -> "Mendengarkan…"
    VoicePhase.PROCESSING -> "Memproses…"
    VoicePhase.SPEAKING -> "Asisten bicara…"
}

private fun VoicePhase.tint(): Color = when (this) {
    VoicePhase.SPEAKING -> SpeakingBlue
    VoicePhase.PROCESSING -> Ojol.Amber
    else -> Ojol.Green
}

/** Tombol mic bulat dengan cincin yang berdenyut mengikuti volume suara. */
@Composable
fun VoiceOrb(
    phase: VoicePhase,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
) {
    val smooth by animateFloatAsState(if (phase == VoicePhase.LISTENING) level else 0f, tween(120), label = "lvl")
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing), RepeatMode.Restart), label = "breathe",
    )
    val tint = phase.tint()
    val active = phase != VoicePhase.IDLE
    Box(modifier.size(size * 1.7f), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.size(size * 1.7f)) {
                val base = size.toPx() / 2f
                drawCircle(tint.copy(alpha = 0.18f * (1f - breathe)), radius = base * (1f + 0.65f * breathe))
                drawCircle(tint.copy(alpha = 0.22f), radius = base * (1.05f + 0.5f * smooth))
            }
        }
        Box(
            Modifier
                .size(size)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(tint, tint.copy(red = tint.red * 0.75f, green = tint.green * 0.75f, blue = tint.blue * 0.75f))))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (phase) {
                    VoicePhase.SPEAKING -> Icons.Filled.GraphicEq
                    VoicePhase.PROCESSING -> Icons.Filled.MoreHoriz
                    else -> Icons.Filled.Mic
                },
                contentDescription = "Asisten suara",
                tint = Color.White,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** Gelombang suara (bar) yang bergerak saat mendengarkan/berbicara. */
@Composable
fun VoiceWave(phase: VoicePhase, level: Float, modifier: Modifier = Modifier, bars: Int = 24, color: Color = phase.tint()) {
    val t by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val amp by animateFloatAsState(
        when (phase) {
            VoicePhase.LISTENING -> 0.25f + level * 0.75f
            VoicePhase.SPEAKING -> 0.55f
            VoicePhase.PROCESSING -> 0.18f
            VoicePhase.IDLE -> 0.06f
        },
        tween(150),
        label = "amp",
    )
    Canvas(modifier.height(36.dp)) {
        val gap = size.width / bars
        val w = gap * 0.5f
        for (i in 0 until bars) {
            val wave = (sin((i / bars.toFloat()) * 2 * PI * 1.5 + t * 2 * PI).toFloat() + 1f) / 2f
            val h = size.height * (0.12f + 0.88f * amp * (0.35f + 0.65f * wave))
            drawRoundRect(
                color.copy(alpha = 0.85f),
                topLeft = Offset(i * gap + (gap - w) / 2f, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

/** FAB mic kecil yang mengambang di atas peta. */
@Composable
fun MicFab(voice: VoiceUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    VoiceOrb(voice.phase, voice.level, onClick, modifier, size = 58.dp)
}

/**
 * Panel asisten suara: transkrip langsung, jawaban asisten, contoh perintah,
 * dan kolom ketik untuk simulasi (berguna di emulator/simulator tanpa mikrofon).
 */
@Composable
fun VoiceAssistantPanel(
    voice: VoiceUiState,
    suggestions: List<String>,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }
    var showKeyboardInput by remember { mutableStateOf(!voice.recognitionAvailable) }
    Column(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(voice.phase.tint()))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Asisten Suara", style = MaterialTheme.typography.titleMedium)
                Text(voice.phase.label(), fontSize = 12.sp, color = Ojol.TextSecondary)
            }
            IconButton(onClick = { showKeyboardInput = !showKeyboardInput }) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Ketik perintah", tint = Ojol.TextSecondary)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Tutup", tint = Ojol.TextSecondary)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            VoiceOrb(voice.phase, voice.level, onMic, size = 54.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val transcript = when {
                    voice.phase == VoicePhase.LISTENING && voice.partialText.isNotBlank() -> voice.partialText
                    voice.phase == VoicePhase.LISTENING -> "Silakan bicara…"
                    voice.lastHeard != null -> voice.lastHeard
                    else -> "Tekan mic lalu ucapkan perintah"
                }
                Text(
                    "“$transcript”",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (voice.phase == VoicePhase.LISTENING && voice.partialText.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    color = if (voice.phase == VoicePhase.LISTENING) Ojol.TextPrimary else Ojol.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                VoiceWave(voice.phase, voice.level, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        voice.lastResponse?.let { response ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Ojol.GreenLight).padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Ojol.GreenDark, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(response, fontSize = 13.sp, color = Ojol.GreenDeep, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        voice.error?.let {
            Text(it, color = Ojol.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Text("Coba ucapkan:", fontSize = 12.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        SuggestionChips(suggestions, onClick = onSubmit)

        if (showKeyboardInput) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Ketik perintah (simulasi suara)", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSubmit(typed)
                        typed = ""
                    }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ojol.Green, unfocusedBorderColor = Ojol.Divider),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        onSubmit(typed)
                        typed = ""
                    },
                    modifier = Modifier.clip(CircleShape).background(Ojol.Green),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun SuggestionChips(suggestions: List<String>, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(suggestions) { s ->
            Text(
                s,
                fontSize = 13.sp,
                color = Ojol.GreenDeep,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Ojol.GreenSoft, RoundedCornerShape(50))
                    .background(Ojol.GreenLight.copy(alpha = 0.6f))
                    .clickable { onClick(s) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Status suara ringkas di dalam kartu order masuk. */
@Composable
fun InlineVoiceBar(voice: VoiceUiState, hint: String, onMic: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ojol.GreenLight)
            .clickable(onClick = onMic)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (voice.phase == VoicePhase.SPEAKING) SpeakingBlue else Ojol.Green),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (voice.phase == VoicePhase.SPEAKING) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                contentDescription = "Perintah suara",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val title = when (voice.phase) {
                VoicePhase.LISTENING -> if (voice.partialText.isNotBlank()) "“${voice.partialText}”" else "Mendengarkan… $hint"
                VoicePhase.SPEAKING -> "Membacakan detail order…"
                VoicePhase.PROCESSING -> "Memproses “${voice.partialText}”…"
                VoicePhase.IDLE -> "Ketuk lalu $hint"
            }
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ojol.GreenDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VoiceWave(voice.phase, voice.level, Modifier.fillMaxWidth().height(18.dp), bars = 30)
        }
    }
}
