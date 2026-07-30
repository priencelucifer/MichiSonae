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

internal object FuelCoverageGuardian {
    fun evaluate(
        estimate: FuelRangeEstimate,
        stationsAhead: List<FuelStationAhead>,
        remainingRouteKm: Double,
    ): FuelAdvice {
        require(remainingRouteKm >= 0.0)
        val stations = stationsAhead
            .onEach { require(it.distanceAheadKm >= 0.0) }
            .sortedBy { it.distanceAheadKm }
        if (remainingRouteKm <= estimate.conservativeKm) {
            return FuelAdvice(
                FuelAdviceLevel.ENOUGH_RANGE,
                "Estimated fuel range covers the remaining route.",
            )
        }

        val reachable = stations.filter { it.distanceAheadKm <= estimate.conservativeKm }
        if (reachable.isEmpty()) {
            return FuelAdvice(
                FuelAdviceLevel.NO_REACHABLE_STATION,
                "No listed fuel station is within the conservative estimated range.",
            )
        }

        val lastReachable = reachable.last()
        val next = stations.firstOrNull {
            it.distanceAheadKm > lastReachable.distanceAheadKm
        }
        val reason = if (next == null) {
            "No later fuel station is listed on this route."
        } else {
            "The following station may be beyond the conservative estimated range."
        }
        return FuelAdvice(
            FuelAdviceLevel.FUEL_AT_UPCOMING_STATION,
            "Fuel at ${lastReachable.name}. $reason",
            lastReachable,
        )
    }
}
