package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
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
    fun criticalEngineCodesHaveDeterministicStopAdvice() {
        assertEquals(
            DiagnosticSeverity.STOP_SAFELY,
            DiagnosticPolicy.interpret("P0524").severity,
        )
    }
}
