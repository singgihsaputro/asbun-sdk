package id.ojolmitra.driver.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.model.OrderOffer
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.model.formatKm
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.ui.components.Avatar
import id.ojolmitra.driver.ui.components.InlineVoiceBar
import id.ojolmitra.driver.ui.components.PaymentChip
import id.ojolmitra.driver.ui.components.Pill
import id.ojolmitra.driver.ui.components.RouteStops
import id.ojolmitra.driver.ui.components.ServiceBadge
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color
import id.ojolmitra.driver.voice.VoiceUiState

/** Kartu order masuk dengan hitung mundur, detail rute, dan perintah suara terima/tolak. */
@Composable
fun OfferOverlay(
    offer: OrderOffer,
    voice: VoiceUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onMic: () -> Unit,
) {
    val order = offer.order
    val progress by animateFloatAsState(offer.secondsLeft / offer.totalSeconds.toFloat(), tween(900), label = "countdown")
    val urgent = offer.secondsLeft <= 7

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.10f), 1f to Color.Black.copy(alpha = 0.45f))),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Color.White)
                .navigationBarsPadding(),
        ) {
            // bar hitung mundur
            Box(Modifier.fillMaxWidth().height(6.dp).background(Ojol.GreenLight)) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(if (urgent) Ojol.Orange else Ojol.Green),
                )
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceBadge(order.service, large = true)
                    Spacer(Modifier.width(8.dp))
                    PaymentChip(order.payment)
                    Spacer(Modifier.weight(1f))
                    CountdownRing(offer.secondsLeft, progress, urgent)
                }
                Spacer(Modifier.height(10.dp))
                Text("Order baru masuk!", fontSize = 13.sp, color = Ojol.TextSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatRupiah(order.driverEarning), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Ojol.TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("pendapatanmu", fontSize = 12.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    if (order.bonus > 0) {
                        Pill("+${formatRupiah(order.bonus)} bonus", Color(0xFFFFF4E0), Color(0xFFB26A00), modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Text(
                    "Tarif ${formatRupiah(order.fare)} • ${formatKm(order.tripDistanceKm)} • ±${order.estimatedTripMinutes} mnt",
                    fontSize = 12.sp,
                    color = Ojol.TextSecondary,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Ojol.Divider)

                RouteStops(
                    pickupTitle = order.pickup.name,
                    pickupSubtitle = order.pickup.address,
                    pickupCaption = "JEMPUT • ${formatKm(order.pickupDistanceKm)} dari kamu",
                    dropoffTitle = order.dropoff.name,
                    dropoffSubtitle = order.dropoff.address,
                    dropoffCaption = "TUJUAN • ${formatKm(order.tripDistanceKm)}",
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Avatar(order.customer.initials, size = 34.dp, color = order.service.color())
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(order.customer.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Star, null, tint = Ojol.Amber, modifier = Modifier.size(14.dp))
                            Text(order.customer.rating.toString(), fontSize = 12.sp, color = Ojol.TextSecondary)
                        }
                        val detail = when (order.service) {
                            ServiceType.RIDE -> order.note
                            else -> (order.items.joinToString(", ") + (order.note?.let { " • $it" } ?: ""))
                        }
                        if (!detail.isNullOrBlank()) {
                            Text(
                                detail,
                                fontSize = 12.sp,
                                color = Ojol.TextSecondary,
                                fontStyle = FontStyle.Italic,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                InlineVoiceBar(voice, hint = "ucapkan “Terima” atau “Tolak”", onMic = onMic)
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(0.36f).height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Text("Lewati", color = Ojol.TextSecondary, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(0.64f).height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ojol.Green),
                    ) {
                        Text("Terima", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownRing(secondsLeft: Int, progress: Float, urgent: Boolean) {
    val color = if (urgent) Ojol.Orange else Ojol.Green
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(48.dp)) {
            val stroke = 4.dp.toPx()
            drawArc(
                Ojol.GreenLight,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke),
            )
            drawArc(
                color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$secondsLeft", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = color)
            Text("dtk", fontSize = 9.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(top = 0.dp))
        }
    }
}
