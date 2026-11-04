package app.convoyage.design

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.convoyage.api.VehicleBadge
import app.convoyage.data.ImageSource
import app.convoyage.render.ThumbSize
import app.convoyage.ui.LocalContainer
import app.convoyage.ui.Ps1Thumb
import app.convoyage.ui.ps1
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Uppercase eyebrow / caption in the label style. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Cv.colors.inkMuted, align: TextAlign? = null) {
    CvText(text.uppercase(), Cv.type.label, color, modifier, textAlign = align)
}

// ---- Buttons ---------------------------------------------------------------------------------

enum class CvButtonKind { Primary, Ghost, Alert, Ride }

/** Chamfered button. Primary carries the one signal glow of its region. */
@Composable
fun CvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CvButtonKind = CvButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** Tighter padding and gap, for rows of several buttons (e.g. three across on Home). */
    compact: Boolean = false,
) {
    val c = Cv.colors
    val (fill, edge, ink) = when (kind) {
        CvButtonKind.Primary -> Triple(c.signal, c.signal, c.signalInk)
        CvButtonKind.Ghost -> Triple(Color.Transparent, c.lineStrong, c.ink)
        CvButtonKind.Alert -> Triple(c.pulse, c.pulse, c.pulseInk)
        CvButtonKind.Ride -> Triple(c.surface200, c.lineStrong, c.ink)
    }
    val height = if (kind == CvButtonKind.Ride) Space.targetRide else Space.target
    val glowing = kind == CvButtonKind.Primary && enabled
    Row(
        modifier = modifier
            .then(if (glowing) Modifier.glow(c.signal.copy(alpha = 0.6f), CutMShape, 10.dp) else Modifier)
            .cutPanel(fill = if (enabled) fill else c.surface300, edge = if (enabled) edge else c.line, shape = CutMShape)
            .defaultMinSize(minHeight = height)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) Space.s2 else Space.s4),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else Space.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) ink else c.inkMuted
        if (icon != null) CvIcon(icon, tint, size = if (compact) 18.dp else 20.dp)
        FitText(text.uppercase(), Cv.type.button, tint, Modifier.weight(1f, fill = false))
    }
}

/**
 * One line of text that never gets cut off: if it does not fit it shrinks (down to 70%) and, as a last
 * resort, tightens its letter-spacing. Used for button labels, which must stay readable in a narrow slot.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    var scale by remember(text, style) { mutableStateOf(1f) }
    val shrunk = if (scale >= 1f) style else style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = style.lineHeight * scale,
        letterSpacing = style.letterSpacing * scale,
    )
    CvText(
        text, shrunk, color, modifier,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && scale > 0.7f) scale = (scale - 0.06f).coerceAtLeast(0.7f) },
    )
}

/** 48dp square icon button on a glass chamfer (map controls, headers). */
@Composable
fun CvIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Cv.colors.ink,
    edge: Color = Cv.colors.lineStrong,
    fill: Color = Cv.colors.glass,
    size: Dp = Space.target,
) {
    Box(
        modifier = modifier
            .size(size)
            .cutPanel(fill, edge, CutSShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CvIcon(icon, tint) }
}

/** Small chamfered toggle chip (3D, SPOTS...). */
@Composable
fun CvChipToggle(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cv.colors
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .cutPanel(if (selected) c.signal else c.glass, if (selected) c.signal else c.lineStrong, CutSShape)
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = Space.s3),
        contentAlignment = Alignment.Center,
    ) {
        CvText(text.uppercase(), Cv.type.label, if (selected) c.signalInk else c.ink)
    }
}

// ---- Segmented -------------------------------------------------------------------------------

data class SegOption(val label: String, val icon: ImageVector? = null)

@Composable
fun Segmented(
    options: List<SegOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    iconsOnly: Boolean = false,
) {
    val c = Cv.colors
    Row(
        modifier = modifier
            .cutPanel(c.surface200, c.lineStrong, CutSShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val on = i == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(CutSShape)
                    .background(if (on) c.signal else Color.Transparent, CutSShape)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { contentDescription = opt.label }
                    .padding(horizontal = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (on) c.signalInk else c.ink
                if (opt.icon != null) CvIcon(opt.icon, tint, size = 18.dp)
                if (!iconsOnly) CvText(opt.label, Cv.type.button.copy(letterSpacing = 0.02.sp), tint, maxLines = 1)
            }
        }
    }
}

