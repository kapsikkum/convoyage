package app.convoyage.ui

import app.convoyage.design.CutMShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.ApiResult
import app.convoyage.api.Build
import app.convoyage.api.CreateVehicle
import app.convoyage.api.UpdateVehicle
import app.convoyage.api.Vehicle
import app.convoyage.api.VehicleOptions
import app.convoyage.data.BuildText
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.render.Ps1Spec
import app.convoyage.design.cutPanel
import kotlinx.coroutines.launch

/** A compact vehicle row (the garage list and the profile's Garage tab). */
@Composable
fun VehicleRow(v: Vehicle, onClick: () -> Unit, tall: Boolean = true) {
    val c = Cv.colors
    CvCard(Modifier.fillMaxWidth(), edge = if (v.isPrimary) c.signal else c.lineStrong, shape = CutLShape, onClick = onClick) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (tall) VehicleViewport(v, Modifier.fillMaxWidth().height(120.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                        if (!tall) CvIcon(kindIcon(v.kind), parsePaint(v.build.paint) ?: c.inkMuted, size = 20.dp)
                        CvText(v.displayName, Cv.type.title, c.ink, maxLines = 1)
                    }
                    CvText(buildSummary(v), Cv.type.bodyS, c.inkMuted, maxLines = 1)
                }
                if (v.isPrimary) Box(Modifier.cutPanel(c.surface300, c.signal, CutSShape).padding(horizontal = 8.dp, vertical = 4.dp)) { Eyebrow("PRIMARY", color = c.signal) }
                else if (v.isSold) Box(Modifier.cutPanel(c.surface300, c.lineStrong, CutSShape).padding(horizontal = 8.dp, vertical = 4.dp)) { Eyebrow("SOLD") }
                else CvIcon(CvIcons.Chevron, c.inkMuted)
            }
        }
    }
}

@Composable
fun GarageListScreen(onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val container = LocalContainer.current
    val garage by container.garage.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.garage.refresh() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().padding(top = Space.s6, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            TitleBlock("YOUR GARAGE · ${garage.owned.size}", "Garage")
            CvIconButton(CvIcons.Plus, "Add a vehicle", onAdd)
        }
        garage.error?.let {
            Banner(BannerTone.Hazard, "Could not load your garage", sub = it, icon = CvIcons.Alert, actionLabel = "Retry", onAction = container.garage::refresh)
        }
        if (!garage.loaded && garage.error == null) Eyebrow("LOADING...")
        garage.owned.forEach { v -> VehicleRow(v, onClick = { onOpen(v.id) }) }
        if (garage.loaded && garage.owned.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvText("Nothing in the garage yet.", Cv.type.body, Cv.colors.inkMuted)
                CvButton("Add your first vehicle", onAdd, Modifier.fillMaxWidth(), icon = CvIcons.Plus)
            }
        }
        if (garage.sold.isNotEmpty()) {
            Eyebrow("SOLD · ${garage.sold.size}")
            garage.sold.forEach { v -> VehicleRow(v, onClick = { onOpen(v.id) }, tall = false) }
        }
    }
}

