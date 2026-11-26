package id.ojolmitra.driver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.ojolmitra.driver.model.HotspotLevel
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficType

/** Palet hijau khas aplikasi mitra ojol. */
object Ojol {
    val Green = Color(0xFF00AA13)
    val GreenDark = Color(0xFF008A10)
    val GreenDeep = Color(0xFF0B5E1B)
    val GreenLight = Color(0xFFE5F7E7)
    val GreenSoft = Color(0xFFC6EECB)
    val Orange = Color(0xFFF26B21)
    val Red = Color(0xFFE53935)
    val FoodRed = Color(0xFFEE2737)
    val SendBlue = Color(0xFF0E8FCF)
    val Amber = Color(0xFFFFB300)
    val Purple = Color(0xFF6C4BD8)

    val Background = Color(0xFFF3F4F6)
    val Surface = Color.White
    val TextPrimary = Color(0xFF1B1D1F)
    val TextSecondary = Color(0xFF6B7079)
    val TextHint = Color(0xFF9AA0A6)
    val Divider = Color(0xFFE6E8EB)
    val Chip = Color(0xFFF1F2F4)

    // Peta
    val MapLand = Color(0xFFEFEFE8)
    val MapBlock = Color(0xFFE6E6DE)
    val MapBuilding = Color(0xFFDCDCD3)
    val MapPark = Color(0xFFCDE9C4)
    val MapWater = Color(0xFFAFD6F2)
    val MapRoad = Color.White
    val MapRoadCasing = Color(0xFFD6D6CF)
    val MapMajorRoad = Color(0xFFFFF1C2)
    val MapMajorCasing = Color(0xFFEACB7F)
    val MapLabel = Color(0xFF7B7F86)
}

fun ServiceType.color(): Color = when (this) {
    ServiceType.RIDE -> Ojol.Green
    ServiceType.FOOD -> Ojol.FoodRed
    ServiceType.SEND -> Ojol.SendBlue
}

fun TrafficType.color(): Color = when (this) {
    TrafficType.MACET -> Ojol.Red
    TrafficType.KECELAKAAN -> Color(0xFFD32F2F)
    TrafficType.BANJIR -> Color(0xFF1E88E5)
    TrafficType.DITUTUP -> Color(0xFF5D4037)
    TrafficType.RAZIA -> Color(0xFF3949AB)
    TrafficType.JALAN_RUSAK -> Ojol.Orange
    TrafficType.DEMO -> Ojol.Purple
}

fun HotspotLevel.color(): Color = when (this) {
    HotspotLevel.SANGAT_RAMAI -> Color(0xFFE53935)
    HotspotLevel.RAMAI -> Color(0xFFF26B21)
    HotspotLevel.SEDANG -> Color(0xFFF4A300)
}

fun Severity.color(): Color = when (this) {
    Severity.RINGAN -> Ojol.Amber
    Severity.SEDANG -> Ojol.Orange
    Severity.PARAH -> Ojol.Red
}

private val colors = lightColorScheme(
    primary = Ojol.Green,
    onPrimary = Color.White,
    primaryContainer = Ojol.GreenLight,
    onPrimaryContainer = Ojol.GreenDeep,
    secondary = Ojol.GreenDark,
    onSecondary = Color.White,
    background = Ojol.Background,
    onBackground = Ojol.TextPrimary,
    surface = Ojol.Surface,
    onSurface = Ojol.TextPrimary,
    surfaceVariant = Ojol.Chip,
    onSurfaceVariant = Ojol.TextSecondary,
    outline = Ojol.Divider,
    error = Ojol.Red,
)

private val base = Typography()
private val typography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

val CaptionStyle = TextStyle(fontSize = 12.sp, color = Ojol.TextSecondary)

@Composable
fun OjolTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
