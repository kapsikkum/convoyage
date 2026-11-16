package app.convoyage.ui

import app.convoyage.api.Vehicle
import app.convoyage.data.RideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideWordsTest {

    @Test
    fun aMotorcycleRidesAndLeans() {
        val w = RideWords.of("motorcycle", RideMode.Drive)
        assertTrue(w.bike)
        assertTrue(w.showsLean)
    }

    @Test
    fun theMotorcycleKindIsCaseInsensitive() {
        assertTrue(RideWords.of("Motorcycle", RideMode.Drive).bike)
        assertTrue(RideWords.of("MOTORCYCLE", RideMode.Both).bike)
    }

    @Test
    fun everyOtherKindIsACar() {
        // Absent/other kinds are cars, even when the onboarding mode was Ride.
        listOf("car", "ute", "4wd", "unknown", "", "Motorcycle ").forEach { kind ->
            val w = RideWords.of(kind, RideMode.Ride)
            assertFalse("kind \"$kind\" must be a car", w.bike)
            assertFalse("kind \"$kind\" must not lean", w.showsLean)
        }
    }

    @Test
    fun withNoVehicleTheOnboardingModeDecides() {
        assertTrue(RideWords.of(null as String?, RideMode.Ride).bike)
        assertFalse(RideWords.of(null as String?, RideMode.Drive).bike)
        assertFalse(RideWords.of(null as String?, RideMode.Both).bike)
    }

    @Test
    fun thePrimaryVehicleWinsOverTheOnboardingMode() {
        assertTrue(RideWords.of(Vehicle(id = "v1", kind = "motorcycle"), RideMode.Drive).bike)
        assertFalse(RideWords.of(Vehicle(id = "v2", kind = "ute"), RideMode.Ride).bike)
    }

    @Test
    fun motorcycleStrings() {
        val w = RideWords(bike = true)
        assertEquals("ride", w.noun)
        assertEquals("Ride", w.Noun)
        assertEquals("RIDE", w.NOUN)
        assertEquals("ride", w.verb)
        assertEquals("Ride", w.Verb)
        assertEquals("Record a ride", w.record)
        assertEquals("Track ride", w.track)
        assertEquals("Ride HUD", w.hud)
        assertEquals("LAST RIDE", w.last)
        assertEquals("Ride", w.tab)
        assertEquals("RIDE RECAP", w.recap)
        assertEquals("NO PHOTOS YET. RIDE PHOTOS LIVE ON EACH RIDE'S RECAP.", w.photos)
        assertTrue(w.showsLean)
    }

    @Test
    fun carStrings() {
        val w = RideWords(bike = false)
        assertEquals("drive", w.noun)
        assertEquals("Drive", w.Noun)
        assertEquals("DRIVE", w.NOUN)
        assertEquals("drive", w.verb)
        assertEquals("Drive", w.Verb)
        assertEquals("Record a drive", w.record)
        assertEquals("Track drive", w.track)
        assertEquals("Drive HUD", w.hud)
        assertEquals("LAST DRIVE", w.last)
        assertEquals("Drive", w.tab)
        assertEquals("DRIVE RECAP", w.recap)
        assertEquals("NO PHOTOS YET. DRIVE PHOTOS LIVE ON EACH DRIVE'S RECAP.", w.photos)
        assertFalse(w.showsLean)
    }
}
