package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.ManeuverDto
import app.convoyage.api.RouteApi
import app.convoyage.api.RouteResponse
import app.convoyage.api.RouteStepDto
import app.convoyage.data.Fix
import app.convoyage.data.LatLngD
import app.convoyage.data.LocalRoute
import app.convoyage.data.Polyline
import app.convoyage.nav.ManeuverIcon
import app.convoyage.nav.Maneuvers
import app.convoyage.nav.NavEngine
import app.convoyage.nav.NavMath
import app.convoyage.nav.NavPhase
import app.convoyage.nav.NavRoute
import app.convoyage.nav.NavigationController
import app.convoyage.nav.OffRouteDetector
import app.convoyage.nav.Place
import app.convoyage.nav.PromptScheduler
import app.convoyage.nav.PromptStage
import app.convoyage.nav.RouteHazards
import app.convoyage.nav.RouteLine
import app.convoyage.nav.RouteStep
import app.convoyage.nav.Speaker
import app.convoyage.protocol.Destination
import app.convoyage.protocol.Hazard
import app.convoyage.protocol.Room
import app.convoyage.ui.Fmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A test route near Bathurst: 1000 m north, 1000 m east, 500 m north. Steps: depart, right, left, arrive. */
private object Fixture {
    const val LAT0 = -33.50
    const val LNG0 = 149.50
    private const val M_LAT = 1.0 / 111_195.0
    private fun mLng(lat: Double) = 1.0 / (111_195.0 * Math.cos(Math.toRadians(lat)))

    fun points(): List<LatLngD> {
        val out = ArrayList<LatLngD>()
        var lat = LAT0
        var lng = LNG0
        out += LatLngD(lat, lng)
        repeat(10) { lat += 100 * M_LAT; out += LatLngD(lat, lng) }
        repeat(10) { lng += 100 * mLng(lat); out += LatLngD(lat, lng) }
        repeat(5) { lat += 100 * M_LAT; out += LatLngD(lat, lng) }
        return out
    }

    fun step(type: String, mod: String?, p: LatLngD, name: String, dist: Double) =
        RouteStepDto(ManeuverDto(type, mod, listOf(p.lng, p.lat)), name, dist, dist / 15, "")

    fun response(): RouteResponse {
        val pts = points()
        return RouteResponse(
            profile = "car", geometry = Polyline.encode(pts, 6), distanceM = 2500.0, durationS = 200.0,
            steps = listOf(
                step("depart", "north", pts[0], "Conrod Straight", 1000.0),
                step("turn", "right", pts[10], "Mount Panorama Circuit", 1000.0),
                step("turn", "left", pts[20], "Pit Lane", 500.0),
                step("arrive", null, pts[25], "", 0.0),
            ),
        )
    }

    fun route(): NavRoute = NavRoute.from(response())!!

    /** A position [along] metres from the start of the route, [offEast] metres east of it. */
    fun at(route: NavRoute, along: Double, offEast: Double = 0.0): LatLngD {
        val p = NavMath.pointAt(route, along)
        return LatLngD(p.lat, p.lng + offEast * mLng(p.lat))
    }
}

class NavRouteTest {
    @Test
    fun decodesPolylineSixAndReadsTheSteps() {
        val r = Fixture.route()
        assertEquals(26, r.points.size)
        assertEquals(Fixture.LAT0, r.points[0].lat, 1e-6)
        assertEquals(Fixture.LNG0, r.points[0].lng, 1e-6)
        assertEquals(4, r.steps.size)
        assertEquals(2500.0, r.length, 25.0)
        assertEquals(0.0, r.stepStart[0], 1e-9)
        assertEquals(1000.0, r.stepStart[1], 15.0)
    }

