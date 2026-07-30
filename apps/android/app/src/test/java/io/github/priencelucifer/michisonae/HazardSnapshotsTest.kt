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

    @Test
    fun oversizedSnapshotIsRejectedBeforeJsonParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRegionalHazardSnapshot("x".repeat(MAX_HAZARD_SNAPSHOT_BYTES + 1))
        }
    }

    @Test
    fun excessiveHazardCountIsRejectedByTheSnapshotModel() {
        val hazard = PublicRoadHazard(
            id = "a".repeat(24),
            kind = PublicHazardKind.ROAD_DAMAGE,
            latitude = 26.1445,
            longitude = 91.7362,
            severity = 0.5,
            confidence = 0.5,
            contributorCount = 2,
        )

        assertThrows(IllegalArgumentException::class.java) {
            RegionalHazardSnapshot(
                regionId = "gh5:wh9hx",
                version = null,
                generatedAtMillis = null,
                hazards = List(MAX_HAZARDS_PER_SNAPSHOT + 1) { hazard },
            )
        }
    }

    @Test
    fun alternatingBoundaryRegionsAreThrottledAndOnlyCurrentRegionApplies() {
        val gate = RegionalSnapshotRefreshGate(
            refreshIntervalMillis = 60_000,
            maxTrackedRegions = 2,
        )

        assertEquals(true, gate.shouldRefresh("gh5:aaaaa", 1_000))
        assertEquals(true, gate.shouldRefresh("gh5:bbbbb", 2_000))
        assertEquals(false, gate.shouldRefresh("gh5:aaaaa", 3_000))
        assertEquals(true, gate.isCurrent("gh5:aaaaa"))
        assertEquals(false, gate.isCurrent("gh5:bbbbb"))
        assertEquals(true, gate.shouldRefresh("gh5:aaaaa", 61_000))
    }

    @Test
    fun obsoleteRegionCannotEnterThePersistenceBlock() {
        val gate = RegionalSnapshotRefreshGate()
        gate.shouldRefresh("gh5:aaaaa", 1_000)
        gate.shouldRefresh("gh5:bbbbb", 2_000)
        var stored = false

        val result = gate.storeIfCurrent("gh5:aaaaa") {
            stored = true
            SimulatedRegionalHazards.guwahati(1_000)
        }

        assertEquals(null, result)
        assertEquals(false, stored)
    }
}