// ---- Avatar / RosterChip ---------------------------------------------------------------------

enum class MemberState { Online, Lead, Stopped, Offline }

@Composable
fun ringColor(state: MemberState): Color = when (state) {
    MemberState.Online -> Cv.colors.go
    MemberState.Lead -> Cv.colors.lead
    MemberState.Stopped -> Cv.colors.pulse
    MemberState.Offline -> Cv.colors.lineStrong
}

/**
 * The one avatar every person in the app gets: round, and physically round so the exception to the chamfer rule applies.
 *
 * - [photoUrl] (a server media path such as `/v1/media/<id>/thumb`) is the main picture when it has loaded.
 * - [vehicle], their primary vehicle, is its PS1 render: a badge at the bottom right (about 42% of the diameter,
 *   overlapping the edge, ringed in surface-100) when there is a photo, and the main picture when there is not.
 * - With neither, the [initials].
 *
 * [state] adds the convoy ring (and a crown for the leader).
 */
@Composable
fun CvAvatar(
    photoUrl: String?,
    vehicle: VehicleBadge?,
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    state: MemberState? = null,
) {
    val c = Cv.colors
    val images = LocalContainer.current.images
    val px = with(LocalDensity.current) { (size * 2).roundToPx().coerceIn(64, 512) }
    val source = remember(photoUrl) { photoUrl?.takeIf { it.isNotBlank() }?.let { ImageSource.Remote(it) } }
    var photo by remember(source, px) { mutableStateOf(source?.let { images.cached(it, px) }) }
    LaunchedEffect(source, px) {
        if (source != null && photo == null) photo = images.load(source, px)
    }
    val shownPhoto = photo
    Box(modifier = modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size + 8.dp)
                .then(if (state != null) Modifier.border(2.dp, ringColor(state), CircleShape) else Modifier),
        )
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(c.surface300, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                shownPhoto != null -> Image(shownPhoto, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                vehicle != null -> Ps1Thumb(
                    vehicle.ps1(), ThumbSize.Badge, Modifier.fillMaxSize(), ContentScale.Fit,
                    fallback = { CvText(initials, Cv.type.label.copy(letterSpacing = 0.sp), c.ink) },
                )
                else -> CvText(initials, Cv.type.label.copy(letterSpacing = 0.sp), if (state == MemberState.Offline) c.inkMuted else c.ink)
            }
        }
        if (shownPhoto != null && vehicle != null) {
            val badge = size * 0.42f
            val ring = 2.dp
            val whole = badge + ring * 2
            // Centre of the badge sits on the circle's edge at 45 degrees, bottom right.
            val at = (size + 8.dp) / 2 + size / 2 * 0.7071f - whole / 2
            Box(
                Modifier.align(Alignment.TopStart).offset(x = at, y = at).size(whole)
                    .clip(CircleShape).background(c.surface100, CircleShape).padding(ring)
                    .clip(CircleShape).background(c.surface300, CircleShape),
            ) {
                Ps1Thumb(vehicle.ps1(), ThumbSize.Badge, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        if (state == MemberState.Lead) {
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(c.lead, CircleShape),
                contentAlignment = Alignment.Center,
            ) { CvIcon(CvIcons.Crown, c.void, size = 10.dp) }
        }
    }
}

