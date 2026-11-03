package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvCard
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.design.gridBackdrop

/** Placeholder vehicle data. TODO(garage): real vehicles come from the server; none exist yet. */
data class Vehicle(val id: String, val name: String, val spec: String, val kind: String, val paint: Color, val paintName: String)

val SampleVehicles = listOf(
    Vehicle("gr-yaris", "GR Yaris", "Pearl white · 18\" bronze", "CAR", Color(0xFFF2F0EA), "Pearl white"),
    Vehicle("mt-09", "MT-09", "Cyan storm · stock", "BIKE", Color(0xFF2DE2E6), "Cyan storm"),
)

@Composable
fun GarageListScreen(onOpen: (String) -> Unit) {
    val c = Cv.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().padding(top = Space.s6, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        TitleBlock("YOUR GARAGE · ${SampleVehicles.size}", "Garage")
        SampleVehicles.forEach { v ->
            CvCard(Modifier.fillMaxWidth(), edge = c.lineStrong, shape = CutLShape, onClick = { onOpen(v.id) }) {
                Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    VehicleViewport(v, Modifier.fillMaxWidth().height(120.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            CvText(v.name, Cv.type.title, c.ink)
                            CvText(v.spec, Cv.type.bodyS, c.inkMuted)
                        }
                        CvIcon(CvIcons.Chevron, c.inkMuted)
                    }
                }
            }
        }
        // TODO(garage): "Add vehicle" and scan flows.
        Eyebrow("ADD A VEHICLE · COMING SOON")
    }
}

@Composable
fun GarageDetailScreen(vehicleId: String, onBack: () -> Unit) {
    val c = Cv.colors
    val v = SampleVehicles.firstOrNull { it.id == vehicleId } ?: SampleVehicles.first()
    var section by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            Column {
                Eyebrow("GARAGE · ${v.kind}")
                CvText(v.name, Cv.type.title, c.ink)
            }
        }

        VehicleViewport(v, Modifier.fillMaxWidth().height(250.dp), large = true)

        Segmented(
            listOf(SegOption("Paint"), SegOption("Wheels"), SegOption("Stance"), SegOption("Decals")),
            section, { section = it }, Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("COLOUR")
                CvText(v.paintName, Cv.type.bodyS, c.ink)
            }
            // Swatches are decorative until the renderer can take paint.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                listOf(Color(0xFFF2F0EA), Color(0xFF2DE2E6), Color(0xFFFF3D8B), Color(0xFFFFB020), Color(0xFF6F97FF), Color(0xFF46E08C)).forEachIndexed { i, col ->
                    Box(
                        Modifier.size(Space.target).clip(CircleShape).background(col, CircleShape)
                            .border(if (i == 0) 2.dp else 1.dp, if (i == 0) c.signal else c.lineStrong, CircleShape),
                    )
                }
            }
        }
        Eyebrow("CUSTOMISATION IS PREVIEW ONLY IN THIS VERSION")
    }
}

/**
 * PLACEHOLDER: this is where the PS1-style 3D vehicle renderer goes (low-poly model, 240p render target,
 * drag to rotate / pinch to zoom). Nothing is rendered yet; it draws a labelled stand-in.
 *
 * TODO(garage-3d): replace the body with the renderer view (GLSurfaceView / Filament / custom GL) and feed
 * it the vehicle's model, paint, wheels and stance.
 */
@Composable
fun VehicleViewport(vehicle: Vehicle, modifier: Modifier = Modifier, large: Boolean = false) {
    val c = Cv.colors
    Box(
        modifier.cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            CvIcon(CvIcons.Cube, vehicle.paint, size = if (large) 64.dp else 40.dp)
            Eyebrow("PS1 RENDERER GOES HERE")
            if (large) Eyebrow("DRAG TO ROTATE · PINCH TO ZOOM", color = c.signal)
        }
        Box(Modifier.align(Alignment.TopStart).padding(Space.s2).cutPanel(c.surface200, c.line, CutSShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Eyebrow("PLACEHOLDER · 240P")
        }
    }
}
