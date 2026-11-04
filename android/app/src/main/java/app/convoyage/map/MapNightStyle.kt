package app.convoyage.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPadding
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
    const val MOOD_OPACITY = 0.66f

    /** Night HUD road palette. The mood sits under the roads, so these are the colours you actually see. */
    const val ROAD_MINOR = "#3a4660"
    const val ROAD_MAJOR = "#56627e"
    const val ROAD_PATH = "#2c3650"
    const val ROAD_RAIL = "#273044"
    const val ROAD_CASING = "#070a10"
    const val BUILDING_2D = "#151c2a"
    const val BOUNDARY = "#2a3346"

    /** Flat layers that end up above the mood (boundaries) are faded to this. */
    const val ABOVE_MOOD_OPACITY = 0.35f
    const val EXTRUSION_COLOR = "#3b4357"
    const val EXTRUSION_OPACITY = 0.9f
    const val LIGHT_COLOR = "#9aa4c8"
    const val LIGHT_INTENSITY = 0.45f

    /** Route line: signal, 4 wide, with a 14 wide 16% halo. */
    const val ROUTE_COLOR = "#2de2e6"

    // Terrain in 3D: shaded relief from public Terrarium elevation tiles (AWS open data, no key).
    const val DEM_SOURCE = "terrain-dem"
    const val HILLSHADE_LAYER = "terrain-hillshade"
    const val DEM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val HILL_SHADOW = "#000000"
    const val HILL_HIGHLIGHT = "#5a86ad"
    const val HILL_ACCENT = "#0d1830"
    const val REJOIN_SOURCE = "rejoin"
    const val REJOIN_LAYER = "rejoin"
    /** The way back to the route when off track: a purple outside the HUD's cyan/magenta/amber, so it never reads as the route or an alert. */
    const val REJOIN_COLOR = "#a66bff"
    const val ROADQ_SOURCE = "road-quality"
    const val ROADQ_LAYER = "road-quality"
    /** Road quality grades (OpenStreetMap surface/smoothness): go green, hazard amber, pulse magenta, muted grey. */
    val GRADE_COLORS = mapOf("good" to "#46e08c", "fair" to "#ffb020", "poor" to "#ff3d8b", "unknown" to "#5d6b7c")

    const val MOOD_SOURCE = "mood"
    const val MOOD_LAYER = "mood"
    const val ROUTE_SOURCE = "convoy-route"
    const val ROUTE_LAYER = "convoy-route"
    const val ROUTE_HALO_LAYER = "convoy-route-halo"
    const val DEST_SOURCE = "convoy-dest"

    /** Just enough of a style layer to decide where the mood goes (so the rule is unit-testable). */
    data class LayerKind(val id: String, val isExtrusion: Boolean, val isSymbol: Boolean)

    /**
     * Id of the layer the mood tint must sit directly below, or null to put it on top.
     *
     * The mood has to cover every fill and line layer (landuse, parks, water, all roads, bridges) and sit
     * below only the 3D buildings and the labels. Liberty has symbol layers in the middle of the roads
     * (`road_one_way_arrow`, then the `bridge_*` lines above it), so "below the first symbol" leaves the
     * yellow bridges and motorways untinted. Instead: below the first fill-extrusion if there is one,
     * otherwise below the first symbol that comes after the last fill/line/raster layer.
     */
    fun moodInsertBefore(layers: List<LayerKind>): String? {
        layers.firstOrNull { it.isExtrusion }?.let { return it.id }
        val lastFlat = layers.indexOfLast { !it.isSymbol }
        return layers.drop(lastFlat + 1).firstOrNull()?.id
    }

    // What the land should look like once the mood is over it (the reference palette of the night HUD).
    const val LAND_BACKGROUND = "#0b0f17"
    const val LAND_RESIDENTIAL = "#101624"
    const val LAND_GREEN = "#0f1f20"
    const val LAND_OTHER = "#121a28"
    const val LAND_SAND = "#17181f"
    const val LAND_WATER = "#0f2a42"
    const val LAND_WATERWAY = "#14344f"
    const val LAND_AERO = "#131a27"

    /** The colour to paint under the mood so that it ends up as [target] on screen: (target - mood * a) / (1 - a). */
    fun underMood(target: String, moodColor: String = MOOD_COLOR, alpha: Float = MOOD_OPACITY): String {
        fun ch(hex: String, i: Int) = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16)
        val out = (0..2).map { i ->
            ((ch(target, i) - ch(moodColor, i) * alpha) / (1 - alpha)).toInt().coerceIn(0, 255)
        }
        return "#%02x%02x%02x".format(out[0], out[1], out[2])
    }

    /** The on-screen colour a land/water fill layer should end up with, or null to leave the layer alone. */
    fun landTarget(id: String): String? = when {
        id == "background" -> LAND_BACKGROUND
        id == "park" || id == "landcover_wood" || id == "landcover_grass" -> LAND_GREEN
        id == "landuse_residential" -> LAND_RESIDENTIAL
        id == "landcover_sand" -> LAND_SAND
        id == "landcover_ice" || id == "landcover_wetland" -> LAND_OTHER
        id.startsWith("landuse_") -> LAND_OTHER
        id == "water" -> LAND_WATER
        id.startsWith("waterway_") && !id.endsWith("label") -> LAND_WATERWAY
        id == "aeroway_fill" -> LAND_AERO
        id == "aeroway_runway" || id == "aeroway_taxiway" -> ROAD_PATH
        else -> null
    }

    /** How a road-ish line layer is coloured. */
    enum class RoadClass { Minor, Major, Path, Rail, Casing }

    private val ROAD_PREFIX = Regex("^(road|bridge|tunnel)_")
    private val MAJOR = Regex("motorway|trunk|primary|secondary|tertiary|link")

    fun isRoadLayer(id: String) = ROAD_PREFIX.containsMatchIn(id)

    /** Null for layers that are not roads, bridges or tunnels (and for the one-way arrows and the area pattern). */
    fun roadClass(id: String): RoadClass? {
        if (!isRoadLayer(id) || id.contains("arrow") || id.contains("area_pattern")) return null
        return when {
            id.contains("casing") -> RoadClass.Casing
            id.contains("rail") -> RoadClass.Rail
            id.contains("path") || id.contains("pedestrian") -> RoadClass.Path
            MAJOR.containsMatchIn(id) -> RoadClass.Major
            else -> RoadClass.Minor
        }
    }

    fun roadColor(c: RoadClass) = when (c) {
        RoadClass.Minor -> ROAD_MINOR
        RoadClass.Major -> ROAD_MAJOR
        RoadClass.Path -> ROAD_PATH
        RoadClass.Rail -> ROAD_RAIL
        RoadClass.Casing -> ROAD_CASING
    }

    /** The mood goes directly under the first tunnel/road/bridge layer, so roads are not washed out by it. */
    fun moodBelowRoads(layerIds: List<String>): String? = layerIds.firstOrNull { isRoadLayer(it) }

    fun shouldHide(layerId: String) = HIDDEN_LAYERS.containsMatchIn(layerId)
    fun labelColor(layerId: String) = if (layerId.contains("water")) TEXT_WATER else TEXT

    /** Applies everything to a freshly loaded [style]. Returns the ids of the 3D building layers. */
    fun apply(style: Style, buildings3d: Boolean): List<String> {
        val layers = style.layers
        val extrusions = mutableListOf<String>()
        for (layer in layers) {
            val id = layer.id
            if (shouldHide(id)) layer.setProperties(visibility(Property.NONE))
            recolourLand(layer)
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
        // Mood under the roads: everything below (land, water, parks) is darkened, the roads keep the HUD colours.
        val above = moodBelowRoads(layers.map { it.id })
            ?: moodInsertBefore(layers.map { LayerKind(it.id, it is FillExtrusionLayer, it is SymbolLayer) })
        if (above != null) style.addLayerBelow(mood, above) else style.addLayer(mood)
        addTerrain(style, above, buildings3d)
        val aboveIdx = if (above == null) layers.size else layers.indexOfFirst { it.id == above }
        for (layer in layers.drop(aboveIdx)) {
            val id = layer.id
            val road = roadClass(id)
            if (layer is LineLayer && road != null) {
                runCatching { layer.setProperties(lineColor(roadColor(road)), lineOpacity(1f)) }
            } else if (layer is FillLayer && id.contains("area_pattern")) {
                runCatching { layer.setProperties(fillOpacity(0f)) }
            } else if (layer is FillLayer && id == "building") {
                runCatching { layer.setProperties(fillColor(BUILDING_2D), fillOpacity(0.9f)) }
            } else if (layer is LineLayer) {
                // Boundaries and anything else flat that sits above the mood.
                runCatching { layer.setProperties(lineColor(BOUNDARY), lineOpacity(ABOVE_MOOD_OPACITY)) }
            } else if (layer is FillLayer) {
                runCatching { layer.setProperties(fillOpacity(ABOVE_MOOD_OPACITY)) }
            }
        }

        addRoute(style)
        addColliders(style)
        return extrusions
    }

    const val COLLIDER_SOURCE = "pin-colliders"
    const val COLLIDER_LAYER = "pin-colliders"
    const val COLLIDER_IMAGE = "pin-collider"

    /**
     * The pins (puck, members, hazards, destination) are Compose overlays, so the map does not know they are there
     * and a place label ("Bathurst") can sit right under one. Each pin gets an invisible symbol on this layer, on top
     * of every label: it always draws (icon-allow-overlap) and, by not ignoring placement, makes the labels around it yield.
     */
    private fun addColliders(style: Style) {
        val bmp = android.graphics.Bitmap.createBitmap(COLLIDER_PX, COLLIDER_PX, android.graphics.Bitmap.Config.ARGB_8888)
        style.addImage(COLLIDER_IMAGE, bmp)
        style.addSource(GeoJsonSource(COLLIDER_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addLayer(
            SymbolLayer(COLLIDER_LAYER, COLLIDER_SOURCE).withProperties(
                iconImage(COLLIDER_IMAGE),
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
                iconPadding(6f),
            ),
        )
    }

    private const val COLLIDER_PX = 96

    fun setColliders(style: Style, points: List<Pair<Double, Double>>) {
        val source = style.getSourceAs<GeoJsonSource>(COLLIDER_SOURCE) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(points.map { (lat, lng) -> Feature.fromGeometry(Point.fromLngLat(lng, lat)) }))
    }

    private fun recolourLand(layer: org.maplibre.android.style.layers.Layer) {
        val id = layer.id
        runCatching {
            when (layer) {
                is BackgroundLayer -> landTarget(id)?.let { layer.setProperties(backgroundColor(underMood(it))) }
                is FillLayer -> landTarget(id)?.let { layer.setProperties(fillColor(underMood(it)), fillOpacity(1f)) }
                is LineLayer -> {
                    if (id == "park_outline") layer.setProperties(visibility(Property.NONE))
                    else landTarget(id)?.let { layer.setProperties(lineColor(underMood(it))) }
                }
                is RasterLayer -> layer.setProperties(rasterOpacity(0f))
                else -> Unit
            }
        }
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
                // A segment can carry its own colour (road quality along a saved route); otherwise route cyan.
                lineColor(org.maplibre.android.style.expressions.Expression.coalesce(
                    org.maplibre.android.style.expressions.Expression.get("color"),
                    org.maplibre.android.style.expressions.Expression.literal(ROUTE_COLOR),
                )),
                lineWidth(4f),
            ),
        )
        // Road quality overlay sits under the route halo.
        style.addSource(GeoJsonSource(ROADQ_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
        val grade = org.maplibre.android.style.expressions.Expression.match(
            org.maplibre.android.style.expressions.Expression.get("grade"),
            org.maplibre.android.style.expressions.Expression.literal(GRADE_COLORS.getValue("unknown")),
            *GRADE_COLORS.filterKeys { it != "unknown" }.flatMap { (k, v) ->
                listOf(org.maplibre.android.style.expressions.Expression.literal(k), org.maplibre.android.style.expressions.Expression.literal(v))
            }.toTypedArray(),
        )
        style.addLayerBelow(
            LineLayer(ROADQ_LAYER, ROADQ_SOURCE).withProperties(
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineColor(grade), lineWidth(3f), lineOpacity(0.85f),
            ),
            ROUTE_HALO_LAYER,
        )
    }

    /** The rejoin hint from [from] to [to] on the route, or cleared when either is null. */
    fun setRejoin(style: Style, from: app.convoyage.data.LatLngD?, to: app.convoyage.data.LatLngD?) {
        if (style.getSource(REJOIN_SOURCE) == null) {
            style.addSource(GeoJsonSource(REJOIN_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                LineLayer(REJOIN_LAYER, REJOIN_SOURCE).withProperties(
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                    lineColor(REJOIN_COLOR), lineWidth(4f), lineOpacity(0.9f),
                    org.maplibre.android.style.layers.PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                ),
            )
        }
        val source = style.getSourceAs<GeoJsonSource>(REJOIN_SOURCE) ?: return
        if (from == null || to == null) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(from.lng, from.lat), Point.fromLngLat(to.lng, to.lat)))))
    }

    /** Pulse: the rejoin line's opacity, set every frame while it shows. */
    fun setRejoinOpacity(style: Style, opacity: Float) {
        style.getLayerAs<LineLayer>(REJOIN_LAYER)?.setProperties(lineOpacity(opacity))
    }

    /** The graded-roads layer: GeoJSON from GET /v1/roads, or null to clear it. */
    fun setRoadQuality(style: Style, geojson: String?) {
        val source = style.getSourceAs<GeoJsonSource>(ROADQ_SOURCE) ?: return
        if (geojson.isNullOrBlank()) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else runCatching { source.setGeoJson(FeatureCollection.fromJson(geojson)) }
    }

    /** The route drawn in coloured pieces (each [colorHex] like "#46e08c"). */
    fun setRouteSegments(style: Style, segments: List<Pair<List<app.convoyage.data.LatLngD>, String>>) {
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(segments.filter { it.first.size >= 2 }.map { (pts, color) ->
            Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) })).also { it.addStringProperty("color", color) }
        }))
    }

    /** Replaces the route polyline. [stops] are [lng, lat] pairs as in the protocol. */
    fun setRoute(style: Style, stops: List<List<Double>>) {
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val pts = stops.filter { it.size >= 2 }.map { Point.fromLngLat(it[0], it[1]) }
        if (pts.size < 2) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    /** Replaces the route polyline with a decoded road route (can be thousands of points). */
    fun setRouteLine(style: Style, points: List<app.convoyage.data.LatLngD>) {
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        if (points.size < 2) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lng, it.lat) })))
    }

    fun setBuildings3d(style: Style, extrusionIds: List<String>, on: Boolean) {
        for (id in extrusionIds) style.getLayerAs<FillExtrusionLayer>(id)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
        style.getLayer(HILLSHADE_LAYER)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    /**
     * Hills and valleys: hillshade over the darkened land and under the roads, lit from the north-west in the HUD's
     * blues. MapLibre Native has no 3D terrain mesh, so relief is shading, not raised ground. Shown with 3D.
     */
    private fun addTerrain(style: Style, below: String?, on: Boolean) {
        runCatching {
            val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", DEM_TILES).apply {
                encoding = "terrarium"
                maxZoom = 15f
                attribution = "Elevation: Mapzen Terrain Tiles (AWS Open Data)"
            }
            style.addSource(org.maplibre.android.style.sources.RasterDemSource(DEM_SOURCE, tiles, 256))
            val hill = org.maplibre.android.style.layers.HillshadeLayer(HILLSHADE_LAYER, DEM_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor(arrayOf(HILL_SHADOW)),
                org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor(arrayOf(HILL_HIGHLIGHT)),
                org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor(HILL_ACCENT),
                org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration(1.0f),
                visibility(if (on) Property.VISIBLE else Property.NONE),
            )
            if (below != null) style.addLayerBelow(hill, below) else style.addLayer(hill)
        }.onFailure { android.util.Log.w("MapNightStyle", "terrain unavailable", it) }
    }
}
