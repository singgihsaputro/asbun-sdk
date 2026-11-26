package id.ojolmitra.driver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.core.DriverLocation
import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.model.Hotspot
import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficReport
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.ui.theme.Ojol
import id.ojolmitra.driver.ui.theme.color
import kotlin.math.max
import kotlin.math.min

/**
 * Peta kota simulasi yang digambar dengan Canvas Compose.
 *
 * @param overview jika tidak null, kamera menampilkan semua titik ini (preview order);
 *                 jika null kamera mengikuti driver.
 * @param hotspots area ramai yang digambar sebagai heat map, bernomor sesuai urutan daftar.
 */
@Composable
fun CityMapView(
    location: DriverLocation,
    reports: List<TrafficReport>,
    modifier: Modifier = Modifier,
    pickup: MapPoint? = null,
    dropoff: MapPoint? = null,
    previewPickupLeg: List<MapPoint> = emptyList(),
    previewTripLeg: List<MapPoint> = emptyList(),
    overview: List<MapPoint>? = null,
    online: Boolean = true,
    zoom: Float = 1f,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    hotspots: List<Hotspot> = emptyList(),
) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val labels = remember(measurer) { MapLabels(measurer) }
    val density = LocalDensity.current

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )

    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val topPx = with(density) { topInset.toPx() }
        val bottomPx = with(density) { bottomInset.toPx() }
        val visibleH = max(120f, heightPx - topPx - bottomPx)

        val followScale = with(density) { 0.95.dp.toPx() } * zoom
        val (targetCenter, targetScale) = if (overview != null && overview.isNotEmpty()) {
            val minX = overview.minOf { it.x }
            val maxX = overview.maxOf { it.x }
            val minY = overview.minOf { it.y }
            val maxY = overview.maxOf { it.y }
            val pad = with(density) { 56.dp.toPx() }
            val sx = (widthPx - pad * 2) / max(80f, maxX - minX)
            val sy = (visibleH - pad * 1.6f) / max(80f, maxY - minY)
            MapPoint((minX + maxX) / 2f, (minY + maxY) / 2f) to min(sx, sy).coerceIn(followScale * 0.35f, followScale * 1.3f)
        } else {
            location.position to followScale
        }

        val center by animateOffsetAsState(
            Offset(targetCenter.x, targetCenter.y),
            spring(stiffness = Spring.StiffnessLow),
            label = "center",
        )
        val scale by animateFloatAsState(targetScale, spring(stiffness = Spring.StiffnessLow), label = "scale")

        Canvas(Modifier.fillMaxSize()) {
            val cam = Camera(center, scale, Offset(size.width / 2f, topPx + visibleH / 2f))
            drawBase(cam)
            drawTraffic(cam, reports)
            if (hotspots.isNotEmpty()) drawHeat(cam, hotspots, pulse)
            if (previewPickupLeg.size > 1) drawRoute(cam, previewPickupLeg, Ojol.GreenDark, dashed = true)
            if (previewTripLeg.size > 1) drawRoute(cam, previewTripLeg, Ojol.Green, dashed = false)
            if (location.remainingRoute.size > 1) drawRoute(cam, location.remainingRoute, Ojol.Green, dashed = false)
            drawLabels(cam, labels)
            drawReports(cam, reports, labels, pulse)
            if (hotspots.isNotEmpty()) drawHotspotBadges(cam, hotspots, labels)
            pickup?.let { drawPin(cam.toScreen(it), Ojol.Green) }
            dropoff?.let { drawPin(cam.toScreen(it), Ojol.Orange) }
            drawDriver(cam.toScreen(location.position), location.heading, online, pulse)
        }
    }
}

private class Camera(val center: Offset, val scale: Float, val screenCenter: Offset) {
    fun toScreen(p: MapPoint) = Offset((p.x - center.x) * scale + screenCenter.x, (p.y - center.y) * scale + screenCenter.y)
    fun toScreen(x: Float, y: Float) = Offset((x - center.x) * scale + screenCenter.x, (y - center.y) * scale + screenCenter.y)
}

