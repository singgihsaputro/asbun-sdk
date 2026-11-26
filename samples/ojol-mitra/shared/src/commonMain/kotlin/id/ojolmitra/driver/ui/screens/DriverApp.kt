package id.ojolmitra.driver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.KeepScreenOn
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.core.DriverUiState
import id.ojolmitra.driver.core.HomeTab
import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.model.OrderOffer
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.ui.components.VoiceAssistantPanel
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.voice.ListenContext
import id.ojolmitra.driver.voice.VoicePhase
import id.ojolmitra.driver.voice.VoiceUiState

/** Root UI aplikasi driver — dipakai sama persis oleh Android dan iOS. */
@Composable
fun DriverApp(controller: DriverController) {
    val state by controller.state.collectAsState()
    val sdkVoice by controller.voice.state.collectAsState()
    val voicePanel by controller.voicePanel.state.collectAsState()
    val voice = VoiceUiState.of(sdkVoice, voicePanel, hasOffer = state.offer != null)
    val location by controller.location.collectAsState()
    KeepScreenOn(state.isOnline)
    val snackbarHost = remember { SnackbarHostState() }
    var reportSheetType by remember { mutableStateOf<TrafficType?>(null) }
    // Simpan offer terakhir supaya animasi keluar tetap punya data.
    var lastOffer by remember { mutableStateOf<OrderOffer?>(null) }
    state.offer?.let { lastOffer = it }

    LaunchedEffect(state.snackbar?.id) {
        val message = state.snackbar ?: return@LaunchedEffect
        controller.consumeSnackbar(message.id)
        snackbarHost.showSnackbar(message.text)
    }

    Box(Modifier.fillMaxSize().background(Ojol.Background)) {
        val trip = state.trip
        val showNav = trip == null && state.offer == null
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    trip != null -> TripScreen(state, trip, location, voice, controller, onOpenReport = { reportSheetType = TrafficType.MACET })
                    state.tab == HomeTab.BERANDA ->
                        HomeScreen(state, location, voice, controller, onOpenReport = { reportSheetType = TrafficType.MACET })
                    state.tab == HomeTab.PENDAPATAN -> EarningsScreen(state.earnings, state.completedTrips)
                    state.tab == HomeTab.LALU_LINTAS -> TrafficScreen(
                        reports = state.reports,
                        onQuickReport = { reportSheetType = it },
                        onVoiceReport = controller::onMicPressed,
                    )
                    else -> VoiceScreen(
                        state = state,
                        voice = voice,
                        onMic = controller::onMicPressed,
                        onTry = controller::submitTypedCommand,
                        onHandsFree = controller::setHandsFree,
                        onWakeWord = controller::setWakeWord,
                        onReadAloud = controller::setReadMessagesAloud,
                        onAutobid = controller::setAutobid,
                    )
                }
            }
            if (showNav) BottomNav(state.tab, controller::selectTab)
        }

        AnimatedVisibility(
            visible = state.offer != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            lastOffer?.let { offer ->
                OfferOverlay(
                    offer = state.offer ?: offer,
                    voice = voice,
                    onAccept = controller::acceptOffer,
                    onReject = controller::rejectOffer,
                    onMic = controller::onMicPressed,
                )
            }
        }

        AnimatedVisibility(
            visible = state.showChat && trip != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            state.trip?.let { activeTrip ->
                ChatScreen(
                    trip = activeTrip,
                    messages = state.chat,
                    voice = voice,
                    onBack = controller::closeChat,
                    onSend = controller::sendChat,
                    onDictate = controller::startChatDictation,
                    onCall = controller::callCustomer,
                )
            }
        }

        val voiceBusy = voice.phase != VoicePhase.IDLE &&
            (voice.context == ListenContext.GENERAL || voice.context == ListenContext.AWAITING_CHAT)
        val showPanel = state.offer == null && !state.showChat && (voice.panelPinned || voiceBusy)
        AnimatedVisibility(
            visible = showPanel,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            VoiceAssistantPanel(
                voice = voice,
                suggestions = voiceSuggestions(state, wakeReady = state.wakeWord && sdkVoice.wakeUnavailable == null),
                onMic = controller::onMicPressed,
                onClose = controller::closeVoicePanel,
                onSubmit = controller::submitTypedCommand,
                modifier = Modifier
                    .background(Color.White)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        }

        SnackbarHost(
            snackbarHost,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp),
        ) { data ->
            Snackbar(containerColor = Ojol.TextPrimary, contentColor = Color.White) { Text(data.visuals.message) }
        }

        state.lastCompleted?.let { completed ->
            if (state.offer == null) TripCompletedDialog(completed, state.earnings.today, controller::dismissCompleted)
        }

        reportSheetType?.let { type ->
            TrafficReportSheet(
                initialType = type,
                currentRoad = CityMap.roadAt(location.position).name,
                onSubmit = { t, severity, place ->
                    controller.reportTraffic(t, severity, place)
                    reportSheetType = null
                },
                onDismiss = { reportSheetType = null },
            )
        }
    }
}

@Composable
private fun BottomNav(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 11.sp, fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ojol.GreenDark,
                    selectedTextColor = Ojol.GreenDark,
                    indicatorColor = Ojol.GreenLight,
                    unselectedIconColor = Ojol.TextSecondary,
                    unselectedTextColor = Ojol.TextSecondary,
                ),
            )
        }
    }
}

private fun HomeTab.icon(): ImageVector = when (this) {
    HomeTab.BERANDA -> Icons.Filled.Home
    HomeTab.PENDAPATAN -> Icons.Filled.AccountBalanceWallet
    HomeTab.LALU_LINTAS -> Icons.Filled.Traffic
    HomeTab.SUARA -> Icons.Filled.RecordVoiceOver
}

/** Contoh perintah yang relevan dengan kondisi saat ini. */
private fun voiceSuggestions(state: DriverUiState, wakeReady: Boolean): List<String> {
    val trip = state.trip
    return when {
        state.offer != null -> listOf("Terima", "Tolak", "Ulangi")
        trip != null -> when (trip.stage) {
            TripStage.TO_PICKUP -> listOf("Sudah sampai", "Chat saya sudah dekat", "Lapor macet di sini", "Telepon penumpang", "Baca pesan")
            TripStage.AT_PICKUP -> listOf("Mulai perjalanan", "Chat saya sudah di titik jemput", "Telepon penumpang")
            TripStage.TO_DROPOFF -> listOf("Sudah sampai", "Lapor kecelakaan di depan", "Chat sebentar lagi sampai")
            TripStage.AT_DROPOFF -> listOf("Selesai", "Chat terima kasih sudah order")
        }
        !state.isOnline -> listOf("Mulai narik", "Pendapatan hari ini", "Lapor banjir di Kemang Raya", "Bantuan")
        else -> listOf("Lapor macet parah di Jalan Sudirman", if (wakeReady) "Halo Mitra, pendapatan hari ini" else "Pendapatan hari ini", "Nyalakan autobid", "Offline", "Bantuan")
    }
}
