package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.ChatMessage
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.ui.components.Avatar
import id.ojolmitra.driver.ui.components.SuggestionChips
import id.ojolmitra.driver.ui.components.VoiceWave
import id.ojolmitra.driver.ui.components.label
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.voice.ListenContext
import id.ojolmitra.driver.voice.VoicePhase
import id.ojolmitra.driver.voice.VoiceUiState

@Composable
fun ChatScreen(
    trip: ActiveTrip,
    messages: List<ChatMessage>,
    voice: VoiceUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onDictate: () -> Unit,
    onCall: () -> Unit,
) {
    val order = trip.order
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    val quickReplies = when (trip.stage) {
        TripStage.TO_PICKUP -> listOf("Saya OTW ya", "Mohon tunggu sebentar", "Lokasi sesuai aplikasi?", "Maaf agak macet")
        TripStage.AT_PICKUP -> listOf("Saya sudah di titik jemput", "Saya pakai jaket hijau", "Posisi di mana ya?")
        TripStage.TO_DROPOFF -> listOf("Sebentar lagi sampai", "Maaf macet, mohon ditunggu")
        TripStage.AT_DROPOFF -> listOf("Sudah sampai ya", "Terima kasih sudah order 🙏")
    }
    val dictating = voice.context == ListenContext.DICTATION && voice.phase != VoicePhase.IDLE

    Column(Modifier.fillMaxSize().background(Color(0xFFF6F7F8))) {
        Row(
            Modifier.fillMaxWidth().background(Ojol.Green).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Avatar(order.customer.initials, size = 38.dp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(order.customer.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${order.service.label} • ${order.id}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            IconButton(onClick = onCall) {
                Icon(Icons.Filled.Call, contentDescription = "Telepon", tint = Color.White)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Demi keamanan, chat hanya aktif selama order berlangsung. Kamu juga bisa chat lewat suara: “Chat saya sudah di depan”.",
                    fontSize = 11.sp,
                    color = Ojol.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            items(messages, key = { it.id }) { ChatBubble(it) }
        }

        if (dictating) {
            Column(Modifier.fillMaxWidth().background(Ojol.GreenLight).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    when {
                        voice.phase == VoicePhase.LISTENING && voice.partialText.isNotBlank() -> "“${voice.partialText}”"
                        voice.phase == VoicePhase.LISTENING -> "Mendengarkan… ucapkan pesanmu"
                        else -> voice.phase.label()
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = Ojol.GreenDeep,
                    fontSize = 14.sp,
                )
                VoiceWave(voice.phase, voice.level, Modifier.fillMaxWidth())
            }
        }

        Column(
            Modifier.fillMaxWidth().background(Color.White).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            SuggestionChips(quickReplies, onClick = onSend, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Tulis pesan…") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSend(text)
                        text = ""
                    }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ojol.Green, unfocusedBorderColor = Ojol.Divider),
                )
                Spacer(Modifier.width(8.dp))
                val sendMode = text.isNotBlank()
                IconButton(
                    onClick = {
                        if (sendMode) {
                            onSend(text)
                            text = ""
                        } else {
                            onDictate()
                        }
                    },
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(if (dictating) Ojol.Red else Ojol.Green),
                ) {
                    Icon(
                        if (sendMode) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                        contentDescription = if (sendMode) "Kirim" else "Dikte pesan",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    if (message.isSystem) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.text,
                fontSize = 11.sp,
                color = Ojol.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE9EBEE)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        return
    }
    val mine = message.fromDriver
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                )
                .background(if (mine) Ojol.Green else Color.White)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.text, color = if (mine) Color.White else Ojol.TextPrimary, fontSize = 15.sp)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (message.viaVoice) {
                    Icon(Icons.Filled.Mic, contentDescription = "via suara", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                    Text("via suara • ", fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                }
                Text(message.timeLabel, fontSize = 10.sp, color = if (mine) Color.White.copy(alpha = 0.85f) else Ojol.TextHint)
            }
        }
    }
}
