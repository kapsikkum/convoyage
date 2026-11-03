package app.convoyage

import app.convoyage.data.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {
    @Test fun generatedHandleNeedsOnboarding() {
        assertTrue(Names.needsOnboarding("rider_c896a744", "rider_c896a744"))
        assertTrue(Names.needsOnboarding("Rider_C896A744", "somethingelse"))
        assertTrue(Names.needsOnboarding("", "rider_c896a744"))
        assertTrue(Names.needsOnboarding("  ", "x"))
        assertTrue(Names.needsOnboarding("sam_h", "sam_h")) // display name still equals the handle
    }

    @Test fun realNameSkipsOnboarding() {
        assertFalse(Names.needsOnboarding("Sam Harper", "rider_c896a744"))
    }

    @Test fun displayNameRules() {
        assertNull(Names.nameProblem("Sam"))
        assertNull(Names.nameProblem("  Sam Harper  "))
        assertNull(Names.nameProblem("Zoe Q"))
        assertNotNull(Names.nameProblem(""))
        assertNotNull(Names.nameProblem("S"))
        assertNotNull(Names.nameProblem("x".repeat(41)))
        assertNotNull(Names.nameProblem("!!!"))
        assertNotNull(Names.nameProblem("rider_c896a744"))
        assertNotNull(Names.nameProblem("bad\u0007name"))
    }

    @Test fun handleRulesMatchTheServer() {
        assertNull(Names.handleProblem(""))
        assertNull(Names.handleProblem("sam_h"))
        assertNull(Names.handleProblem("Sam_H"))
        assertNotNull(Names.handleProblem("ab"))
        assertNotNull(Names.handleProblem("a".repeat(31)))
        assertNotNull(Names.handleProblem("sam h"))
        assertNotNull(Names.handleProblem("sam-h"))
    }

    @Test fun rosterNeverShowsARawHandle() {
        assertEquals("Rider A744", Names.friendly("rider_c896a744"))
        assertEquals("Rider", Names.friendly(""))
        assertEquals("Sam", Names.friendly(" Sam "))
    }

    @Test fun convoyNamePrefill() {
        assertEquals("Sam's convoy", Names.defaultConvoyName("Sam"))
        assertEquals("", Names.defaultConvoyName("rider_c896a744"))
        assertEquals("", Names.defaultConvoyName(null))
        assertEquals(40, Names.defaultConvoyName("x".repeat(60)).length)
    }
}
