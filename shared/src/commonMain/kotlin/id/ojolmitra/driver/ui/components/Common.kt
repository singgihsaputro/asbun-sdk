package id.ojolmitra.driver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color

fun ServiceType.icon(): ImageVector = when (this) {
    ServiceType.RIDE -> Icons.Filled.TwoWheeler
    ServiceType.FOOD -> Icons.Filled.Restaurant
    ServiceType.SEND -> Icons.Filled.Inventory2
}

@Composable
fun ServiceBadge(service: ServiceType, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(service.color())
            .padding(horizontal = if (large) 12.dp else 9.dp, vertical = if (large) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(service.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(if (large) 18.dp else 14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            service.label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 15.sp else 12.sp,
        )
    }
}

@Composable
fun PaymentChip(payment: PaymentMethod, modifier: Modifier = Modifier) {
    val cash = payment == PaymentMethod.CASH
    Pill(
        text = payment.label,
        background = if (cash) Color(0xFFFFF4E0) else Color(0xFFE6F2FF),
        content = if (cash) Color(0xFFB26A00) else Color(0xFF1565C0),
        icon = if (cash) Icons.Filled.Payments else Icons.Filled.AccountBalanceWallet,
        modifier = modifier,
    )
}

@Composable
fun Pill(
    text: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 44.dp, color: Color = Ojol.Green) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun StatItem(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Ojol.TextPrimary) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = valueColor)
        Text(label, fontSize = 11.sp, color = Ojol.TextSecondary, maxLines = 1)
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(Ojol.Divider))
}

/** Titik jemput (bulat hijau) atau tujuan (pin oranye) dengan garis putus-putus di antaranya. */
@Composable
fun RouteStops(
    pickupTitle: String,
    pickupSubtitle: String,
    pickupCaption: String?,
    dropoffTitle: String,
    dropoffSubtitle: String,
    dropoffCaption: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        RouteStopRow(isPickup = true, title = pickupTitle, subtitle = pickupSubtitle, caption = pickupCaption, showConnector = true)
        RouteStopRow(isPickup = false, title = dropoffTitle, subtitle = dropoffSubtitle, caption = dropoffCaption, showConnector = false)
    }
}

@Composable
private fun RouteStopRow(isPickup: Boolean, title: String, subtitle: String, caption: String?, showConnector: Boolean) {
    Row {
        Box(Modifier.width(26.dp).height(if (showConnector) 62.dp else 44.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val top = 9.dp.toPx()
                if (showConnector) {
                    drawLine(
                        color = Ojol.TextHint,
                        start = Offset(cx, top + 9.dp.toPx()),
                        end = Offset(cx, size.height + 2.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }
                if (isPickup) {
                    drawCircle(Ojol.Green, radius = 8.dp.toPx(), center = Offset(cx, top))
                    drawCircle(Color.White, radius = 3.5.dp.toPx(), center = Offset(cx, top))
                } else {
                    drawCircle(Ojol.Orange, radius = 8.dp.toPx(), center = Offset(cx, top))
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(cx, top))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            if (caption != null) {
                Text(caption, fontSize = 11.sp, color = if (isPickup) Ojol.GreenDark else Ojol.Orange, fontWeight = FontWeight.SemiBold)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = Ojol.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kartu putih membulat dengan bayangan lembut. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ojol.Surface)
            .border(1.dp, Ojol.Divider, RoundedCornerShape(16.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}