    @Test
    fun precisionFiveAndSixGiveDifferentPoints() {
        val pts = listOf(LatLngD(-33.4470123, 149.5580456), LatLngD(-33.4480789, 149.5590123))
        val six = Polyline.decode(Polyline.encode(pts, 6), 6)
        assertEquals(-33.4470123, six[0].lat, 1e-6)
        // Reading a precision-6 string as precision 5 puts the point ten times too far out: this is why the response says which.
        val wrong = try { Polyline.decode(Polyline.encode(pts, 6), 5).first().lat } catch (_: IllegalArgumentException) { Double.NaN }
        assertTrue(wrong.isNaN() || Math.abs(wrong - pts[0].lat) > 1.0)
    }

    @Test
    fun anUnreadableGeometryGivesNoRoute() {
        assertNull(NavRoute.from(Fixture.response().copy(geometry = "!!!")))
        assertNull(NavRoute.from(Fixture.response().copy(geometry = "")))
    }
}

class NavProgressTest {
    private val route = Fixture.route()

    private fun progress(along: Double, off: Double = 0.0, minStep: Int = 0, hint: Int = 0): app.convoyage.nav.NavProgress {
        val p = Fixture.at(route, along, off)
        return NavEngine.progress(route, NavMath.snap(route, p.lat, p.lng, hint), minStep)
    }

    @Test
    fun stepsAdvanceAsWeDrive() {
        assertEquals(0, progress(10.0).stepIndex)
        assertEquals(0, progress(900.0).stepIndex)
        assertEquals(1, progress(1100.0).stepIndex)
        assertEquals(1, progress(1900.0).stepIndex)
        assertEquals(2, progress(2100.0).stepIndex)
        assertEquals(2, progress(1100.0).nextIndex)
        assertEquals(3, progress(2400.0).nextIndex)
    }

    @Test
    fun distanceToTheNextManeuver() {
        assertEquals(1000.0, progress(0.0).distToManeuverM, 20.0)
        assertEquals(250.0, progress(750.0).distToManeuverM, 20.0)
        // Past the right turn the next maneuver is the left turn 1000 m further on.
        assertEquals(500.0, progress(1500.0).distToManeuverM, 20.0)
        assertEquals(10.0, progress(990.0).distToManeuverM, 8.0)
    }

    @Test
    fun theStepNumberNeverGoesBackwards() {
        // GPS noise puts us 8 m behind the corner we have just taken.
        assertEquals(1, progress(992.0, minStep = 1).stepIndex)
    }

    @Test
    fun remainingDistanceAndTimeShrink() {
        val a = progress(500.0)
        val b = progress(1500.0)
        assertTrue(a.remainingM > b.remainingM)
        assertTrue(a.remainingS > b.remainingS)
        assertEquals(2000.0, a.remainingM, 25.0)
    }

    @Test
    fun arrivesNearTheEnd() {
        assertFalse(progress(2300.0).arrived)
        assertTrue(progress(2480.0).arrived)
    }

    @Test
    fun snapReportsHowFarOffTheLineWeAre() {
        assertEquals(0.0, progress(300.0).offRouteM, 1.0)
        assertEquals(80.0, progress(300.0, off = 80.0).offRouteM, 3.0)
    }

    @Test
    fun theSnapHintStopsTheLineJumpingToAnotherPass() {
        // A route that comes back past its own start: the position at the start is on both passes.
        val loop = listOf(
            LatLngD(-33.5, 149.5), LatLngD(-33.499, 149.5), LatLngD(-33.499, 149.501), LatLngD(-33.5, 149.501), LatLngD(-33.5, 149.5),
        )
        val r = NavRoute("car", loop, 0.0, 0.0, emptyList())
        val near = NavMath.snap(r, -33.5, 149.5, hint = 3)
        assertEquals(3, near.seg)
        val start = NavMath.snap(r, -33.5, 149.5, hint = 0)
        assertEquals(0, start.seg)
    }
}

class OffRouteTest {
    @Test
    fun needsMoreThanFiftyMetresForMoreThanEightSeconds() {
        val d = OffRouteDetector()
        assertFalse(d.update(30.0, 0))
        assertFalse(d.update(80.0, 1_000))
        assertFalse(d.update(80.0, 5_000))
        assertFalse("exactly 8 s is not more than 8 s", d.update(80.0, 9_000))
        assertTrue(d.update(80.0, 9_001))
    }