/** Label statis diukur sekali saja supaya tiap frame ringan. */
private class MapLabels(measurer: androidx.compose.ui.text.TextMeasurer) {
    private val roadStyle = TextStyle(fontSize = 10.sp, color = Ojol.MapLabel, fontWeight = FontWeight.Medium)
    private val parkStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF3F7D3A), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)
    private val placeStyle = TextStyle(fontSize = 9.sp, color = Color(0xFF6D6F73))
    private val emojiStyle = TextStyle(fontSize = 13.sp)

    val roads: List<TextLayoutResult> = CityMap.roads.map { measurer.measure(it.name, roadStyle) }
    val parks: List<TextLayoutResult> = CityMap.parks.map { measurer.measure(it.name, parkStyle) }
    val places: List<TextLayoutResult> = CityMap.places.map { measurer.measure(it.name, placeStyle) }
    val emoji: Map<TrafficType, TextLayoutResult> = TrafficType.entries.associateWith { measurer.measure(it.emoji, emojiStyle) }
    val numbers: List<TextLayoutResult> = (1..9).map {
        measurer.measure("$it", TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold))
    }
}

private fun DrawScope.drawBase(cam: Camera) {
    drawRect(Ojol.MapLand)
    val s = cam.scale
    val lines = CityMap.gridLines
    // blok kota
    for (col in 0 until lines.size - 1) for (row in 0 until lines.size - 1) {
        val inset = 16f
        val tl = cam.toScreen(lines[col] + inset, lines[row] + inset)
        drawRoundRect(
            Ojol.MapBlock,
            topLeft = tl,
            size = Size((lines[col + 1] - lines[col] - inset * 2) * s, (lines[row + 1] - lines[row] - inset * 2) * s),
            cornerRadius = CornerRadius(10f * s),
        )
    }
    CityMap.parks.forEach { park ->
        val a = park.area
        drawRoundRect(
            Ojol.MapPark,
            topLeft = cam.toScreen(a.left + 16f, a.top + 16f),
            size = Size((a.right - a.left - 32f) * s, (a.bottom - a.top - 32f) * s),
            cornerRadius = CornerRadius(14f * s),
        )
    }
    CityMap.buildings.forEach { b ->
        drawRoundRect(
            Ojol.MapBuilding,
            topLeft = cam.toScreen(b.left, b.top),
            size = Size((b.right - b.left) * s, (b.bottom - b.top) * s),
            cornerRadius = CornerRadius(3f * s),
        )
    }
    // sungai
    val river = Path()
    CityMap.river.forEachIndexed { i, p ->
        val o = cam.toScreen(p)
        if (i == 0) river.moveTo(o.x, o.y) else river.lineTo(o.x, o.y)
    }
    drawPath(river, Ojol.MapWater, style = Stroke(width = 34f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // jalan: casing dulu, lalu isi, supaya persimpangan rapi
    val ends = -200f to CityMap.SIZE + 200f
    CityMap.roads.forEach { road ->
        val (a, b) = roadEnds(cam, road.vertical, road.coord, ends)
        drawLine(if (road.major) Ojol.MapMajorCasing else Ojol.MapRoadCasing, a, b, strokeWidth = (if (road.major) 32f else 23f) * s)
    }
    CityMap.roads.forEach { road ->
        val (a, b) = roadEnds(cam, road.vertical, road.coord, ends)
        drawLine(if (road.major) Ojol.MapMajorRoad else Ojol.MapRoad, a, b, strokeWidth = (if (road.major) 26f else 17f) * s)
    }
}

private fun roadEnds(cam: Camera, vertical: Boolean, coord: Float, ends: Pair<Float, Float>): Pair<Offset, Offset> =
    if (vertical) cam.toScreen(coord, ends.first) to cam.toScreen(coord, ends.second)
    else cam.toScreen(ends.first, coord) to cam.toScreen(ends.second, coord)

/** Ruas jalan di sekitar laporan macet/kecelakaan/ditutup diwarnai seperti layer traffic. */
private fun DrawScope.drawTraffic(cam: Camera, reports: List<TrafficReport>) {
    reports.forEach { r ->
        val color = when (r.type) {
            TrafficType.MACET, TrafficType.KECELAKAAN -> when (r.severity) {
                Severity.PARAH -> Color(0xFFD32F2F)
                Severity.SEDANG -> Color(0xFFF4511E)
                Severity.RINGAN -> Color(0xFFFFA000)
            }
            TrafficType.DITUTUP -> Color(0xFF6D4C41)
            TrafficType.BANJIR -> Color(0xFF1E88E5)
            else -> return@forEach
        }
        val road = CityMap.roadAt(r.position)
        val half = if (r.severity == Severity.PARAH) 150f else 105f
        val p = road.nearestPoint(r.position)
        val (a, b) = if (road.vertical) MapPoint(p.x, p.y - half) to MapPoint(p.x, p.y + half)
        else MapPoint(p.x - half, p.y) to MapPoint(p.x + half, p.y)
        drawLine(
            color.copy(alpha = 0.85f),
            cam.toScreen(a),
            cam.toScreen(b),
            strokeWidth = (if (road.major) 11f else 8f) * cam.scale,
            cap = StrokeCap.Round,
            pathEffect = if (r.type == TrafficType.DITUTUP) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
        )
    }
}

/**
 * Heat map: gradasi hangat di tiap area ramai. Makin banyak calon penumpang per driver,
 * makin besar dan makin merah; yang sedang berlangsung berdenyut pelan.
 */
private fun DrawScope.drawHeat(cam: Camera, hotspots: List<Hotspot>, pulse: Float) {
    hotspots.sortedBy { it.heat }.forEach { spot ->
        val o = cam.toScreen(spot.position)
        val breathe = if (spot.ongoing) 1f + 0.06f * kotlin.math.sin(pulse * 2f * kotlin.math.PI.toFloat()) else 1f
        val radius = (120f + 150f * spot.heat) * cam.scale * breathe
        val core = heatColor(spot.heat)
        drawCircle(
            Brush.radialGradient(
                0f to core.copy(alpha = if (spot.ongoing) 0.62f else 0.42f),
                0.45f to core.copy(alpha = if (spot.ongoing) 0.32f else 0.2f),
                1f to core.copy(alpha = 0f),
                center = o,
                radius = radius,
            ),
            radius = radius,
            center = o,
        )
    }
}

/** Kuning (lumayan) -> oranye -> merah (sangat ramai). */
private fun heatColor(heat: Float): Color {
    val yellow = Color(0xFFFFC107)
    val orange = Color(0xFFFF7A1A)
    val red = Color(0xFFE53935)
    return if (heat < 0.6f) lerp(yellow, orange, ((heat - 0.25f) / 0.35f).coerceIn(0f, 1f))
    else lerp(orange, red, ((heat - 0.6f) / 0.4f).coerceIn(0f, 1f))
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Lingkaran bernomor 1, 2, 3, ... sama dengan nomor di daftar dan yang dibacakan asisten. */
private fun DrawScope.drawHotspotBadges(cam: Camera, hotspots: List<Hotspot>, labels: MapLabels) {
    hotspots.forEachIndexed { i, spot ->
        if (i >= labels.numbers.size) return
        val o = cam.toScreen(spot.position)
        val r = 11.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.18f), radius = r + 1.dp.toPx(), center = o + Offset(0f, 1.5.dp.toPx()))
        drawCircle(Color.White, radius = r + 2.dp.toPx(), center = o)
        drawCircle(spot.level.color(), radius = r, center = o)
        val n = labels.numbers[i]
        drawText(n, topLeft = Offset(o.x - n.size.width / 2f, o.y - n.size.height / 2f))
    }
}

private fun DrawScope.drawRoute(cam: Camera, route: List<MapPoint>, color: Color, dashed: Boolean) {
    val path = Path()
    route.forEachIndexed { i, p ->
        val o = cam.toScreen(p)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    val w = 6.dp.toPx()
    if (!dashed) {
        drawPath(path, Ojol.GreenDeep, style = Stroke(width = w + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = if (dashed) w * 0.8f else w,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18f, 14f)) else null,
        ),
    )
}

private fun DrawScope.drawLabels(cam: Camera, labels: MapLabels) {
    val w = size.width
    val h = size.height
    CityMap.roads.forEachIndexed { i, road ->
        val layout = labels.roads[i]
        var t = 250f + (i % 3) * 120f
        while (t < CityMap.SIZE) {
            val o = if (road.vertical) cam.toScreen(road.coord, t) else cam.toScreen(t, road.coord)
            if (o.x in -200f..w + 200f && o.y in -200f..h + 200f) {
                val tl = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f)
                if (road.vertical) rotate(-90f, pivot = o) { drawText(layout, topLeft = tl) } else drawText(layout, topLeft = tl)
            }
            t += 600f
        }
    }
    CityMap.parks.forEachIndexed { i, park ->
        val layout = labels.parks[i]
        val o = cam.toScreen(park.area.center)
        drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
    }
    if (cam.scale > 0.6f.dp.toPx()) {
        CityMap.places.forEachIndexed { i, place ->
            val o = cam.toScreen(place.position)
            if (o.x !in -100f..w + 100f || o.y !in -100f..h + 100f) return@forEachIndexed
            drawCircle(Color.White, radius = 4.dp.toPx(), center = o)
            drawCircle(Color(0xFF8D9096), radius = 2.6.dp.toPx(), center = o)
            val layout = labels.places[i]
            drawText(layout, topLeft = Offset(o.x + 6.dp.toPx(), o.y - layout.size.height - 1.dp.toPx()))
        }
    }
}

