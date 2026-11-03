package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.RideTile
import app.convoyage.design.Space
import app.convoyage.design.TileTone

private data class Hazard(val label: String, val icon: ImageVector, val tone: TileTone)

/**
 * Hazard report picker. UI only: the v1 protocol has no hazard message yet.
 * TODO(protocol): send a hazard report once the schema defines one, and show hazards as map pins.
 */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val c = Cv.colors
    var sent by remember { mutableStateOf<String?>(null) }
    val hazards = listOf(
        Hazard("Debris", CvIcons.Alert, TileTone.Hazard),
        Hazard("Police", CvIcons.Alert, TileTone.Default),
        Hazard("Crash", CvIcons.Stop, TileTone.Alert),
        Hazard("Wet / ice", CvIcons.Alert, TileTone.Hazard),
    )
    Column(
        Modifier.fillMaxSize().screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            Column { Eyebrow("HAZARD"); CvText("Report", Cv.type.title, c.ink) }
        }
        sent?.let { Banner(BannerTone.Hazard, "$it noted on this device", sub = "Sharing with the convoy needs the next server version.", icon = CvIcons.Alert) }
        hazards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                row.forEach { h -> RideTile(h.label, Modifier.weight(1f), icon = h.icon, tone = h.tone, onClick = { sent = h.label }) }
            }
        }
    }
}
