package app.convoyage.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colour tokens from the Convoyage HUD design system (tokens.css). Night is the default. */
data class CvColors(
    val isNight: Boolean,
    val void: Color,
    val surface100: Color,
    val surface200: Color,
    val surface300: Color,
    val glass: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val signal: Color,
    val signalInk: Color,
    val signalDim: Color,
    val pulse: Color,
    val pulseInk: Color,
    val hazard: Color,
    val hazardInk: Color,
    val police: Color,
    val go: Color,
    val lead: Color,
)

val NightColors = CvColors(
    isNight = true,
    void = Color(0xFF05080D),
    surface100 = Color(0xFF0A1018),
    surface200 = Color(0xFF101A26),
    surface300 = Color(0xFF172434),
    glass = Color(0xB80C141E),
    line = Color(0xFF1E2F42),
    lineStrong = Color(0xFF2F4862),
    ink = Color(0xFFE8F1FA),
    inkMuted = Color(0xFF8EA4BA),
    signal = Color(0xFF2DE2E6),
    signalInk = Color(0xFF021416),
    signalDim = Color(0x242DE2E6),
    pulse = Color(0xFFFF3D8B),
    pulseInk = Color(0xFF1A0610),
    hazard = Color(0xFFFFB020),
    hazardInk = Color(0xFF1D1200),
    police = Color(0xFF6F97FF),
    go = Color(0xFF46E08C),
    lead = Color(0xFFC9B6FF),
)

val DayColors = CvColors(
    isNight = false,
    void = Color(0xFFE9EFF4),
    surface100 = Color(0xFFFFFFFF),
    surface200 = Color(0xFFF2F6F9),
    surface300 = Color(0xFFE2E9EF),
    glass = Color(0xD1FFFFFF),
    line = Color(0xFFC9D4DE),
    lineStrong = Color(0xFF8A9CAD),
    ink = Color(0xFF06121E),
    inkMuted = Color(0xFF3D5064),
    signal = Color(0xFF00777D),
    signalInk = Color(0xFFFFFFFF),
    signalDim = Color(0x1A00777D),
    pulse = Color(0xFFC4004F),
    pulseInk = Color(0xFFFFFFFF),
    hazard = Color(0xFF8F5400),
    hazardInk = Color(0xFFFFFFFF),
    police = Color(0xFF2443C4),
    go = Color(0xFF137A3E),
    lead = Color(0xFF5B3FC0),
)

/** Spacing scale (4/8/12/16/24/32/48) and touch targets. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    val gutter = 16.dp
    val target = 48.dp
    val targetRide = 72.dp
    val ptt = 96.dp
}

val LocalCvColors = staticCompositionLocalOf { NightColors }

/** Accessor: `Cv.colors.signal`, `Cv.type.title`. */
object Cv {
    val colors: CvColors @Composable @ReadOnlyComposable get() = LocalCvColors.current
    val type: CvTypography get() = CvTypeface
}

/** @param night true = night (default), false = day, null = follow the system. */
@Composable
fun CvTheme(night: Boolean? = true, content: @Composable () -> Unit) {
    val dark = night ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalCvColors provides if (dark) NightColors else DayColors, content = content)
}
