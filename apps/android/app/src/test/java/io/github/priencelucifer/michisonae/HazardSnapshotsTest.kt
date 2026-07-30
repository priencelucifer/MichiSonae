package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HazardSnapshotsTest {
    @Test
    fun globalRegionIdIsStableForGuwahati() {
        assertEquals("gh5:wh9hx", regionalHazardId(26.1445, 91.7362))
    }

    @Test
    fun invalidPublicCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PublicRoadHazard(
                id = "a".repeat(24),
                kind = PublicHazardKind.ROAD_DAMAGE,
                latitude = 91.0,
                longitude = 0.0,
                severity = 0.5,
                confidence = 0.5,
                contributorCount = 2,
            )
        }
    }
}
