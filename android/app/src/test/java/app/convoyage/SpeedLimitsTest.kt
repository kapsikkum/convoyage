package app.convoyage

import app.convoyage.api.AreaConditionsDto
import app.convoyage.api.ConditionItem
import app.convoyage.data.AreaConditions
import app.convoyage.data.LimitWay
import app.convoyage.data.SpeedLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitsTest {
    // A main road running east-west at lat -33.4200 (60), and a side street running north from it (50).
    private val main = LimitWay(60, "Durham St", listOf(doubleArrayOf(149.570, -33.4200), doubleArrayOf(149.580, -33.4200)))
    private val side = LimitWay(50, "Side St", listOf(doubleArrayOf(149.5750, -33.4200), doubleArrayOf(149.5750, -33.4150)))
    private val ways = listOf(main, side)

    @Test fun parsesTheRoadLayer() {
        val json = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[149.57,-33.42],[149.58,-33.42]]},"properties":{"grade":"good","name":"Durham St","maxspeed":60}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[149.575,-33.42],[149.575,-33.415]]},"properties":{"grade":"fair","name":"","maxspeed":null}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[149.575,-33.42]]},"properties":{}}
        ]}"""
        val w = SpeedLimits.parse(json)
        assertEquals(2, w.size)
        assertEquals(60, w[0].limitKmh)
        assertNull(w[1].limitKmh)
        assertEquals(emptyList<LimitWay>(), SpeedLimits.parse("not json"))
    }

    @Test fun matchesTheRoadRunningYourWay() {
        // Right at the junction, heading east: the main road, not the side street.
        assertEquals(60, SpeedLimits.match(ways, -33.42001, 149.57502, 90.0, null)?.limitKmh)
        // Same spot heading north: the side street.
        assertEquals(50, SpeedLimits.match(ways, -33.42001, 149.57502, 0.0, null)?.limitKmh)
        // Heading west along the main road counts too.
        assertEquals(60, SpeedLimits.match(ways, -33.42001, 149.5720, 270.0, null)?.limitKmh)
    }

    @Test fun offTheRoadsThereIsNoLimit() {
        assertNull(SpeedLimits.match(ways, -33.4180, 149.5720, 90.0, null))
    }

    @Test fun areaLayerLeavesOutOurOwnReports() {
        val dto = AreaConditionsDto(listOf(
            ConditionItem("waze:p1", "waze", "police", "Police", "", -33.4, 149.5),
            ConditionItem("convoyage:9", "convoyage", "police", "Police", "", -33.4, 149.5),
            ConditionItem("waze:p1", "waze", "police", "Police", "", -33.4, 149.5),
        ))
        assertEquals(listOf("waze:p1"), AreaConditions.visible(dto).map { it.id })
    }
}