@Composable
fun RosterChip(
    initials: String,
    name: String,
    meta: String,
    state: MemberState,
    distance: String?,
    distanceCaption: String?,
    modifier: Modifier = Modifier,
    metaColor: Color = Cv.colors.inkMuted,
    photoUrl: String? = null,
    vehicle: VehicleBadge? = null,
) {
    val c = Cv.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .cutPanel(c.surface200, if (state == MemberState.Stopped) c.pulse else c.line, CutSShape)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CvAvatar(photoUrl, vehicle, initials, state = state)
        Column(Modifier.weight(1f)) {
            CvText(name, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CvText(meta, Cv.type.bodyS, metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (distance != null) {
            Column(horizontalAlignment = Alignment.End) {
                CvText(distance, Cv.type.telemetry.copy(fontSize = 16.sp), c.ink)
                if (distanceCaption != null) CvText(distanceCaption.uppercase(), Cv.type.label.copy(fontSize = 10.sp, lineHeight = 12.sp), c.inkMuted)
            }
        }
    }
}

// ---- Banner ----------------------------------------------------------------------------------

enum class BannerTone { Pulse, Hazard, Police, Go }

@Composable
fun toneColor(tone: BannerTone): Color = when (tone) {
    BannerTone.Pulse -> Cv.colors.pulse
    BannerTone.Hazard -> Cv.colors.hazard
    BannerTone.Police -> Cv.colors.police
    BannerTone.Go -> Cv.colors.go
}

/** Glass banner with a tone mark, title, sub line and an optional action. */
@Composable
fun Banner(
    tone: BannerTone,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector = CvIcons.Alert,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** An optional second, quieter action next to [actionLabel] (e.g. Dismiss beside Confirm). */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = Cv.colors
    val tc = toneColor(tone)
    val twoActions = secondaryLabel != null && onSecondary != null && actionLabel != null && onAction != null
    @Composable
    fun ActionButtons() {
        if (secondaryLabel != null && onSecondary != null) BannerAction(secondaryLabel, c.inkMuted, c.lineStrong, onSecondary)
        if (actionLabel != null && onAction != null) BannerAction(actionLabel, tc, tc, onAction)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .cutPanel(c.glass, tc, CutMShape)
            .padding(Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).cutPanel(tc, tc, CutSShape),
                contentAlignment = Alignment.Center,
            ) { CvIcon(icon, if (tone == BannerTone.Hazard) c.hazardInk else c.void, size = 22.dp) }
            Column(Modifier.weight(1f)) {
                CvText(title, Cv.type.bodyStrong, c.ink, maxLines = 2)
                if (sub != null) CvText(sub, Cv.type.bodyS, c.inkMuted, maxLines = 2)
            }
            // One action sits beside the text; two would squeeze it on a phone, so they get their own row.
            if (!twoActions) ActionButtons()
        }
        if (twoActions) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.End)) { ActionButtons() }
        }
    }
}

@Composable
private fun BannerAction(label: String, ink: Color, edge: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = Space.target, minHeight = Space.target)
            .cutPanel(Color.Transparent, edge, CutSShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s3),
        contentAlignment = Alignment.Center,
    ) { CvText(label.uppercase(), Cv.type.label, ink) }
}

// ---- Readout / RideTile ----------------------------------------------------------------------

