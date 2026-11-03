package app.convoyage.data

import app.convoyage.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesStoreTest {
    private val a = Place("Mount Panorama", "Bathurst", -33.4440, 149.5570)
    private val b = Place("Kelso", "NSW", -33.4180, 149.6050)

    @Test fun newestFirstAndNearDuplicatesDropped() {
        val nearA = Place("Mt Panorama lookout", "", -33.4442, 149.5571)
        val list = pushRecent(pushRecent(pushRecent(emptyList(), a), b), nearA)
        assertEquals(listOf(nearA, b), list)
    }

    @Test fun keepsAtMostMax() {
        var list = emptyList<Place>()
        repeat(12) { i -> list = pushRecent(list, Place("P$i", "", -33.0 - i * 0.01, 149.0)) }
        assertEquals(8, list.size)
        assertEquals("P11", list.first().name)
    }
}
