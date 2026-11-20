package id.ojolmitra.driver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.ui.theme.Ojol
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tombol geser untuk konfirmasi tahap order (mencegah salah pencet saat berkendara). */
@Composable
fun SlideToConfirm(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ojol.Green,
) {
    val confirm by rememberUpdatedState(onConfirm)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(density) { 52.dp.toPx() }
    val paddingPx = with(density) { 4.dp.toPx() }
    val maxOffset = (widthPx - thumbPx - paddingPx * 2).coerceAtLeast(1f)
    val offset = remember { Animatable(0f) }
    val progress = (offset.value / maxOffset).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(color)
            .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(with(density) { (offset.value + thumbPx + paddingPx * 2).toDp() })
                .background(Color.Black.copy(alpha = 0.12f)),
        )
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(start = 60.dp, end = 20.dp).alpha(1f - progress * 0.8f),
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(4.dp)
                .size(52.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset)) }
                    },
                    onDragStopped = {
                        if (offset.value > maxOffset * 0.78f) {
                            offset.animateTo(maxOffset)
                            confirm()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = "Geser", tint = color, modifier = Modifier.size(28.dp))
        }
    }
}
