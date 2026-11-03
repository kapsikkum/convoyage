package app.convoyage.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.light.Position
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Darkens OpenFreeMap "liberty" at runtime, mirroring design-tools/render.html:
 * hide POIs/airports/house numbers/shields, recolour labels, add a full-world "mood" tint under the
 * first building/label layer, and restyle (and optionally hide) the 3D buildings.
 */
object MapNightStyle {
    /** Layer ids matching this are hidden. Same pattern as the reference renderer. */
    val HIDDEN_LAYERS = Regex("^poi|^airport|housenumber|shield")

    const val TEXT = "#b9c4d2"
    const val TEXT_WATER = "#6f8fb3"
    const val HALO = "#0b0f16"
    const val HALO_WIDTH = 1.4f
    const val MOOD_COLOR = "#0a0c1a"
    const val MOOD_OPACITY = 0.62f
    const val EXTRUSION_COLOR = "#3b4357"
    const val EXTRUSION_OPACITY = 0.9f
    const val LIGHT_COLOR = "#9aa4c8"
    const val LIGHT_INTENSITY = 0.45f

    /** Route line: signal, 4 wide, with a 14 wide 16% halo. */
    const val ROUTE_COLOR = "#2de2e6"

    const val MOOD_SOURCE = "mood"
    const val MOOD_LAYER = "mood"
    const val ROUTE_SOURCE = "convoy-route"
    const val ROUTE_LAYER = "convoy-route"
    const val ROUTE_HALO_LAYER = "convoy-route-halo"
    const val DEST_SOURCE = "convoy-dest"

    fun shouldHide(layerId: String) = HIDDEN_LAYERS.containsMatchIn(layerId)
    fun labelColor(layerId: String) = if (layerId.contains("water")) TEXT_WATER else TEXT

    /** Applies everything to a freshly loaded [style]. Returns the ids of the 3D building layers. */
    fun apply(style: Style, buildings3d: Boolean): List<String> {
        val layers = style.layers
        val extrusions = mutableListOf<String>()
        for (layer in layers) {
            val id = layer.id
            if (shouldHide(id)) layer.setProperties(visibility(Property.NONE))
            if (layer is SymbolLayer) {
                runCatching {
                    layer.setProperties(textColor(labelColor(id)), textHaloColor(HALO), textHaloWidth(HALO_WIDTH))
                }
            }
            if (layer is FillExtrusionLayer) {
                extrusions += id
                layer.setProperties(
                    fillExtrusionColor(EXTRUSION_COLOR),
                    fillExtrusionOpacity(EXTRUSION_OPACITY),
                    visibility(if (buildings3d) Property.VISIBLE else Property.NONE),
                )
            }
        }

        runCatching {
            style.light?.apply {
                setAnchor(Property.ANCHOR_MAP)
                position = Position(1.5f, 210f, 60f)
                setColor(LIGHT_COLOR)
                intensity = LIGHT_INTENSITY
            }
        }

        val world = Polygon.fromLngLats(
            listOf(listOf(Point.fromLngLat(-180.0, -85.0), Point.fromLngLat(180.0, -85.0), Point.fromLngLat(180.0, 85.0), Point.fromLngLat(-180.0, 85.0), Point.fromLngLat(-180.0, -85.0))),
        )
        style.addSource(GeoJsonSource(MOOD_SOURCE, Feature.fromGeometry(world)))
        val mood = FillLayer(MOOD_LAYER, MOOD_SOURCE).withProperties(fillColor(MOOD_COLOR), fillOpacity(MOOD_OPACITY))
        val firstAbove = layers.firstOrNull { it is FillExtrusionLayer || it is SymbolLayer }
        if (firstAbove != null) style.addLayerBelow(mood, firstAbove.id) else style.addLayer(mood)

        addRoute(style)
        return extrusions
    }

    private fun addRoute(style: Style) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addLayer(
            LineLayer(ROUTE_HALO_LAYER, ROUTE_SOURCE).withProperties(
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineColor(ROUTE_COLOR), lineWidth(14f), lineOpacity(0.16f), lineBlur(2f),
            ),
        )
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineColor(ROUTE_COLOR), lineWidth(4f),
            ),
        )
    }

    /** Replaces the route polyline. [stops] are [lng, lat] pairs as in the protocol. */
    fun setRoute(style: Style, stops: List<List<Double>>) {
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val pts = stops.filter { it.size >= 2 }.map { Point.fromLngLat(it[0], it[1]) }
        if (pts.size < 2) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    fun setBuildings3d(style: Style, extrusionIds: List<String>, on: Boolean) {
        for (id in extrusionIds) style.getLayerAs<FillExtrusionLayer>(id)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
    }
}
