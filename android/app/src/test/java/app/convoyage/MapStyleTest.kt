package app.convoyage

import app.convoyage.map.MapNightStyle
import app.convoyage.map.MapNightStyle.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapStyleTest {
    /** "id:f" = fill/line/raster/background, "id:s" = symbol, "id:x" = fill-extrusion. */
    private fun layers(spec: String) = spec.trim().split(Regex("\\s+")).map {
        val (id, k) = it.split(':')
        LayerKind(id, isExtrusion = k == "x", isSymbol = k == "s")
    }

    @Test
    fun moodSitsAboveTheBridgesAndRoadsOfOpenFreeMapLiberty() {
        // The real layer order of https://tiles.openfreemap.org/styles/liberty, trimmed: a symbol layer
        // (road_one_way_arrow) sits in the middle of the roads, with the yellow bridge_* lines above it.
        val liberty = layers(
            """
            background:f park:f water:f road_motorway:f road_one_way_arrow:s road_one_way_arrow_opposite:s
            bridge_motorway_link_casing:f bridge_motorway:f bridge_transit_rail_hatching:f
            building:f building-3d:x boundary_3:f boundary_disputed:f
            waterway_line_label:s poi_r20:s label_country_1:s
            """,
        )
        // Not road_one_way_arrow (the old "first symbol" rule), which left every bridge_* line untinted.
        assertEquals("building-3d", MapNightStyle.moodInsertBefore(liberty))
    }

    @Test
    fun withoutExtrusionsTheMoodGoesBelowTheFirstLabelAfterTheLastFlatLayer() {
        val noBuildings = layers("background:f water:f road_motorway:f road_one_way_arrow:s bridge_motorway:f boundary_2:f waterway_line_label:s label_city:s")
        assertEquals("waterway_line_label", MapNightStyle.moodInsertBefore(noBuildings))
    }

    @Test
    fun withNothingAboveTheFlatLayersTheMoodGoesOnTop() {
        assertNull(MapNightStyle.moodInsertBefore(layers("background:f water:f road:f")))
        assertNull(MapNightStyle.moodInsertBefore(emptyList()))
    }

    @Test
    fun theMoodIsTheReferenceTintAndStrongEnoughToDarkenTheMap() {
        // design-tools/render.html uses #0a0c1a at 0.62; PhotoScout's range is 0.62..0.72.
        assertEquals("#0a0c1a", MapNightStyle.MOOD_COLOR)
        assert(MapNightStyle.MOOD_OPACITY in 0.62f..0.72f)
    }

    @Test
    fun moodGoesUnderTheFirstRoadLayerSoRoadsKeepTheirColours() {
        val ids = listOf("background", "park", "water", "tunnel_minor", "road_minor", "bridge_motorway", "building", "label_city")
        assertEquals("tunnel_minor", MapNightStyle.moodBelowRoads(ids))
        assertNull(MapNightStyle.moodBelowRoads(listOf("background", "water")))
    }

    @Test
    fun roadsAreClassifiedForTheNightPalette() {
        assertEquals(MapNightStyle.RoadClass.Minor, MapNightStyle.roadClass("road_minor"))
        assertEquals(MapNightStyle.RoadClass.Minor, MapNightStyle.roadClass("bridge_street"))
        assertEquals(MapNightStyle.RoadClass.Major, MapNightStyle.roadClass("road_motorway"))
        assertEquals(MapNightStyle.RoadClass.Major, MapNightStyle.roadClass("bridge_trunk_primary"))
        assertEquals(MapNightStyle.RoadClass.Major, MapNightStyle.roadClass("road_secondary_tertiary"))
        assertEquals(MapNightStyle.RoadClass.Casing, MapNightStyle.roadClass("road_motorway_casing"))
        assertEquals(MapNightStyle.RoadClass.Rail, MapNightStyle.roadClass("road_major_rail"))
        assertNull(MapNightStyle.roadClass("road_one_way_arrow"))
        assertNull(MapNightStyle.roadClass("building"))
        assertEquals("#4a5878", MapNightStyle.roadColor(MapNightStyle.RoadClass.Minor))
        assertEquals("#7583a2", MapNightStyle.roadColor(MapNightStyle.RoadClass.Major))
    }

    @Test
    fun underMoodInvertsTheTint() {
        // Painting underMood(t) below the mood must give back t (within rounding).
        val t = "#0f2a42"
        val under = MapNightStyle.underMood(t)
        fun ch(h: String, i: Int) = h.substring(1 + 2 * i, 3 + 2 * i).toInt(16)
        for (i in 0..2) {
            val shown = ch(under, i) * (1 - MapNightStyle.MOOD_OPACITY) + ch(MapNightStyle.MOOD_COLOR, i) * MapNightStyle.MOOD_OPACITY
            assert(Math.abs(shown - ch(t, i)) < 3.0)
        }
    }
}
