package io.github.priencelucifer.michisonae

internal enum class FuelLevelSource(val displayName: String) {
    OBD("OBD-II"),
    MANUAL("Manual"),
}

internal data class FuelRangeEstimate(
    val fuelPercent: Double,
    val bestEstimateKm: Double,
    val conservativeKm: Double,
    val source: FuelLevelSource,
)

internal fun estimateFuelRange(
    profile: VehicleProfile,
    fuelPercent: Double,
    source: FuelLevelSource,
    uncertaintyBuffer: Double = 0.2,
): FuelRangeEstimate {
    require(fuelPercent in 0.0..100.0)
    require(uncertaintyBuffer in 0.0..0.5)
    val estimate = profile.tankCapacityLitres *
        fuelPercent / 100.0 *
        profile.efficiencyKmPerLitre
    return FuelRangeEstimate(
        fuelPercent = fuelPercent,
        bestEstimateKm = estimate,
        conservativeKm = estimate * (1.0 - uncertaintyBuffer),
        source = source,
    )
}

internal data class FuelStationAhead(
    val name: String,
    val distanceAheadKm: Double,
    val isOpen: Boolean?,
)

internal enum class FuelAdviceLevel {
    ENOUGH_RANGE,
    FUEL_AT_UPCOMING_STATION,
    NO_REACHABLE_STATION,
}

internal data class FuelAdvice(
    val level: FuelAdviceLevel,
    val message: String,
    val station: FuelStationAhead? = null,
)

internal data class FuelRouteScenario(
    val estimate: FuelRangeEstimate,
    val stationsAhead: List<FuelStationAhead>,
    val remainingRouteKm: Double,
)

internal object FuelCoverageGuardian {
    fun evaluate(scenario: FuelRouteScenario): FuelAdvice = evaluate(
        estimate = scenario.estimate,
        stationsAhead = scenario.stationsAhead,
        remainingRouteKm = scenario.remainingRouteKm,
    )

    fun evaluate(
        estimate: FuelRangeEstimate,
        stationsAhead: List<FuelStationAhead>,
        remainingRouteKm: Double,
    ): FuelAdvice {
        require(remainingRouteKm.isFinite() && remainingRouteKm >= 0.0)
        val stations = stationsAhead
            .onEach {
                require(it.name.isNotBlank())
                require(it.distanceAheadKm.isFinite() && it.distanceAheadKm >= 0.0)
            }
            .filter { it.distanceAheadKm <= remainingRouteKm }
            .sortedBy { it.distanceAheadKm }
        if (remainingRouteKm <= estimate.conservativeKm) {
            return FuelAdvice(
                FuelAdviceLevel.ENOUGH_RANGE,
                "Estimate only: the conservative fuel range covers the remaining route.",
            )
        }

        val withinRange = stations.filter {
            it.distanceAheadKm <= estimate.conservativeKm
        }
        val selected = withinRange.lastOrNull { it.isOpen == true }
            ?: withinRange.lastOrNull { it.isOpen == null }
        if (selected == null) {
            val closed = withinRange.count { it.isOpen == false }
            val nextOpen = stations.firstOrNull { it.isOpen == true }
            val reason = when {
                closed > 0 && nextOpen != null ->
                    "Stations within range are listed closed, and ${nextOpen.name} is beyond " +
                        "the conservative estimated range."

                closed > 0 -> "All listed stations within range are closed."
                nextOpen != null ->
                    "${nextOpen.name} is beyond the conservative estimated range."

                else -> "No usable fuel station is listed within the conservative estimated range."
            }
            return FuelAdvice(
                FuelAdviceLevel.NO_REACHABLE_STATION,
                "Estimate only: $reason",
            )
        }

        val nextOpen = stations.firstOrNull {
            it.distanceAheadKm > selected.distanceAheadKm && it.isOpen == true
        }
        val availability = if (selected.isOpen == true) {
            "is listed open"
        } else {
            "has unknown opening status; verify it before relying on it"
        }
        val gap = if (nextOpen == null) {
            "No later station is listed open on this route."
        } else {
            "If you miss it, ${nextOpen.name} is beyond the conservative estimated range."
        }
        return FuelAdvice(
            FuelAdviceLevel.FUEL_AT_UPCOMING_STATION,
            "Estimate only: fuel at ${selected.name}, which $availability. $gap",
            selected,
        )
    }
}

internal object FuelRouteScenarioSimulator {
    fun criticalGap(estimate: FuelRangeEstimate): FuelRouteScenario = FuelRouteScenario(
        estimate = estimate,
        stationsAhead = listOf(
            FuelStationAhead("Current reachable station", estimate.conservativeKm * 0.5, true),
            FuelStationAhead("Next station", estimate.conservativeKm + 10.0, true),
        ),
        remainingRouteKm = estimate.conservativeKm * 2.0,
    )

    fun closedUpcoming(estimate: FuelRangeEstimate): FuelRouteScenario = FuelRouteScenario(
        estimate = estimate,
        stationsAhead = listOf(
            FuelStationAhead("Closed station", estimate.conservativeKm * 0.5, false),
            FuelStationAhead("Next open station", estimate.conservativeKm + 10.0, true),
        ),
        remainingRouteKm = estimate.conservativeKm * 2.0,
    )

    fun unknownUpcoming(estimate: FuelRangeEstimate): FuelRouteScenario = FuelRouteScenario(
        estimate = estimate,
        stationsAhead = listOf(
            FuelStationAhead("Unverified station", estimate.conservativeKm * 0.5, null),
            FuelStationAhead("Next open station", estimate.conservativeKm + 10.0, true),
        ),
        remainingRouteKm = estimate.conservativeKm * 2.0,
    )
}