    @Test
    fun comingBackOnTheLineResetsTheTimer() {
        val d = OffRouteDetector()
        d.update(80.0, 0)
        d.update(80.0, 7_000)
        assertFalse(d.update(20.0, 7_500))
        assertFalse(d.update(80.0, 8_000))
        assertFalse(d.update(80.0, 15_000))
        assertTrue(d.update(80.0, 16_100))
    }

    @Test
    fun rerouteRequestsAreThrottled() {
        val d = OffRouteDetector()
        d.update(80.0, 0)
        assertTrue(d.update(80.0, 9_000))
        d.reset()
        d.update(80.0, 9_500)
        assertFalse("too soon after the last one", d.update(80.0, 18_000))
        assertTrue(d.update(80.0, 24_100))
    }
}

class PromptSchedulerTest {
    private fun step(name: String = "Pit Lane") = RouteStep("turn", "left", 0.0, 0.0, name, 500.0, 30.0, "")
    private val fast = 19.4 // about 70 km/h

    @Test
    fun announcesAtRoughlyEightHundredTwoHundredAndAtTheTurn() {
        val s = PromptScheduler()
        val st = step()
        assertNull(s.update(1, st, 1200.0, fast))
        val far = s.update(1, st, 770.0, fast)!!
        assertEquals(PromptStage.Far, far.stage)
        assertTrue(far.text, far.text.startsWith("In 750 metres, "))
        assertNull("each is said once", s.update(1, st, 700.0, fast))
        assertEquals(PromptStage.Mid, s.update(1, st, 190.0, fast)!!.stage)
        assertNull(s.update(1, st, 150.0, fast))
        val now = s.update(1, st, 35.0, fast)!!
        assertEquals(PromptStage.Now, now.stage)
        assertFalse(now.text.startsWith("In "))
    }

    @Test
    fun scalesDownAtLowSpeed() {
        val s = PromptScheduler()
        val slow = 5.0 // 18 km/h
        assertNull("800 m is far too early at 18 km/h", s.update(1, step(), 500.0, slow))
        assertNull(s.update(1, step(), 250.0, slow))
        assertEquals(PromptStage.Far, s.update(1, step(), 190.0, slow)!!.stage)
        assertTrue(PromptScheduler.farM(slow) < PromptScheduler.farM(fast))
        assertTrue(PromptScheduler.midM(slow) < PromptScheduler.midM(fast))
        assertEquals(800.0, PromptScheduler.farM(40.0), 1e-9)
    }

    @Test
    fun aManeuverFirstSeenCloseOnlyGetsTheNearestPrompt() {
        val s = PromptScheduler()
        assertEquals(PromptStage.Mid, s.update(1, step(), 100.0, fast)!!.stage)
        assertNull("the far prompt is not said late", s.update(1, step(), 95.0, fast))
        assertEquals(PromptStage.Now, s.update(1, step(), 30.0, fast)!!.stage)
    }

    @Test
    fun theNextManeuverStartsOver() {
        val s = PromptScheduler()
        s.update(1, step(), 30.0, fast)
        assertNull(s.update(1, step(), 20.0, fast))
        assertNotNull(s.update(2, step("Next St"), 700.0, fast))
    }

    @Test
    fun saysNothingOnTheLastStep() {
        assertNull(PromptScheduler().update(null, null, 100.0, fast))
    }

    @Test
    fun speaksDistancesTheWayAPersonWould() {
        assertEquals("200 metres", PromptScheduler.spokenDistance(204.0))
        assertEquals("300 metres", PromptScheduler.spokenDistance(318.0))
        assertEquals("90 metres", PromptScheduler.spokenDistance(87.0))
        assertEquals("1 kilometre", PromptScheduler.spokenDistance(1000.0))
        assertEquals("1.2 kilometres", PromptScheduler.spokenDistance(1210.0))
        assertEquals("2 kilometres", PromptScheduler.spokenDistance(1980.0))
    }

