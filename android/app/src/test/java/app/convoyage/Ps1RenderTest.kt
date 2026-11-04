package app.convoyage

import app.convoyage.api.Build
import app.convoyage.api.VehicleBadge
import app.convoyage.render.Ps1Camera
import app.convoyage.render.Ps1Finish
import app.convoyage.render.Ps1Geo
import app.convoyage.render.Ps1Kind
import app.convoyage.render.Ps1Body
import app.convoyage.render.Ps1Look
import app.convoyage.render.Ps1Math
import app.convoyage.render.Ps1Mesh
import app.convoyage.render.Ps1Models
import app.convoyage.render.Ps1Spec
import app.convoyage.render.Ps1Textures
import app.convoyage.render.Ps1Wheels
import app.convoyage.ui.of
import app.convoyage.ui.ps1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Ps1MeshTest {
    private fun allSpecs() = Ps1Kind.entries.flatMap { k -> Ps1Wheels.ALL.map { w -> Ps1Spec(kind = k, wheel = w) } }

    @Test fun everyKindBuildsAModelWithGeometry() {
        for (k in Ps1Kind.entries) {
            val m = Ps1Models.build(Ps1Spec(kind = k))
            assertTrue("$k has vertices", m.vertexCount > 100)
            assertTrue("$k has triangles", m.indices.size / 3 > 100)
            assertEquals("$k indices are whole triangles", 0, m.indices.size % 3)
            assertEquals("$k vertices are whole records", 0, m.vertices.size % Ps1Mesh.STRIDE)
        }
    }

    @Test fun counts() {
        // The ground is a 12-segment fan; the shadow a 10-segment one. The car adds the body, glass, 5 boxes and 4 wheels.
        val car = Ps1Models.build(Ps1Spec(kind = Ps1Kind.Car))
        assertEquals(12 * 3, car.groundIndexCount)
        assertEquals(13, car.groundIndexCount / 3 + 1)
        // A wheel is 8 side quads (16 triangles) and two 8-triangle faces.
        val wheelTris = 8 * 2 + 8 * 2
        assertEquals(32, wheelTris)
        val tris = car.indices.size / 3
        assertTrue("car has $tris triangles", tris in 150..1200)
        // The bike is the smallest model, the 4wd the busiest.
        val bike = Ps1Models.build(Ps1Spec(kind = Ps1Kind.Motorcycle)).indices.size
        val fwd = Ps1Models.build(Ps1Spec(kind = Ps1Kind.Fourwd)).indices.size
        assertTrue(bike < car.indices.size)
        assertTrue(fwd > car.indices.size)
    }

    @Test fun everyBodyBuildsCleanGeometry() {
        for (b in Ps1Body.entries) {
            val m = Ps1Models.build(Ps1Spec(kind = b.kind, body = b))
            assertTrue("$b has triangles", m.indices.size / 3 > 100)
            for ((i, v) in m.vertices.withIndex()) assertTrue("$b value $i = $v", v.isFinite())
            for (idx in m.indices) assertTrue("$b index", (idx.toInt() and 0xFFFF) < m.vertexCount)
            for (i in 0 until m.vertexCount) {
                val o = i * Ps1Mesh.STRIDE
                val len = sqrt(m.vertices[o + 3] * m.vertices[o + 3] + m.vertices[o + 4] * m.vertices[o + 4] + m.vertices[o + 5] * m.vertices[o + 5])
                assertEquals("$b vertex $i normal", 1f, len, 1e-3f)
            }
        }
    }

    @Test fun bodiesBelongToTheirKindAndChangeTheCacheKey() {
        // A body from another kind falls back to the kind's default.
        assertEquals(Ps1Body.Sedan, Ps1Spec.of("car", null, null, null, null, "cruiser").body)
        assertEquals(Ps1Body.Cruiser, Ps1Spec.of("motorcycle", null, null, null, null, "cruiser").body)
        assertEquals(Ps1Body.DualCab, Ps1Spec.of("ute", null, null, null, null, null).body)
        val keys = Ps1Body.entries.map { Ps1Spec(kind = it.kind, body = it).key() }
        assertEquals(keys.size, keys.toSet().size)
        for (k in Ps1Kind.entries) assertTrue("$k has a body", Ps1Body.forKind(k).isNotEmpty())
    }

    @Test fun noNaNOrInfinityInAnyVertexValue() {
        for (s in allSpecs()) {
            val m = Ps1Models.build(s)
            for ((i, v) in m.vertices.withIndex()) assertTrue("${s.kind}/${s.wheel.id} value $i = $v", v.isFinite())
        }
    }

    @Test fun indicesAreInRange() {
        for (s in allSpecs()) {
            val m = Ps1Models.build(s)
            assertTrue("fits 16-bit indices", m.vertexCount < 65_535)
            for (idx in m.indices) assertTrue("${s.kind} index ${idx.toInt() and 0xFFFF} < ${m.vertexCount}", (idx.toInt() and 0xFFFF) < m.vertexCount)
            assertTrue(m.groundIndexCount in 1 until m.indices.size)
            assertEquals(0, m.groundIndexCount % 3)
        }
    }

    @Test fun normalsAreUnitLength() {
        for (k in Ps1Kind.entries) {
            val m = Ps1Models.build(Ps1Spec(kind = k))
            for (i in 0 until m.vertexCount) {
                val o = i * Ps1Mesh.STRIDE
                val len = sqrt(m.vertices[o + 3] * m.vertices[o + 3] + m.vertices[o + 4] * m.vertices[o + 4] + m.vertices[o + 5] * m.vertices[o + 5])
                assertEquals("$k vertex $i normal", 1f, len, 1e-3f)
            }
        }
    }

    @Test fun surfaceIdsAreKnown() {
        val m = Ps1Models.build(Ps1Spec(kind = Ps1Kind.Car))
        for (i in 0 until m.vertexCount) {
            val tex = m.vertices[i * Ps1Mesh.STRIDE + 11].toInt()
            val flag = m.vertices[i * Ps1Mesh.STRIDE + 12].toInt()
            assertTrue(tex in 0..3)
            assertTrue(flag in 0..2)
        }
    }

    @Test fun rideHeightMovesTheBodyAndNotTheWheelsOrGround() {
        fun top(m: Ps1Mesh) = (0 until m.vertexCount).maxOf { m.vertices[it * Ps1Mesh.STRIDE + 1] }
        fun bottom(m: Ps1Mesh) = (0 until m.vertexCount).minOf { m.vertices[it * Ps1Mesh.STRIDE + 1] }
        val low = Ps1Models.build(Ps1Spec(rideHeightMm = -200))
        val stock = Ps1Models.build(Ps1Spec(rideHeightMm = 0))
        val high = Ps1Models.build(Ps1Spec(rideHeightMm = 200))
        assertTrue(top(low) < top(stock))
        assertTrue(top(stock) < top(high))
        assertEquals(bottom(low), bottom(high), 1e-6f)
        assertEquals(low.vertexCount, high.vertexCount)
    }

    @Test fun paintChangesOnlyTintsAndWheelStyleOnlyTheRimTexture() {
        val a = Ps1Models.build(Ps1Spec(paint = 0xFF0000))
        val b = Ps1Models.build(Ps1Spec(paint = 0x0000FF))
        assertEquals(a.indices.size, b.indices.size)
        assertFalse(a.vertices.contentEquals(b.vertices))
        // Wheel style is a texture and tint, not geometry.
        val w1 = Ps1Models.build(Ps1Spec(wheel = Ps1Wheels.of("mesh")))
        val w2 = Ps1Models.build(Ps1Spec(wheel = Ps1Wheels.of("steelie")))
        assertEquals(w1.indices.size, w2.indices.size)
    }

    @Test fun triangulationCoversThePolygonExactly() {
        val concave = listOf(0f to 0f, 4f to 0f, 4f to 3f, 2f to 1f, 0f to 3f)
        val tris = Ps1Geo.triangulate(concave)
        assertEquals(concave.size - 2, tris.size)
        val covered = tris.sumOf { t -> abs(Ps1Geo.area(listOf(concave[t[0]], concave[t[1]], concave[t[2]]))).toDouble() }
        assertEquals(abs(Ps1Geo.area(concave)).toDouble(), covered, 1e-4)
        // Either winding works.
        assertEquals(tris.size, Ps1Geo.triangulate(concave.reversed()).size)
    }

    @Test fun extrusionFollowsTheReferenceProfile() {
        val body = listOf(8f to 48f, 9f to 38f, 12f to 34f, 29f to 30f, 47f to 18.5f, 60f to 14f, 72f to 13.5f, 96f to 13.5f, 110f to 16.5f, 116f to 21.5f, 129f to 30f, 139f to 33f, 142f to 40.5f, 142f to 48f)
        val units = Ps1Geo.profile(body)
        assertEquals(-3.35f, units.minOf { it.first }, 1e-4f)
        assertEquals(3.35f, units.maxOf { it.first }, 1e-4f)
        assertEquals(0f, units.minOf { it.second }, 1e-4f)
        val mb = app.convoyage.render.MeshBuilder()
        Ps1Geo.extrude(mb, body, 3.1f, 0.78f, app.convoyage.render.Surf.srgb(0xFFFFFF))
        val m = mb.build(0)
        // Two caps of (n - 2) triangles and two triangles per side edge.
        assertEquals(2 * (body.size - 2) + 2 * body.size, m.indices.size / 3)
        val zs = (0 until m.vertexCount).map { m.vertices[it * Ps1Mesh.STRIDE + 2] }
        assertEquals(1.55f, zs.max(), 1e-4f)
        assertEquals(1.55f * 0.78f, zs.filter { it < 1.5f && it > 0f }.max(), 1e-4f) // the taper: vertices above y = 0.95 pulled in
    }

    @Test fun texturesAreFilled() {
        for (t in listOf(Ps1Textures.body(), Ps1Textures.ground()) + Ps1Wheels.ALL.map { Ps1Textures.rim(it) }) {
            assertEquals(t.size * t.size, t.argb.size)
            assertTrue(t.argb.all { (it ushr 24) == 0xFF })
            assertEquals(t.size * t.size * 4, t.rgbaBytes().size)
        }
        // The wheel styles really differ.
        assertFalse(Ps1Textures.rim(Ps1Wheels.of("mesh")).argb.contentEquals(Ps1Textures.rim(Ps1Wheels.of("sport5")).argb))
    }

    @Test fun finishesGiveDistinctLooks() {
        val matte = Ps1Look.of(Ps1Finish.Matte, 0xC8102E)
        val gloss = Ps1Look.of(Ps1Finish.Gloss, 0xC8102E)
        val metal = Ps1Look.of(Ps1Finish.Metallic, 0xC8102E)
        val pearl = Ps1Look.of(Ps1Finish.Pearl, 0xC8102E)
        assertEquals(0f, matte.spec, 0f)
        assertTrue(gloss.spec > 0f)
        assertTrue(metal.metal > 0f && metal.spec > gloss.spec)
        assertTrue(pearl.pearl > 0f && gloss.pearl == 0f)
        // Metallic highlights pick up the paint: red paint gives a redder highlight than blue.
        val blue = Ps1Look.of(Ps1Finish.Metallic, 0x1F4FD8)
        assertTrue(metal.specCol[0] > blue.specCol[0])
    }

    @Test fun cameraBacksOffForNarrowViewsAndZoomsIn() {
        val wide = Ps1Camera.distance(Ps1Body.Sedan, 1.43f)
        assertEquals(12.2f, wide, 1e-3f)
        assertTrue(Ps1Camera.distance(Ps1Body.Sedan, 1f) > wide)
        assertTrue(Ps1Camera.distance(Ps1Body.Sedan, 1.43f, zoom = 1.5f) < wide)
        assertTrue(Ps1Camera.distance(Ps1Body.Naked, 1.43f) < wide)
    }
}

