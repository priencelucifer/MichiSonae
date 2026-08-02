package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun everyContractHazardKindHasADeterministicWarningLabel() {
        val kinds = mapOf(
            "road_damage" to "Road damage",
            "rough_road" to "Rough road",
            "obstruction" to "Road obstruction",
            "flooding" to "Flooding",
            "manhole_hazard" to "Manhole hazard",
            "road_construction" to "Road construction",
            "disabled_vehicle" to "Disabled vehicle",
        )

        kinds.forEach { (wireName, label) ->
            assertEquals(label, PublicHazardKind.fromWireName(wireName).warningLabel)
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

    @Test
    fun adjacentRegionsIncludeCurrentCellAndStayBounded() {
        val regions = adjacentRegionalHazardIds("gh5:wh9hx")

        assertEquals("gh5:wh9hx", regions.first())
        assertEquals(regions.distinct(), regions)
        assertTrue(regions.size in 6..9)
    }

    @Test
    fun adjacentRegionsWrapAtTheInternationalDateLine() {
        val region = regionalHazardId(0.0, 179.999, precision = 5)

        adjacentRegionalHazardIds(region).forEach {
            assertTrue(it.matches(Regex("gh5:[0123456789bcdefghjkmnpqrstuvwxyz]{5}")))
        }
    }

    @Test
    fun cacheIndexEvictsOldPrefetchBeforeTheCurrentRegion() {
        val current = "gh5:bbbbb"
        var index = HazardCacheIndex(current, listOf(current))
        listOf("gh5:ccccc", "gh5:ddddd", "gh5:eeeee").forEach {
            index = hazardCacheIndexAfterWrite(
                existing = index,
                regionId = it,
                makeCurrent = false,
                maxRegions = 3,
            )
        }

        assertEquals(current, index.currentRegionId)
        assertEquals(listOf(current, "gh5:ddddd", "gh5:eeeee"), index.regions)
    }

    @Test
    fun legacySnapshotMigrationMakesItsRegionCurrent() {
        val snapshot = SimulatedRegionalHazards.guwahati(1_000)

        assertEquals(
            HazardCacheIndex(snapshot.regionId, listOf(snapshot.regionId)),
            hazardCacheIndexForLegacySnapshot(snapshot),
        )
    }

    @Test
    fun futureCacheIndexIsNotSilentlyDowngraded() {
        assertThrows(UnsupportedHazardCacheSchema::class.java) {
            validateHazardCacheIndexSchema(2)
        }
        assertEquals(
            false,
            shouldMigrateLegacyHazardCache(indexExists = true, legacyExists = true),
        )
    }

    @Test
    fun corruptedSnapshotFieldsFailClosed() {
        val valid = validSnapshotJson()
        val corruptions = listOf(
            valid.replace("\"schema_version\":\"1.0\"", "\"schema_version\":\"2.0\""),
            valid.replace("\"hazard_count\":1", "\"hazard_count\":2"),
            valid.replace("\"kind\":\"road_damage\"", "\"kind\":\"unknown\""),
            valid.replace("\"contributor_count\":2", "\"contributor_count\":1"),
            valid.replace("\"latitude\":26.1445", "\"latitude\":91.0"),
            valid.dropLast(1),
        )

        corruptions.forEach { corrupted ->
            assertTrue(runCatching { parseRegionalHazardSnapshot(corrupted) }.isFailure)
        }
    }

    @Test
    fun validSnapshotReplayPreservesEveryPublicField() {
        val snapshot = parseRegionalHazardSnapshot(validSnapshotJson())

        assertEquals("gh5:wh9hx", snapshot.regionId)
        assertEquals("a".repeat(64), snapshot.version)
        assertEquals(1_767_225_600_000L, snapshot.generatedAtMillis)
        assertEquals(PublicHazardKind.ROAD_DAMAGE, snapshot.hazards.single().kind)
        assertEquals(2, snapshot.hazards.single().contributorCount)
    }

    @Test
    fun corruptedCacheIndexesCannotSelectAnUnlistedOrDuplicateRegion() {
        listOf(
            """{"schema_version":1,"current_region_id":"gh5:aaaaa","regions":[]}""",
            """{"schema_version":1,"current_region_id":"gh5:aaaaa","regions":["gh5:aaaaa","gh5:aaaaa"]}""",
            """{"schema_version":1,"current_region_id":"bad","regions":["bad"]}""",
        ).forEach { corrupted ->
            assertTrue(runCatching { decodeHazardCacheIndex(corrupted) }.isFailure)
        }
    }

    @Test
    fun elapsedClockRollbackCannotSuppressARegionRefresh() {
        val gate = RegionalSnapshotRefreshGate(refreshIntervalMillis = 60_000)

        assertEquals(true, gate.shouldRefresh("gh5:aaaaa", 120_000))
        assertEquals(true, gate.shouldRefresh("gh5:aaaaa", 1_000))
        assertEquals(false, gate.shouldRefresh("gh5:aaaaa", 1_001))
    }

    private fun validSnapshotJson(): String =
        """
        {
          "schema_version":"1.0",
          "region_id":"gh5:wh9hx",
          "version":"${"a".repeat(64)}",
          "generated_at":"2026-01-01T00:00:00Z",
          "hazard_count":1,
          "hazards":[{
            "hazard_id":"${"b".repeat(24)}",
            "kind":"road_damage",
            "latitude":26.1445,
            "longitude":91.7362,
            "severity":0.7,
            "confidence":0.8,
            "contributor_count":2
          }]
        }
        """.trimIndent()
}