private fun DrawScope.drawReports(cam: Camera, reports: List<TrafficReport>, labels: MapLabels, pulse: Float) {
    reports.forEach { r ->
        val o = cam.toScreen(r.position)
        val color = r.type.color()
        if (r.isMine) {
            drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = (16 + 18 * pulse).dp.toPx(), center = o)
        }
        drawCircle(Color.Black.copy(alpha = 0.12f), radius = 16.dp.toPx(), center = o + Offset(0f, 1.5.dp.toPx()))
        drawCircle(if (r.isMine) Ojol.Green else Color.White, radius = 16.dp.toPx(), center = o)
        drawCircle(color.copy(alpha = 0.15f).compositeOverWhite(), radius = 13.dp.toPx(), center = o)
        val e = labels.emoji.getValue(r.type)
        drawText(e, topLeft = Offset(o.x - e.size.width / 2f, o.y - e.size.height / 2f))
    }
}

private fun Color.compositeOverWhite(): Color =
    Color(red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha), alpha = 1f)

private fun DrawScope.drawPin(o: Offset, color: Color) {
    val r = 12.dp.toPx()
    val head = Offset(o.x, o.y - 26.dp.toPx())
    drawOval(Color.Black.copy(alpha = 0.18f), topLeft = Offset(o.x - 8.dp.toPx(), o.y - 3.dp.toPx()), size = Size(16.dp.toPx(), 6.dp.toPx()))
    val stem = Path().apply {
        moveTo(head.x - r * 0.62f, head.y + r * 0.7f)
        lineTo(o.x, o.y)
        lineTo(head.x + r * 0.62f, head.y + r * 0.7f)
        close()
    }
    drawPath(stem, color)
    drawCircle(Color.White, radius = r + 2.dp.toPx(), center = head)
    drawCircle(color, radius = r, center = head)
    drawCircle(Color.White, radius = r * 0.42f, center = head)
}

