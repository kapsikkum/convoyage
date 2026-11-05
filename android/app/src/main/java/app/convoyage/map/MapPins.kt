package app.convoyage.map

import app.convoyage.design.PinKind
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Map pins drawn by the map itself, as symbols: anchored to the ground in the same frame as the roads (an overlay
 * placed after each camera move trails the map and slides over 3D terrain). Each pin's artwork is the Compose pin,
 * captured to an image named [imageId].
 */
object MapPins {
    const val SOURCE = "cv-pins"
    const val LAYER = "cv-pins"
    const val ID = "id"

    fun imageId(pinId: String) = "pin-$pinId"

    /** Draw order: you on top, then people, then everything else. */
    fun z(kind: PinKind): Int = when (kind) {
        PinKind.Me -> 100
        PinKind.Lead -> 90
        PinKind.Member, PinKind.Stopped, PinKind.Offline -> 80
        PinKind.Destination -> 70
        PinKind.Police, PinKind.Hazard -> 60
        else -> 50
    }

    private var checkedStyle = java.lang.ref.WeakReference<Style>(null)
    private var checkedAt = 0L

    private fun ensure(style: Style) {
        // Listing the style's layers is costly and this runs every frame while your marker glides: check at most
        // every couple of seconds (layers added later, like a route, are moved under the pins then).
        val now = android.os.SystemClock.uptimeMillis()
        if (style === checkedStyle.get() && now - checkedAt < 2000 && style.getLayer(LAYER) != null) return
        checkedStyle = java.lang.ref.WeakReference(style); checkedAt = now
        if (style.getSource(SOURCE) == null) style.addSource(GeoJsonSource(SOURCE))
        val layers = style.layers
        // Always the top layer: route and other layers added later must not cover the pins.
        if (layers.lastOrNull()?.id == LAYER) return
        if (style.getLayer(LAYER) != null) style.removeLayer(LAYER)
        style.addLayer(
            SymbolLayer(LAYER, SOURCE).withProperties(
                iconImage(get("icon")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                // Upright and screen-aligned like the Compose pins (their artwork already allows for heading and tilt).
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                symbolSortKey(get("z")),
            ),
        )
    }

    /** Puts [pins] on the map; only those whose image is already in the style ([ready]) are shown. */
    fun set(style: Style, pins: List<MapPinData>, ready: Set<String>) {
        if (!style.isFullyLoaded) return
        ensure(style)
        val features = pins.filter { imageId(it.id) in ready }.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty(ID, p.id)
                addStringProperty("icon", imageId(p.id))
                addNumberProperty("z", z(p.kind))
            }
        }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}