class Ps1MathTest {
    @Test fun bayerMatrixIsAPermutationOfZeroToFifteen() {
        assertEquals((0..15).toList(), Ps1Math.BAYER4.sorted())
        assertEquals(0f, Ps1Math.bayer(0, 0), 0f)
        assertEquals(10 / 16f, Ps1Math.bayer(3, 0), 0f)
        assertEquals(15 / 16f, Ps1Math.bayer(0, 3), 0f) // row 3: 15, 7, 13, 5
        assertEquals(Ps1Math.bayer(1, 2), Ps1Math.bayer(5, 6), 0f) // repeats every four pixels
    }

    @Test fun ditherOffsetStaysWithinAHalfStepOfTheShaderRange() {
        for (y in 0..3) for (x in 0..3) assertTrue(Ps1Math.ditherOffset(x, y) in (-0.5f / 24f)..(0.5f / 24f))
        assertEquals(-0.5f / 24f, Ps1Math.ditherOffset(0, 0), 1e-7f)
    }

    @Test fun quantiseGivesThirtyTwoLevels() {
        assertEquals(0f, Ps1Math.quantise15(0f), 0f)
        assertEquals(1f, Ps1Math.quantise15(1f), 1e-6f)
        assertEquals(1f, Ps1Math.quantise15(7f), 1e-6f)
        assertEquals(0f, Ps1Math.quantise15(-3f), 0f)
        assertEquals(31, Ps1Math.level5(1f))
        val levels = (0..1000).map { Ps1Math.level5(it / 1000f) }.toSet()
        assertEquals(32, levels.size)
        // Idempotent: a quantised value stays put.
        for (i in 0..31) assertEquals(i / 31f, Ps1Math.quantise15(i / 31f), 1e-6f)
        // Half a step rounds up.
        assertEquals(1 / 31f, Ps1Math.quantise15(0.5f / 31f), 1e-6f)
    }

