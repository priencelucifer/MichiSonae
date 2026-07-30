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
}