    @Test
    fun usesTheServersInstructionWhenThereIsOne() {
        val st = RouteStep("turn", "left", 0.0, 0.0, "Pit Lane", 1.0, 1.0, "Turn left onto Pit Lane")
        assertEquals("In 190 metres, turn left onto Pit Lane", PromptScheduler().update(1, st, 190.0, fast)!!.text)
    }
}

class ManeuverIconTest {
    private fun icon(t: String, m: String?) = Maneuvers.icon(t, m)

    @Test
    fun turnsFollowTheModifier() {
        assertEquals(ManeuverIcon.Right, icon("turn", "right"))
        assertEquals(ManeuverIcon.Left, icon("turn", "left"))
        assertEquals(ManeuverIcon.SlightLeft, icon("turn", "slight left"))
        assertEquals(ManeuverIcon.SharpRight, icon("turn", "sharp right"))
        assertEquals(ManeuverIcon.UTurn, icon("turn", "uturn"))
        assertEquals(ManeuverIcon.Straight, icon("turn", "straight"))
        assertEquals(ManeuverIcon.Left, icon("end of road", "left"))
        assertEquals(ManeuverIcon.SlightRight, icon("on ramp", "slight right"))
        assertEquals(ManeuverIcon.Straight, icon("continue", null))
        assertEquals(ManeuverIcon.Straight, icon("new name", "straight"))
    }

    @Test
    fun specialManeuvers() {
        assertEquals(ManeuverIcon.Arrive, icon("arrive", "left"))
        assertEquals(ManeuverIcon.Depart, icon("depart", "north"))
        assertEquals(ManeuverIcon.Roundabout, icon("roundabout", "right"))
        assertEquals(ManeuverIcon.Roundabout, icon("rotary", null))
        assertEquals(ManeuverIcon.Roundabout, icon("exit roundabout", "left"))
        assertEquals(ManeuverIcon.ForkLeft, icon("fork", "slight left"))
        assertEquals(ManeuverIcon.ForkRight, icon("fork", "right"))
        assertEquals(ManeuverIcon.Merge, icon("merge", "slight right"))
    }

    @Test
    fun anUnknownTypeFallsBackToItsModifier() {
        assertEquals(ManeuverIcon.Left, icon("some future type", "left"))
        assertEquals(ManeuverIcon.Straight, icon("some future type", null))
    }

    @Test
    fun cardTextReadsLikeTheMockup() {
        val right = RouteStep("turn", "right", 0.0, 0.0, "Spring St", 1.0, 1.0, "")
        val left = RouteStep("turn", "left", 0.0, 0.0, "Flinders St", 1.0, 1.0, "")
        assertEquals("Right onto Spring St", Maneuvers.headline(right))
        assertEquals("then left · Flinders St", Maneuvers.then(left))
        assertEquals("Arrive at Pit Lane", Maneuvers.headline(RouteStep("arrive", null, 0.0, 0.0, "Pit Lane", 0.0, 0.0, "")))
    }

    @Test
    fun cardDistanceFormat() {
        assertEquals("300 M", Fmt.navDistance(304.0))
        assertEquals("45 M", Fmt.navDistance(46.0))
        assertEquals("1.2 KM", Fmt.navDistance(1240.0))
        assertEquals("0 M", Fmt.navDistance(-3.0))
    }
}

class RouteHazardTest {
    private val route = Fixture.route()
    private fun hazard(id: String, along: Double, off: Double, kind: String = "police"): Hazard {
        val p = Fixture.at(route, along, off)
        return Hazard(id = id, kind = kind, subkind = "", lat = p.lat, lng = p.lng,
                      createdAt = "2026-11-03T04:30:00Z", expiresAt = "2026-11-03T05:00:00Z",
                      confirmations = 0, dismissals = 0)
    }