    @Test fun ditherMakesAFlatGreyUseTwoNeighbouringLevels() {
        val v = 10.5f / 31f // exactly between level 10 and 11
        val seen = HashSet<Int>()
        for (y in 0..3) for (x in 0..3) seen.add(Ps1Math.level5(v + Ps1Math.ditherOffset(x, y)))
        assertEquals(setOf(10, 11), seen)
        // A value on a level swings about 0.65 of a step either way, so it stays within one level of itself.
        val flat = HashSet<Int>()
        for (y in 0..3) for (x in 0..3) flat.add(Ps1Math.level5(10f / 31f + Ps1Math.ditherOffset(x, y)))
        assertEquals(setOf(9, 10, 11), flat)
    }

    @Test fun vertexSnapLandsOnPixelCentres() {
        // 240 rows: NDC step is 2/240.
        val s = Ps1Math.snap(0.3333f, 240)
        assertEquals(Math.round(0.3333f * 120f) / 120f, s, 1e-6f)
        assertEquals(0f, Ps1Math.snap(0.001f, 240), 0f)
        assertEquals(Ps1Math.snap(0.5f, 100), Ps1Math.snap(Ps1Math.snap(0.5f, 100), 100), 1e-6f)
    }

    @Test fun srgbToLinear() {
        assertEquals(0f, Ps1Math.srgbToLinear(0), 0f)
        assertEquals(1f, Ps1Math.srgbToLinear(255), 1e-6f)
        assertEquals(0.2159f, Ps1Math.srgbToLinear(128), 1e-3f)
    }

