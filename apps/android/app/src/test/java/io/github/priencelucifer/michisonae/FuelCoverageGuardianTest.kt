package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelCoverageGuardianTest {
    private val profile = VehicleProfile(
        nickname = "Test car",
        vehicleClass = VehicleClass.COMPACT,
        fuelType = FuelType.PETROL,
        tankCapacityLitres = 35.0,
        efficiencyKmPerLitre = 16.0,
    )

    @Test
    fun rangeIsConservativeAndClearlyAnEstimate() {
        val estimate = estimateFuelRange(profile, 25.0, FuelLevelSource.MANUAL)

        assertEquals(140.0, estimate.bestEstimateKm, 0.0001)
        assertEquals(112.0, estimate.conservativeKm, 0.0001)
        assertEquals(FuelLevelSource.MANUAL, estimate.source)
    }

    @Test
    fun warnsAtUpcomingStationWhenFollowingStationIsOutOfRange() {
        val estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.OBD)
        val scenario = FuelRouteScenarioSimulator.criticalGap(estimate)
        val upcoming = scenario.stationsAhead.first()
        val advice = FuelCoverageGuardian.evaluate(scenario)

        assertEquals(FuelAdviceLevel.FUEL_AT_UPCOMING_STATION, advice.level)
        assertEquals(upcoming, advice.station)
        assertTrue(advice.message.contains("If you miss it"))
        assertTrue(advice.message.contains("beyond"))
        assertTrue(advice.message.contains("Estimate only"))
    }

    @Test
    fun warnsWhenNoFuelStationCanBeReached() {
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimateFuelRange(profile, 2.0, FuelLevelSource.MANUAL),
            stationsAhead = listOf(FuelStationAhead("Too far", 50.0, null)),
            remainingRouteKm = 100.0,
        )

        assertEquals(FuelAdviceLevel.NO_REACHABLE_STATION, advice.level)
    }

    @Test
    fun closedStationIsNeverTreatedAsReachableFuel() {
        val estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.OBD)
        val advice = FuelCoverageGuardian.evaluate(
            FuelRouteScenarioSimulator.closedUpcoming(estimate),
        )

        assertEquals(FuelAdviceLevel.NO_REACHABLE_STATION, advice.level)
        assertTrue(advice.message.contains("closed"))
        assertTrue(advice.message.contains("beyond"))
    }

    @Test
    fun unknownOpeningStatusMustBeVerified() {
        val estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.OBD)
        val advice = FuelCoverageGuardian.evaluate(
            FuelRouteScenarioSimulator.unknownUpcoming(estimate),
        )

        assertEquals(FuelAdviceLevel.FUEL_AT_UPCOMING_STATION, advice.level)
        assertTrue(advice.message.contains("unknown opening status"))
        assertTrue(advice.message.contains("verify"))
    }

    @Test
    fun completedRouteAdviceStillSaysItIsAnEstimate() {
        val estimate = estimateFuelRange(profile, 50.0, FuelLevelSource.MANUAL)
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimate,
            stationsAhead = emptyList(),
            remainingRouteKm = 10.0,
        )

        assertEquals(FuelAdviceLevel.ENOUGH_RANGE, advice.level)
        assertTrue(advice.message.contains("Estimate only"))
    }

    @Test
    fun staleObdReadingMakesNoRangeClaim() {
        val estimate = estimateFuelRange(
            profile,
            FuelLevelSample(
                percent = 25.0,
                source = FuelLevelSource.OBD,
                observedAtEpochMillis = 1_000,
            ),
            validForMillis = 1_000,
        )
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimate,
            stationsAhead = emptyList(),
            remainingRouteKm = 10.0,
            evaluatedAtEpochMillis = 2_001,
        )

        assertEquals(FuelAdviceLevel.FUEL_DATA_UNAVAILABLE, advice.level)
        assertTrue(advice.message.contains("stale"))
        assertTrue(advice.message.contains("manually"))
    }

    @Test
    fun staleHoursBecomeUnknownAndOfflineCacheIsDisclosed() {
        val estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.OBD)
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimate,
            stationsAhead = listOf(
                FuelStationAhead(
                    name = "Cached station",
                    distanceAheadKm = 20.0,
                    isOpen = true,
                    hoursCheckedAtEpochMillis = 1_000,
                ),
            ),
            remainingRouteKm = 100.0,
            evaluatedAtEpochMillis = 8_000_000,
            stationDataUpdatedAtEpochMillis = 7_999_000,
            isOffline = true,
        )

        assertEquals(FuelAdviceLevel.FUEL_AT_UPCOMING_STATION, advice.level)
        assertTrue(advice.message.contains("unknown opening status"))
        assertTrue(advice.message.contains("offline"))
    }

    @Test
    fun offlineWithoutCachedStationsMakesNoReachabilityClaim() {
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimateFuelRange(profile, 2.0, FuelLevelSource.MANUAL),
            stationsAhead = emptyList(),
            remainingRouteKm = 100.0,
            evaluatedAtEpochMillis = 5_000,
            isOffline = true,
        )

        assertEquals(FuelAdviceLevel.STATION_DATA_UNAVAILABLE, advice.level)
    }

    @Test
    fun staleStationLocationsAreNotUsedForFuelAdvice() {
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimateFuelRange(profile, 2.0, FuelLevelSource.MANUAL),
            stationsAhead = listOf(FuelStationAhead("Old station", 5.0, true)),
            remainingRouteKm = 100.0,
            evaluatedAtEpochMillis = 100_000_000,
            stationDataUpdatedAtEpochMillis = 1_000,
        )

        assertEquals(FuelAdviceLevel.STATION_DATA_UNAVAILABLE, advice.level)
        assertTrue(advice.message.contains("too old"))
    }

    @Test
    fun missingStationUpdateTimeMakesNoReachabilityClaim() {
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.MANUAL),
            stationsAhead = listOf(FuelStationAhead("Unverified station", 20.0, true)),
            remainingRouteKm = 100.0,
            evaluatedAtEpochMillis = 10_000,
            stationDataUpdatedAtEpochMillis = null,
        )

        assertEquals(FuelAdviceLevel.STATION_DATA_UNAVAILABLE, advice.level)
        assertTrue(advice.message.contains("no verified update time"))
    }

    @Test
    fun missingHoursTimestampCannotBeSpokenAsOpen() {
        val advice = FuelCoverageGuardian.evaluate(
            estimate = estimateFuelRange(profile, 10.0, FuelLevelSource.MANUAL),
            stationsAhead = listOf(FuelStationAhead("Unverified hours", 20.0, true)),
            remainingRouteKm = 100.0,
            evaluatedAtEpochMillis = 10_000,
            stationDataUpdatedAtEpochMillis = 10_000,
        )

        assertEquals(FuelAdviceLevel.FUEL_AT_UPCOMING_STATION, advice.level)
        assertTrue(advice.message.contains("unknown opening status"))
        assertTrue(!advice.message.contains("which is listed open"))
    }

    @Test
    fun rejectsInvalidVehicleProfileAndRangeEstimate() {
        assertThrows(IllegalArgumentException::class.java) {
            estimateFuelRange(
                profile.copy(efficiencyKmPerLitre = Double.NaN),
                25.0,
                FuelLevelSource.MANUAL,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuelCoverageGuardian.evaluate(
                estimate = FuelRangeEstimate(
                    fuelPercent = 25.0,
                    bestEstimateKm = 100.0,
                    conservativeKm = Double.NaN,
                    source = FuelLevelSource.MANUAL,
                ),
                stationsAhead = emptyList(),
                remainingRouteKm = 10.0,
            )
        }
    }

    @Test
    fun criticalEngineCodesHaveDeterministicStopAdvice() {
        assertEquals(
            DiagnosticSeverity.STOP_SAFELY,
            DiagnosticPolicy.interpret("P0524").severity,
        )
    }
}
