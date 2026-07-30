package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineHazardWarningsTest {
    private val snapshot = SimulatedRegionalHazards.guwahati(generatedAtMillis = 1_000)

    @Test
    fun warnsForNearestHazardInDirectionOfTravel() {
        val warning = findUpcomingHazard(
            snapshot = snapshot,
            latitude = 26.1445,
            longitude = 91.7362,
            headingDegrees = 0.0,
        )

        assertNotNull(warning)
        assertEquals("100000000000000000000001", warning?.hazard?.id)
        assertTrue(warning?.message?.contains("ahead") == true)
    }

    @Test
    fun doesNotWarnForHazardBehindTheCar() {
        assertNull(
            findUpcomingHazard(
                snapshot = snapshot,
                latitude = 26.1445,
                longitude = 91.7362,
                headingDegrees = 180.0,
                maximumHeadingDifferenceDegrees = 20.0,
            ),
        )
    }

    @Test
    fun worksWithoutHeadingByUsingNearestDistance() {
        assertEquals(
            "100000000000000000000001",
            findUpcomingHazard(
                snapshot = snapshot,
                latitude = 26.1445,
                longitude = 91.7362,
                headingDegrees = null,
            )?.hazard?.id,
        )
    }

    @Test
    fun cooldownSurvivesMissesAndAlternatingHazardIds() {
        val first = findUpcomingHazard(snapshot, 26.1445, 91.7362, 0.0)
        val second = findUpcomingHazard(snapshot, 26.1445, 91.7362, 100.0)
        val gate = PublicHazardWarningGate(cooldownMillis = 30_000)

        assertTrue(gate.shouldWarn(first, elapsedRealtimeMillis = 1_000))
        assertEquals(false, gate.shouldWarn(null, elapsedRealtimeMillis = 2_000))
        assertEquals(false, gate.shouldWarn(second, elapsedRealtimeMillis = 3_000))
        assertTrue(gate.shouldWarn(second, elapsedRealtimeMillis = 31_000))
    }

    @Test
    fun cooldownRejectsNonMonotonicTime() {
        val warning = findUpcomingHazard(snapshot, 26.1445, 91.7362, 0.0)
        val gate = PublicHazardWarningGate()
        gate.shouldWarn(warning, elapsedRealtimeMillis = 10_000)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            gate.shouldWarn(warning, elapsedRealtimeMillis = 9_999)
        }
    }
}