/** Caption + mono number with an optional unit. Numbers are tabular so they do not jitter. */
@Composable
fun Readout(
    caption: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueStyle: TextStyle = Cv.type.telemetryL,
    valueColor: Color = Cv.colors.ink,
    align: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CvText(caption.uppercase(), Cv.type.label, Cv.colors.inkMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            CvText(value, valueStyle, valueColor, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                CvText(
                    unit.uppercase(),
                    Cv.type.label,
                    Cv.colors.inkMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

enum class TileTone { Default, Signal, Hazard, Police, Alert }

/** Large Ride-mode tile (>=72dp tall, cut-l). */
@Composable
fun RideTile(
    caption: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    unit: String? = null,
    icon: ImageVector? = null,
    tone: TileTone = TileTone.Default,
    valueColor: Color? = null,
    onClick: () -> Unit = {},
) {
    val c = Cv.colors
    val (fill, edge, ink) = when (tone) {
        TileTone.Default -> Triple(c.surface200, c.lineStrong, c.ink)
        TileTone.Signal -> Triple(c.surface200, c.signal, c.signal)
        TileTone.Hazard -> Triple(c.surface200, c.hazard, c.hazard)
        TileTone.Police -> Triple(c.surface200, c.police, c.police)
        TileTone.Alert -> Triple(c.pulse, c.pulse, c.pulseInk)
    }
    Column(
        modifier = modifier
            .cutPanel(fill, edge, CutLShape)
            .defaultMinSize(minHeight = 104.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterVertically),
    ) {
        if (icon != null) CvIcon(icon, ink, size = 28.dp)
        if (value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                CvText(value, Cv.type.telemetryL, valueColor ?: ink, maxLines = 1)
                if (unit != null) {
                    CvText(" " + unit.uppercase(), Cv.type.label, c.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        CvText(caption.uppercase(), Cv.type.label, if (tone == TileTone.Alert) c.pulseInk else c.inkMuted)
    }
}

// ---- Push to talk ----------------------------------------------------------------------------

/** Idle: ready. Live: the mic is open (pulse colour). Receiving: somebody else is talking. Connecting / Unavailable: no voice yet. */
enum class PttState { Idle, Live, Receiving, Connecting, Unavailable }

/** Round push-to-talk button (physically round). [caption] sits under it: who is talking, or "Voice unavailable". */
@Composable
fun PushToTalk(
    state: PttState,
    modifier: Modifier = Modifier,
    size: Dp = Space.ptt,
    label: String? = null,
    caption: String? = null,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
) {
    val c = Cv.colors
    val (fill, edge, ink) = when (state) {
        PttState.Idle -> Triple(c.glass, c.lineStrong, c.signal)
        PttState.Live -> Triple(c.pulse, c.pulse, c.pulseInk)
        PttState.Receiving -> Triple(c.surface200, c.go, c.go)
        PttState.Connecting -> Triple(c.glass, c.line, c.inkMuted)
        PttState.Unavailable -> Triple(c.surface300, c.line, c.inkMuted)
    }
    // While live the button breathes, so it is obvious from the corner of an eye that the mic is open.
    val pulse by rememberInfiniteTransition(label = "ptt").animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "ptt-scale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(if (state == PttState.Live) pulse else 1f)
                .then(if (state == PttState.Live) Modifier.glow(c.pulse, CircleShape, 18.dp) else Modifier)
                .clip(CircleShape)
                .background(fill, CircleShape)
                .border(BorderStroke(if (state == PttState.Idle) 1.dp else 2.dp, edge), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    })
                }
                .semantics {
                    contentDescription = when (state) {
                        PttState.Unavailable -> "Voice unavailable. Press to retry"
                        PttState.Connecting -> "Voice connecting"
                        else -> "Hold to talk"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CvIcon(CvIcons.Mic, ink, size = size / 3)
                CvText(
                    label ?: when (state) {
                        PttState.Live -> "LIVE"
                        PttState.Receiving -> "RX"
                        PttState.Unavailable -> "OFF"
                        PttState.Connecting -> "..."
                        PttState.Idle -> "HOLD"
                    },
                    Cv.type.label.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    ink,
                )
            }
        }
        if (caption != null) {
            CvText(
                caption.uppercase(), Cv.type.label.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.06.sp),
                if (state == PttState.Receiving) c.go else c.inkMuted,
                Modifier.padding(top = 4.dp).widthIn(max = 88.dp), maxLines = 2, textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Sheet / panels --------------------------------------------------------------------------

/** Bottom sheet surface: cut-l chamfer, 1dp line-strong edge, grab handle. Tap the handle to collapse. */
@Composable
fun CvSheet(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = Cv.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sheetPanel(c.surface100, c.lineStrong)
            .animateContentSize()
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Space.target)
                .then(if (onToggle != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle,
                ) else Modifier)
                .semantics { contentDescription = if (expanded) "Collapse sheet" else "Expand sheet" },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = Space.s2).width(40.dp).height(4.dp).clip(CutSShape).background(c.lineStrong, CutSShape))
        }
        content()
    }
}

/** Plain chamfered card with the standard edge. */
@Composable
fun CvCard(
    modifier: Modifier = Modifier,
    fill: Color = Cv.colors.surface200,
    edge: Color = Cv.colors.lineStrong,
    shape: Shape = CutMShape,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .cutPanel(fill, edge, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) { content() }
}

// ---- Text field ------------------------------------------------------------------------------

@Composable
fun CvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    mono: Boolean = false,
    enabled: Boolean = true,
    /** Called when the field gains focus (e.g. to scroll its form above the keyboard). */
    onFocused: () -> Unit = {},
    /** False for notes and posts: the field grows to [maxLines] and Enter starts a new line. */
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
) {
    val c = Cv.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        if (label.isNotEmpty()) Eyebrow(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            textStyle = (if (mono) Cv.type.telemetry.copy(fontSize = 16.sp) else Cv.type.body).copy(color = c.ink),
            cursorBrush = SolidColor(c.signal),
            modifier = Modifier
                .fillMaxWidth()
                .cutPanel(c.surface100, if (focused) c.signal else c.lineStrong, CutSShape)
                .defaultMinSize(minHeight = Space.target)
                .onFocusChanged { if (it.isFocused && !focused) onFocused(); focused = it.isFocused },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = Space.s3, vertical = Space.s3), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) CvText(placeholder, Cv.type.body, c.inkMuted)
                    inner()
                }
            },
        )
    }
}