    @Test
    fun onlyHazardsNearTheLineCount() {
        val on = RouteHazards.onRoute(route, listOf(hazard("a", 1800.0, 20.0), hazard("far", 1800.0, 400.0), hazard("b", 300.0, 0.0)))
        assertEquals(listOf("b", "a"), on.map { it.hazard.id })
        assertEquals(1800.0, on[1].along, 25.0)
    }

    @Test
    fun warnsWithinAKilometreAheadOnly() {
        val on = RouteHazards.onRoute(route, listOf(hazard("a", 1800.0, 0.0), hazard("b", 300.0, 0.0)))
        assertEquals("b", RouteHazards.nextAhead(on, 100.0)!!.hazard.id)
        assertEquals(200.0, RouteHazards.nextAhead(on, 100.0)!!.aheadM, 30.0)
        assertEquals("a", RouteHazards.nextAhead(on, 900.0)!!.hazard.id)
        assertEquals(900.0, RouteHazards.nextAhead(on, 900.0)!!.aheadM, 30.0)
        assertNull("1400 m ahead is too far to warn", RouteHazards.nextAhead(on, 400.0))
        assertNull("already past both", RouteHazards.nextAhead(on, 2000.0))
    }
}

class RouteLineTest {
    private val pts = listOf(LatLngD(-33.50, 149.50), LatLngD(-33.49, 149.51), LatLngD(-33.48, 149.52))
    private fun room(geometry: String? = null, dest: Destination? = Destination(-33.45, 149.558, "Pit area"), stops: List<List<Double>> = listOf(listOf(149.5, -33.5), listOf(149.558, -33.45))) =
        Room(code = "K7QXM2PR", destination = dest, route = app.convoyage.protocol.ConvoyRoute("r", stops), routeGeometry = geometry)

    @Test
    fun theRoomsRoadGeometryWins() {
        val line = RouteLine.pick(room(Polyline.encode(pts, 6)), null)
        assertEquals(3, line.size)
        assertEquals(-33.49, line[1].lat, 1e-6)
    }

    @Test
    fun withoutItTheLeadersOwnRoadRouteIsUsed() {
        val local = LocalRoute("K7QXM2PR", Destination(-33.45, 149.558, "Pit area"), pts)
        assertEquals(pts, RouteLine.pick(room(), local))
        // A route fetched for another destination or another convoy is ignored.
        assertEquals(2, RouteLine.pick(room(dest = Destination(-34.0, 150.0, "Elsewhere")), local).size)
        assertEquals(2, RouteLine.pick(room(), local.copy(code = "OTHER222")).size)
    }

    @Test
    fun otherwiseTheSharedStopsAreDrawn() {
        val line = RouteLine.pick(room(), null)
        assertEquals(listOf(LatLngD(-33.5, 149.5), LatLngD(-33.45, 149.558)), line)
        assertTrue(RouteLine.pick(null, null).isEmpty())
    }

    @Test
    fun aBrokenGeometryFallsBackInsteadOfCrashing() {
        assertEquals(2, RouteLine.pick(room("!!not a polyline!!"), null).size)
    }