@Composable
fun GarageDetailScreen(vehicleId: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val ridesState by container.rides.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { container.garage.refresh(); container.rides.refresh() }
    val v = garage.vehicles.firstOrNull { it.id == vehicleId }
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(label: String, call: suspend () -> ApiResult<*>, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            when (val r = call()) {
                is ApiResult.Ok -> after()
                is ApiResult.Err -> error = r.message
            }
            busy = false
            confirm = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            Column(Modifier.weight(1f)) {
                Eyebrow("GARAGE · ${v?.let { VehicleOptions.kindLabel(it.kind).uppercase() } ?: ""}")
                CvText(v?.displayName ?: "Vehicle", Cv.type.title, c.ink, maxLines = 1)
            }
            if (v != null) CvIconButton(CvIcons.Edit, "Edit vehicle", { onEdit(v.id) }, fill = c.surface200)
        }

        if (v == null) {
            Eyebrow(if (garage.loaded) "THIS VEHICLE IS GONE" else "LOADING...")
            return@Column
        }

        VehicleViewport(v, Modifier.fillMaxWidth().height(250.dp), live = true)

        CvText("${listOfNotNull(v.year?.toString(), v.make, v.model).joinToString(" ")}", Cv.type.bodyS, c.inkMuted)

        val stats = ridesState.let { container.rides.totals(v.id) }
        Eyebrow("LIFETIME · ${if (ridesState.loaded) "" else "LOADING "}FROM YOUR RIDES ON THIS VEHICLE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Rides", "${stats.rides}", Modifier.weight(1f))
            Readout("Distance", Fmt.km(stats.distanceM), Modifier.weight(1f), unit = "km")
            Fmt.hoursOrMinutes(stats.durationS).let { (n, u) -> Readout("Seat time", n, Modifier.weight(1f), unit = u) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Top speed", Fmt.kmh(stats.topSpeedMps), Modifier.weight(1f), unit = "km/h")
            Readout("Longest", Fmt.km(stats.longestM), Modifier.weight(1f), unit = "km")
        }

        Eyebrow("BUILD")
        CvCard(Modifier.fillMaxWidth(), shape = CutLShape) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                BuildLine("Paint", VehicleOptions.paintName(v.build.paint) + (v.build.paint?.let { "  $it" } ?: ""))
                BuildLine("Finish", v.build.finish?.replaceFirstChar { it.uppercase() } ?: "Stock")
                BuildLine("Body", app.convoyage.render.Ps1Body.of(v.build.body, app.convoyage.render.Ps1Kind.of(v.kind)).label)
                BuildLine("Wheels", VehicleOptions.wheelLabel(v.build.wheel))
                BuildLine("Ride height", v.build.rideHeightMm?.let { (if (it > 0) "+" else "") + "$it mm" } ?: "Stock")
                BuildLine("Decals", BuildText.textOf(v.build).ifBlank { "None" })
            }
        }

        error?.let { Banner(BannerTone.Pulse, it) }

        if (!v.isSold) {
            if (!v.isPrimary) CvButton("Make primary", { run("primary", { container.garage.makePrimary(v.id) }) }, Modifier.fillMaxWidth(), icon = CvIcons.Check, enabled = !busy)
            else Eyebrow("THIS IS YOUR PRIMARY VEHICLE · RIDES RECORD ON IT", color = c.signal)
            CvButton(
                if (confirm == "sold") "Tap again to mark sold" else "Mark as sold",
                { if (confirm == "sold") run("sold", { container.garage.markSold(v.id) }) else confirm = "sold" },
                Modifier.fillMaxWidth(), if (confirm == "sold") CvButtonKind.Alert else CvButtonKind.Ghost, enabled = !busy,
            )
        } else {
            Eyebrow("SOLD · KEPT IN YOUR GARAGE HISTORY")
        }
        CvButton(
            if (confirm == "delete") "Tap again to delete for good" else "Delete vehicle",
            { if (confirm == "delete") run("delete", { container.garage.delete(v.id) }, after = onBack) else confirm = "delete" },
            Modifier.fillMaxWidth(), if (confirm == "delete") CvButtonKind.Alert else CvButtonKind.Ghost, icon = CvIcons.Trash, enabled = !busy,
        )
        if (confirm == "delete") Eyebrow("RIDES KEEP THEIR STATS BUT LOSE THE VEHICLE. POSTS ABOUT IT ARE REMOVED.")
    }
}

@Composable
private fun BuildLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Eyebrow(label)
        CvText(value, Cv.type.bodyS, Cv.colors.ink, maxLines = 2, modifier = Modifier.weight(1f).padding(start = Space.s4), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Add (vehicleId == null) or edit. */
@Composable
fun GarageEditScreen(vehicleId: String?, onDone: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val existing = vehicleId?.let { id -> garage.vehicles.firstOrNull { it.id == id } }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Close, "Cancel", onDone, fill = c.surface200)
            Column {
                Eyebrow("GARAGE")
                CvText(if (vehicleId == null) "Add a vehicle" else "Edit vehicle", Cv.type.title, c.ink)
            }
        }
        if (vehicleId != null && existing == null) {
            Eyebrow(if (garage.loaded) "THIS VEHICLE IS GONE" else "LOADING...")
        } else {
            // Re-key on the vehicle so the fields start from it, not from a previous edit.
            androidx.compose.runtime.key(existing?.id) { VehicleForm(existing, onDone) }
        }
    }
}