// ---- Floating glass tab bar --------------------------------------------------------------------

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", CvIcons.Home),
    Feed("Feed", CvIcons.Feed),
    Drive("Drive", CvIcons.Navigate),
    Garage("Garage", CvIcons.Garage),
    Profile("Profile", CvIcons.Profile),
}

@Composable
fun CvTabBar(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Cv.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .cutPanel(c.glass, c.lineStrong, CutLShape)
            .padding(horizontal = Space.s2, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            if (tab == Tab.Drive) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .glow(c.signal.copy(alpha = 0.6f), CutMShape, 12.dp)
                            .cutPanel(c.signal, c.signal, CutMShape)
                            .clickable(role = Role.Button) { onSelect(tab) }
                            .semantics { contentDescription = "Drive" },
                        contentAlignment = Alignment.Center,
                    ) { CvIcon(tab.icon, c.signalInk, size = 28.dp) }
                }
            } else {
                val on = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = Space.target)
                        .clip(CutSShape)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = Space.s1),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    CvIcon(tab.icon, if (on) c.signal else c.inkMuted, size = 24.dp)
                    CvText(tab.label, Cv.type.label.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.04.sp), if (on) c.signal else c.inkMuted)
                }
            }
        }
    }
}

// ---- Map pins (Compose overlays) ---------------------------------------------------------------

enum class PinKind { Me, Member, Lead, Stopped, Offline, Destination, Hazard, Police, Start, End, Photo, Stop }

/**
 * Round map pin: heading wedge behind a round dot ringed by state. Only physically round things are round.
 * [heading] in degrees clockwise from north (map rotation must already be applied by the caller).
 */
@Composable
fun MapPin(
    kind: PinKind,
    label: String,
    modifier: Modifier = Modifier,
    heading: Float? = null,
    tag: String? = null,
    /** A member's primary vehicle: its PS1 render replaces the initials in the dot. */
    vehicle: VehicleBadge? = null,
    /** The map camera's tilt: a vehicle marker is drawn from the same angle so it sits on the road. */
    tiltDeg: Float = 0f,
) {
    val c = Cv.colors
    val ring = when (kind) {
        PinKind.Me -> c.signal
        PinKind.Member -> c.go
        PinKind.Lead -> c.lead
        PinKind.Stopped -> c.pulse
        PinKind.Offline -> c.lineStrong
        PinKind.Destination -> c.ink
        PinKind.Hazard -> c.hazard
        PinKind.Police -> c.police
        PinKind.Start -> c.go
        PinKind.End -> c.pulse
        PinKind.Photo -> c.hazard
        PinKind.Stop -> c.inkMuted
    }
    // A person with a vehicle shows as it, from above, turned the way they are going.
    if (vehicle != null && kind in CAR_KINDS) {
        CarMarker(kind, ring, vehicle, heading, tag, tiltDeg, modifier, fallback = { MapPin(kind, label, Modifier, heading, tag, null) })
        return
    }
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (heading != null) {
            Canvas(Modifier.size(56.dp).rotate(heading)) {
                val cx = size.width / 2
                val p = Path().apply {
                    moveTo(cx, 0f)
                    lineTo(cx + 9.dp.toPx(), 18.dp.toPx())
                    lineTo(cx - 9.dp.toPx(), 18.dp.toPx())
                    close()
                }
                drawPath(p, ring.copy(alpha = 0.85f))
            }
        }
        Box(
            Modifier
                .size(32.dp)
                .then(if (kind == PinKind.Me) Modifier.glow(c.signal, CircleShape, 12.dp) else Modifier)
                .clip(CircleShape)
                .background(if (kind == PinKind.Me) c.signal else c.surface100, CircleShape)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == PinKind.Me) CvIcon(CvIcons.Navigate, c.signalInk, size = 16.dp, modifier = Modifier.rotate(heading ?: 0f))
            else if (kind == PinKind.Destination) CvIcon(CvIcons.Route, c.ink, size = 18.dp)
            else if (kind == PinKind.Hazard || kind == PinKind.Police) CvIcon(CvIcons.Alert, ring, size = 18.dp)
            else if (kind == PinKind.Photo) CvIcon(CvIcons.Camera, ring, size = 18.dp)
            else if (vehicle != null) Ps1Thumb(vehicle.ps1(), ThumbSize.Badge, Modifier.fillMaxSize(), ContentScale.Fit, fallback = { CvText(label, Cv.type.label.copy(fontSize = 11.sp, letterSpacing = 0.sp), c.ink) })
            else CvText(label, Cv.type.label.copy(fontSize = 11.sp, letterSpacing = 0.sp), if (kind == PinKind.Offline) c.inkMuted else c.ink)
        }
        if (tag != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).cutPanel(c.surface100, ring, CutSShape).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { CvText(tag, Cv.type.label.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.06.sp), c.ink) }
        }
    }
}

