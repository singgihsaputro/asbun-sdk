package id.ojolmitra.driver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import id.ojolmitra.driver.model.CompletedTrip
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.formatRupiah
import id.ojolmitra.driver.ui.components.Pill
import id.ojolmitra.driver.ui.theme.Ojol

@Composable
fun TripCompletedDialog(trip: CompletedTrip, todayTotal: Int, onDismiss: () -> Unit) {
    val order = trip.order
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Ojol.Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Order selesai!", style = MaterialTheme.typography.titleLarge)
            Text(
                "${order.service.label} • ${order.pickup.name} → ${order.dropoff.name}",
                fontSize = 12.sp,
                color = Ojol.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("Pendapatanmu", fontSize = 12.sp, color = Ojol.TextSecondary)
            Text("+${formatRupiah(trip.earning)}", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ojol.GreenDark)
            if (order.payment == PaymentMethod.CASH) {
                Pill("Tunai diterima ${formatRupiah(order.fare)}", Color(0xFFFFF4E0), Color(0xFFB26A00), modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Rating dari ${order.customer.firstName}", fontSize = 12.sp, color = Ojol.TextSecondary)
            Row(Modifier.padding(top = 2.dp)) {
                repeat(5) { i ->
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (i < trip.customerRating) Ojol.Amber else Ojol.Divider,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Text("Total hari ini ${formatRupiah(todayTotal)}", fontSize = 12.sp, color = Ojol.TextSecondary, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ojol.Green),
            ) {
                Text("Lanjut narik", fontWeight = FontWeight.Bold)
            }
        }
    }
}