@Composable
private fun VehicleForm(existing: Vehicle?, onDone: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: "car") }
    var make by rememberSaveable { mutableStateOf(existing?.make ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var nickname by rememberSaveable { mutableStateOf(existing?.nickname ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year?.toString() ?: "") }
    var paint by rememberSaveable { mutableStateOf(existing?.build?.paint ?: VehicleOptions.PAINTS[0].first) }
    var finish by rememberSaveable { mutableStateOf(existing?.build?.finish ?: "gloss") }
    var wheel by rememberSaveable { mutableStateOf(existing?.build?.wheel ?: "stock") }
    var height by rememberSaveable { mutableIntStateOf(existing?.build?.rideHeightMm ?: 0) }
    var body by rememberSaveable { mutableStateOf(existing?.build?.body) }
    val bodyNow = app.convoyage.render.Ps1Body.of(body, app.convoyage.render.Ps1Kind.of(kind))
    var decals by rememberSaveable { mutableStateOf(existing?.build?.let(BuildText::textOf) ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    VehicleViewport(Ps1Spec.of(kind, paint, finish, wheel, height, bodyNow.wire), Modifier.fillMaxWidth().height(220.dp), live = true, caption = finish.uppercase())

    Eyebrow("TYPE")
    Segmented(
        listOf(SegOption("Car", CvIcons.Car), SegOption("Bike", CvIcons.Bike), SegOption("Ute", CvIcons.Ute), SegOption("4WD", CvIcons.Fourwd)),
        VehicleOptions.KINDS.indexOf(kind).coerceAtLeast(0), { kind = VehicleOptions.KINDS[it] }, Modifier.fillMaxWidth(),
    )

    Eyebrow("BODY · ${bodyNow.label.uppercase()}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        app.convoyage.render.Ps1Body.forKind(app.convoyage.render.Ps1Kind.of(kind)).forEach { b ->
            val on = b == bodyNow
            Column(
                Modifier.width(112.dp).cutPanel(if (on) c.surface300 else c.surface200, if (on) c.signal else c.line, CutMShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { body = b.wire }
                    .semantics { contentDescription = b.label }
                    .padding(Space.s1),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ps1Thumb(Ps1Spec.of(kind, paint, finish, wheel, height, b.wire), app.convoyage.render.ThumbSize.Card, Modifier.fillMaxWidth().height(70.dp))
                CvText(b.label, Cv.type.label, if (on) c.signal else c.ink, maxLines = 1)
            }
        }
    }

    CvTextField(make, { make = it.take(60) }, "Make", placeholder = "Toyota")
    CvTextField(model, { model = it.take(60) }, "Model", placeholder = "GR Yaris")
    CvTextField(nickname, { nickname = it.take(60) }, "Nickname (optional)", placeholder = "Yari")
    CvTextField(
        year, { year = it.filter(Char::isDigit).take(4) }, "Year (optional)", placeholder = "2023", mono = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    Eyebrow("PAINT · ${VehicleOptions.paintName(paint).uppercase()}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        VehicleOptions.PAINTS.forEach { (hex, name) ->
            val on = hex.equals(paint, true)
            Box(
                Modifier.size(Space.target).clip(CircleShape).background(parsePaint(hex) ?: Color.Gray, CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) c.signal else c.lineStrong, CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { paint = hex }
                    .semantics { contentDescription = name },
            )
        }
    }

    Eyebrow("FINISH")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        VehicleOptions.FINISHES.forEach { f -> CvChipToggle(f, finish == f, { finish = f }) }
    }

    Eyebrow("WHEELS")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        VehicleOptions.WHEELS.forEach { (id, label) -> CvChipToggle(label, wheel == id, { wheel = id }) }
        if (VehicleOptions.WHEELS.none { it.first == wheel }) CvChipToggle(wheel, true, {})
    }

    Eyebrow("RIDE HEIGHT")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        CvIconButton(CvIcons.Minus, "Lower 10 millimetres", { height = (height - 10).coerceAtLeast(-200) }, fill = c.surface200)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CvText((if (height > 0) "+" else "") + "$height mm", Cv.type.telemetryL, c.ink)
            Eyebrow(if (height == 0) "STOCK" else if (height < 0) "LOWERED" else "RAISED")
        }
        CvIconButton(CvIcons.Plus, "Raise 10 millimetres", { height = (height + 10).coerceAtMost(200) }, fill = c.surface200)
    }

    CvTextField(decals, { decals = it.take(400) }, "Decals (free text, separate with commas)", placeholder = "Racing stripes, #7 door", singleLine = false, maxLines = 3)

    error?.let { Banner(BannerTone.Pulse, it) }
    val ready = make.isNotBlank() && model.isNotBlank() && !saving
    CvButton(
        if (saving) "Saving..." else "Save vehicle",
        {
            saving = true
            error = null
            val build = Build(paint = paint, finish = finish, wheel = wheel, rideHeightMm = height, decals = BuildText.decalsOf(decals), body = bodyNow.wire)
            val yearInt = year.toIntOrNull()?.takeIf { it in 1885..2028 }
            scope.launch {
                val r = if (existing == null) {
                    container.garage.create(CreateVehicle(kind, make.trim(), model.trim(), yearInt, nickname.trim().ifBlank { null }, build))
                } else {
                    container.garage.update(existing.id, UpdateVehicle(kind, make.trim(), model.trim(), yearInt, nickname.trim(), build))
                }
                saving = false
                when (r) {
                    is ApiResult.Ok -> onDone()
                    is ApiResult.Err -> error = r.message
                }
            }
        },
        Modifier.fillMaxWidth(), enabled = ready,
    )
    if (year.isNotEmpty() && year.toIntOrNull()?.let { it in 1885..2028 } != true) Eyebrow("YEAR MUST BE 1885 TO 2028, OR LEAVE IT BLANK")
    if (existing != null) Eyebrow("A YEAR CAN BE CHANGED BUT NOT CLEARED YET")
}
