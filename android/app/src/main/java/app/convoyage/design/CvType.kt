package app.convoyage.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.convoyage.R

/** Bundled fonts (res/font, SIL OFL): Chakra Petch (display), Manrope (sans), JetBrains Mono (numbers). */
val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

val Manrope = FontFamily(
    listOf(500, 600, 700).map { w ->
        Font(R.font.manrope_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val JetBrainsMono = FontFamily(
    listOf(500, 600).map { w ->
        Font(R.font.jetbrains_mono_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** Tabular figures so changing numbers do not jitter. */
private const val TNUM = "tnum"

/** Text styles (no colour; apply colour at the call site). */
class CvTypography {
    val displayXl = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = 0.01.em)
    val displayL = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = 0.01.em)
    val title = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyS = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 20.sp)
    val bodyStrong = bodyS.copy(fontWeight = FontWeight.W700)

    /** 12sp / 700 / +0.12em; use with uppercase text. */
    val label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W700, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.12.em)
    val button = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W700, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.06.em)
    val telemetryXl = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W600, fontSize = 72.sp, lineHeight = 72.sp, letterSpacing = (-0.04).em, fontFeatureSettings = TNUM)
    val telemetryL = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W600, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM)
    val telemetry = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM)
}

val CvTypeface = CvTypography()