    /**
     * Regression: evenly spaced stops cut corners and roundabouts on the real Bathurst to Mount Panorama route, so
     * the shared convoy line ran across blocks. Every point of the real route must stay close to the 25-stop line.
     */
    @Test
    fun sampledStopsFollowTheRealBathurstRoute() {
        val text = javaClass.classLoader!!.getResource("route_bathurst_mount_panorama.json")!!.readText()
        val geometry = Regex("\"geometry\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)!!.groupValues[1]
            .replace("\\\\", "\\")
        val pts = Polyline.decode(geometry, 6)
        assertTrue("fixture should be a real road route", pts.size > 100)
        val r = NavRoute("car", pts, 3820.0, 345.0, emptyList())
        val stops = NavMath.sampleStops(r, 25).map { LatLngD(it[1], it[0]) }
        assertTrue(stops.size <= 25)
        val worst = pts.maxOf { p -> stops.zipWithNext().minOf { (a, b) -> NavMath.offSegmentM(p, a, b) } }
        assertTrue("shared line strays ${worst.toInt()} m from the road", worst < 15.0)
    }

    @Test
    fun sampledStopsKeepBothEnds() {
        val r = Fixture.route()
        val stops = NavMath.sampleStops(r, 25)
        assertTrue(stops.size in 2..25)
        assertEquals(r.points.first().lng, stops.first()[0], 1e-9)
        assertEquals(r.points.last().lat, stops.last()[1], 1e-9)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {
    private class FakeApi(var responses: ArrayDeque<ApiResult<RouteResponse>>) : RouteApi {
        val calls = ArrayList<Pair<LatLngD, LatLngD>>()
        val headings = ArrayList<Double?>()
        override suspend fun route(from: LatLngD, to: LatLngD, profile: String, via: List<LatLngD>, headingDeg: Double?): ApiResult<RouteResponse> {
            calls += from to to
            headings += headingDeg
            return responses.removeFirstOrNull() ?: ApiResult.Err(404, "none")
        }
    }

    private class Said : Speaker {
        val lines = ArrayList<String>()
        override fun speak(text: String) { lines += text }
    }

    private val route = Fixture.route()
    private val dest = Place("Pit area", "Mount Panorama", -33.4895, 149.5300)

    private fun fix(p: LatLngD, t: Long, speed: Double? = 15.0) = Fix(p.lat, p.lng, null, speed, 5f, t)

    @Test
    fun followsTheRouteSpeaksAndEnds() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val fixes = MutableStateFlow<Fix?>(null)
        val said = Said()
        var now = 0L
        val api = FakeApi(ArrayDeque(listOf(ApiResult.Ok(Fixture.response()))))
        val nav = NavigationController(scope, api, fixes, MutableStateFlow(emptyList()), said, { "car" }, { now })

        nav.navigateTo(dest, fix(Fixture.at(route, 0.0), 0))
        assertEquals(NavPhase.Preview, nav.state.value.phase)
        assertTrue("nothing is spoken before Start", said.lines.isEmpty())
        nav.start()
        assertEquals(NavPhase.Active, nav.state.value.phase)

        var t = 0L
        var along = 0.0
        while (along < 2490.0) {
            now = t
            fixes.value = fix(Fixture.at(route, along), t)
            t += 1_000
            along += 15.0
        }
        assertEquals(NavPhase.Arrived, nav.state.value.phase)
        assertTrue(said.lines.any { it.startsWith("In ") })
        assertTrue(said.lines.last().startsWith("You have arrived"))
        assertEquals("only the first fetch: no reroute on the line", 1, api.calls.size)
        nav.end()
        assertEquals(NavPhase.Idle, nav.state.value.phase)
    }

    @Test
    fun reroutesAfterEightSecondsOffTheRoute() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val fixes = MutableStateFlow<Fix?>(null)
        val said = Said()
        var now = 0L
        val api = FakeApi(ArrayDeque(listOf(ApiResult.Ok(Fixture.response()), ApiResult.Ok(Fixture.response()))))
        val nav = NavigationController(scope, api, fixes, MutableStateFlow(emptyList()), said, { "car" }, { now })
        nav.navigateTo(dest, fix(Fixture.at(route, 0.0), 0))
        nav.start()

        // 200 m east of the road, standing still in the emulator's way: one second apart.
        for (s in 0..7) {
            now = s * 1_000L
            fixes.value = fix(Fixture.at(route, 300.0, 200.0), now)
        }
        assertEquals("8 s is not enough", 1, api.calls.size)
        now = 9_000
        fixes.value = fix(Fixture.at(route, 300.0, 200.0), now)
        assertEquals(2, api.calls.size)
        assertTrue(said.lines.contains("Rerouting"))
        assertFalse(nav.state.value.rerouting)
        assertEquals(NavPhase.Active, nav.state.value.phase)
    }

    @Test
    fun aDetourStartsAtOnceThenResumesToTheDestination() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val fixes = MutableStateFlow<Fix?>(null)
        val api = FakeApi(ArrayDeque(List(3) { ApiResult.Ok(Fixture.response()) }))
        val nav = NavigationController(scope, api, fixes, MutableStateFlow(emptyList()), Said(), { "car" })
        nav.navigateTo(dest, fix(Fixture.at(route, 0.0), 0))
        nav.start()
        val servo = Place("Servo", "", -33.45, 149.56)
        nav.detour(servo, fix(Fixture.at(route, 0.0), 1_000))
        assertEquals(NavPhase.Active, nav.state.value.phase)
        assertEquals(servo, nav.state.value.destination)
        assertEquals(dest, nav.state.value.resumeTo)
        nav.resume(null)
        assertEquals(dest, nav.state.value.destination)
        assertEquals(null, nav.state.value.resumeTo)
        assertEquals(NavPhase.Active, nav.state.value.phase)
    }