    @Test fun pixelScaleIsAWholeNumberAboutTwoFortyRows() {
        assertEquals(1, Ps1Math.pixelScale(240))
        assertEquals(3, Ps1Math.pixelScale(720))
        assertEquals(4, Ps1Math.pixelScale(960))
        assertEquals(1, Ps1Math.pixelScale(100))
    }
}

class Ps1SpecTest {
    @Test fun sameBuildGivesTheSameKeyWhateverTheCaseOrNoise() {
        val a = Ps1Spec.of("car", "#C8102E", "gloss", "mesh", 20)
        val b = Ps1Spec.of("car", "#c8102e", "gloss", "mesh", 20)
        assertEquals(a.key(), b.key())
        assertEquals(a.key("Badge"), b.key("Badge"))
        assertEquals(20, a.key().length)
        // Decals are not drawn so they are not in the key.
        assertEquals(Build(paint = "#c8102e", finish = "gloss", wheel = "mesh", rideHeightMm = 20).let { Ps1Spec.of("car", it) }.key(), a.key())
    }

    @Test fun anyVisibleChangeGivesAnotherKey() {
        val base = Ps1Spec.of("car", "#c8102e", "gloss", "mesh", 0)
        val keys = setOf(
            base.key(),
            Ps1Spec.of("ute", "#c8102e", "gloss", "mesh", 0).key(),
            Ps1Spec.of("car", "#c8102f", "gloss", "mesh", 0).key(),
            Ps1Spec.of("car", "#c8102e", "pearl", "mesh", 0).key(),
            Ps1Spec.of("car", "#c8102e", "gloss", "steelie", 0).key(),
            Ps1Spec.of("car", "#c8102e", "gloss", "mesh", 10).key(),
            base.key("Badge"),
        )
        assertEquals(7, keys.size)
    }

    @Test fun unknownAndOutOfRangeValuesAreNormalised() {
        assertEquals(Ps1Spec.of("car", "#b8bcc2", "gloss", "stock", 0).key(), Ps1Spec.of("spaceship", null, "shiny", "nonsense", null).key())
        assertEquals(Ps1Spec.of("car", null, null, null, 200).key(), Ps1Spec.of("car", null, null, null, 9999).key())
        assertEquals(-200, Ps1Spec.of("car", null, null, null, -5000).rideHeightMm)
        assertEquals(Ps1Spec.DEFAULT_PAINT, Ps1Spec.parseHex("not a colour"))
        assertEquals(0x1F4FD8, Ps1Spec.parseHex(" #1F4FD8 "))
    }

    @Test fun legacyWheelIdsMapToTheCatalogue() {
        assertEquals("mesh", Ps1Wheels.of("multi_spoke").id)
        assertEquals("split_spoke", Ps1Wheels.of("split_five").id)
        assertEquals("sport5", Ps1Wheels.of("forged_bronze").id)
        assertEquals(6, Ps1Wheels.ALL.map { it.id }.toSet().size)
        assertNotEquals(Ps1Wheels.of("sport5").spokes, Ps1Wheels.of("mesh").spokes)
    }

    @Test fun badgesAndVehiclesShareTheSpec() {
        val badge = VehicleBadge("ute", Build(paint = "#1f4fd8", finish = "pearl", wheel = "deep_dish", rideHeightMm = -40))
        val spec = badge.ps1()
        assertEquals(Ps1Kind.Ute, spec.kind)
        assertEquals(Ps1Finish.Pearl, spec.finish)
        assertEquals("deep_dish", spec.wheel.id)
        assertEquals(-40, spec.rideHeightMm)
    }
}
