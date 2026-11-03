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
}