    @Test
    fun routesFromTheWayWeAreMoving() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val fixes = MutableStateFlow<Fix?>(null)
        val api = FakeApi(ArrayDeque(listOf(ApiResult.Ok(Fixture.response()))))
        val nav = NavigationController(scope, api, fixes, MutableStateFlow(emptyList()), Said(), { "car" })
        // No heading from the device: two fixes 20 m apart heading due north.
        fixes.value = Fix(-33.4000, 149.5000, null, 0.0, 5f, 0)
        fixes.value = Fix(-33.3998, 149.5000, null, 0.0, 5f, 2_000)
        nav.navigateTo(dest, fixes.value)
        val h = api.headings.single()!!
        assertTrue("north, got $h", h < 2.0 || h > 358.0)
    }

    @Test
    fun theDrivenPartOfTheLineGoes() {
        val line = listOf(LatLngD(0.0, 0.0), LatLngD(0.0, 1.0), LatLngD(0.0, 2.0), LatLngD(0.0, 3.0))
        val left = app.convoyage.ui.remainingLine(line, app.convoyage.nav.Snap(1, 0.0, 0.0, 0.0, 1.5))
        assertEquals(listOf(LatLngD(0.0, 1.5), LatLngD(0.0, 2.0), LatLngD(0.0, 3.0)), left)
    }

    @Test
    fun aMissingRouteShowsAnError() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val nav = NavigationController(scope, FakeApi(ArrayDeque(listOf(ApiResult.Err(404, "none")))), MutableStateFlow(null), MutableStateFlow(emptyList()), Said(), { "car" })
        nav.navigateTo(dest, fix(Fixture.at(route, 0.0), 0))
        assertEquals(NavPhase.Idle, nav.state.value.phase)
        assertEquals("No route to there", nav.state.value.error)
    }

    @Test
    fun warnsOfAHazardAheadOnTheRoute() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val fixes = MutableStateFlow<Fix?>(null)
        val p = Fixture.at(route, 700.0, 10.0)
        val near = Hazard("h1", "police", "", p.lat, p.lng, createdAt = "2026-11-03T04:30:00Z",
                          expiresAt = "2026-11-03T05:00:00Z", confirmations = 0, dismissals = 0)
        val far = Hazard("h2", "hazard", "", -33.0, 150.0, createdAt = "2026-11-03T04:30:00Z",
                         expiresAt = "2026-11-03T05:00:00Z", confirmations = 0, dismissals = 0)
        val hazards = MutableStateFlow(listOf(near, far))
        val nav = NavigationController(scope, FakeApi(ArrayDeque(listOf(ApiResult.Ok(Fixture.response())))), fixes, hazards, Said(), { "car" })
        nav.navigateTo(dest, fix(Fixture.at(route, 0.0), 0))
        assertEquals(listOf("h1"), nav.state.value.routeHazards.map { it.hazard.id })
        nav.start()
        fixes.value = fix(Fixture.at(route, 100.0), 1_000)
        assertEquals("h1", nav.state.value.hazardAhead?.hazard?.id)
        fixes.value = fix(Fixture.at(route, 1500.0), 2_000)
        assertNull(nav.state.value.hazardAhead)
    }
}