private val CAR_KINDS = setOf(PinKind.Me, PinKind.Member, PinKind.Lead, PinKind.Stopped, PinKind.Offline)

/**
 * The top-down PS1 render of a vehicle as a map marker: a glow in the state colour under it (cyan for you), the sprite
 * turned to [heading], and the tag under. Same 56 dp footprint as the round pin, so the map lays them out alike.
 */
@Composable
private fun CarMarker(
    kind: PinKind,
    ring: androidx.compose.ui.graphics.Color,
    vehicle: VehicleBadge,
    heading: Float?,
    tag: String?,
    tiltDeg: Float,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val c = Cv.colors
    val thumbs = app.convoyage.ui.LocalContainer.current.thumbs
    val spec = vehicle.ps1()
    val flat = tiltDeg < 10f
    // Tilted map: the frame drawn from the camera's angle for this heading. Flat map: one plan view, turned smoothly.
    val steps = app.convoyage.render.VehicleThumbs.ANGLE_STEPS
    val yaw = Math.floorMod(Math.round((heading ?: 0f) / (360f / steps)), steps)
    val pitch = app.convoyage.render.VehicleThumbs.ANGLE_TILTS.indices.minBy { kotlin.math.abs(app.convoyage.render.VehicleThumbs.ANGLE_TILTS[it] - tiltDeg) }
    var image by remember(spec) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(spec) { mutableStateOf(false) }
    // Keep showing the last frame until the next one is ready, so turning never blinks.
    val want = if (flat) thumbs.cached(spec, ThumbSize.Top) else thumbs.cachedAngle(spec, yaw, pitch)
    if (want != null && want !== image) image = want
    LaunchedEffect(spec, flat, yaw, pitch) {
        val got = if (flat) thumbs.get(spec, ThumbSize.Top) else thumbs.angle(spec, yaw, pitch)
        if (got != null) image = got else if (image == null) failed = true
    }
    // Warm the whole turn at this tilt in the background, so later headings are instant.
    LaunchedEffect(spec, flat, pitch) {
        if (!flat) for (i in 0 until steps) thumbs.angle(spec, (yaw + i) % steps, pitch)
    }
    if (failed) { fallback(); return }
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        // Glow in the state colour (cyan for you) so the vehicle reads on the night map.
        Box(
            Modifier.size(if (kind == PinKind.Me) 46.dp else 38.dp)
                .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(ring.copy(alpha = if (kind == PinKind.Me) 0.55f else 0.4f), ring.copy(alpha = 0f))), CircleShape),
        )
        val img = image
        if (img != null) {
            androidx.compose.foundation.Image(
                img, contentDescription = null,
                modifier = if (flat) Modifier.size(22.dp, 52.dp).rotate(heading ?: 0f) else Modifier.size(56.dp),
                contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
        if (tag != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).cutPanel(c.surface100, ring, CutSShape).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { CvText(tag, Cv.type.label.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.06.sp), c.ink) }
        }
    }
}