private fun DrawScope.drawDriver(o: Offset, heading: Float, online: Boolean, pulse: Float) {
    val main = if (online) Ojol.Green else Color(0xFF8D9096)
    if (online) {
        drawCircle(main.copy(alpha = 0.22f * (1f - pulse)), radius = (18 + 26 * pulse).dp.toPx(), center = o)
    }
    drawCircle(main.copy(alpha = 0.16f), radius = 24.dp.toPx(), center = o)
    drawCircle(Color.Black.copy(alpha = 0.15f), radius = 15.dp.toPx(), center = o + Offset(0f, 2.dp.toPx()))
    drawCircle(Color.White, radius = 15.dp.toPx(), center = o)
    drawCircle(main, radius = 12.dp.toPx(), center = o)
    // panah navigasi (digambar menghadap ke atas lalu diputar sesuai heading)
    rotate(heading + 90f, pivot = o) {
        val a = 7.dp.toPx()
        val arrow = Path().apply {
            moveTo(o.x, o.y - a)
            lineTo(o.x + a * 0.75f, o.y + a * 0.8f)
            lineTo(o.x, o.y + a * 0.35f)
            lineTo(o.x - a * 0.75f, o.y + a * 0.8f)
            close()
        }
        drawPath(arrow, Color.White)
    }
}
