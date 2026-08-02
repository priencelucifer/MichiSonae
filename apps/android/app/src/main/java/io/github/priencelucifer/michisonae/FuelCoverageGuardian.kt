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
    val observedAtEpochMillis: Long? = null,
    val validForMillis: Long? = null,
)

internal data class FuelLevelSample(
    val percent: Double,
    val source: FuelLevelSource,
    val observedAtEpochMillis: Long,
)

internal fun estimateFuelRange(
    profile: VehicleProfile,
    fuelPercent: Double,
    source: FuelLevelSource,
    uncertaintyBuffer: Double = 0.2,
): FuelRangeEstimate {
    require(profile.validationError() == null)
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

internal fun estimateFuelRange(
    profile: VehicleProfile,
    sample: FuelLevelSample,
    uncertaintyBuffer: Double = 0.2,
    validForMillis: Long = when (sample.source) {
        FuelLevelSource.OBD -> OBD_FUEL_READING_MAX_AGE_MILLIS
        FuelLevelSource.MANUAL -> MANUAL_FUEL_READING_MAX_AGE_MILLIS
    },
): FuelRangeEstimate {
    require(sample.observedAtEpochMillis >= 0)
    require(validForMillis > 0)
    return estimateFuelRange(
        profile = profile,
        fuelPercent = sample.percent,
        source = sample.source,
        uncertaintyBuffer = uncertaintyBuffer,
    ).copy(
        observedAtEpochMillis = sample.observedAtEpochMillis,
        validForMillis = validForMillis,
    )
}

internal data class FuelStationAhead(
    val name: String,
    val distanceAheadKm: Double,
    val isOpen: Boolean?,
    val hoursCheckedAtEpochMillis: Long? = null,
)

internal enum class FuelAdviceLevel {
    ENOUGH_RANGE,
    FUEL_AT_UPCOMING_STATION,
    NO_REACHABLE_STATION,
    FUEL_DATA_UNAVAILABLE,
    STATION_DATA_UNAVAILABLE,
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
    val evaluatedAtEpochMillis: Long? = null,
    val stationDataUpdatedAtEpochMillis: Long? = null,
    val isOffline: Boolean = false,
)

internal object FuelCoverageGuardian {
    fun evaluate(scenario: FuelRouteScenario): FuelAdvice = evaluate(
        estimate = scenario.estimate,
        stationsAhead = scenario.stationsAhead,
        remainingRouteKm = scenario.remainingRouteKm,
        evaluatedAtEpochMillis = scenario.evaluatedAtEpochMillis,
        stationDataUpdatedAtEpochMillis = scenario.stationDataUpdatedAtEpochMillis,
        isOffline = scenario.isOffline,
    )

    fun evaluate(
        estimate: FuelRangeEstimate,
        stationsAhead: List<FuelStationAhead>,
        remainingRouteKm: Double,
        evaluatedAtEpochMillis: Long? = null,
        stationDataUpdatedAtEpochMillis: Long? = null,
        isOffline: Boolean = false,
    ): FuelAdvice {
        require(estimate.fuelPercent in 0.0..100.0)
        require(estimate.bestEstimateKm.isFinite() && estimate.bestEstimateKm >= 0.0)
        require(estimate.conservativeKm.isFinite() && estimate.conservativeKm >= 0.0)
        require((estimate.observedAtEpochMillis == null) == (estimate.validForMillis == null))
        require(estimate.observedAtEpochMillis == null || estimate.observedAtEpochMillis >= 0)
        require(estimate.validForMillis == null || estimate.validForMillis > 0)
        require(remainingRouteKm.isFinite() && remainingRouteKm >= 0.0)
        require(evaluatedAtEpochMillis == null || evaluatedAtEpochMillis >= 0)
        require(
            stationDataUpdatedAtEpochMillis == null ||
                stationDataUpdatedAtEpochMillis >= 0,
        )
        if (estimate.isStaleAt(evaluatedAtEpochMillis)) {
            return FuelAdvice(
                FuelAdviceLevel.FUEL_DATA_UNAVAILABLE,
                "Fuel reading is stale. Reconnect OBD or enter the fuel level manually. " +
                    "No range claim is available.",
            )
        }
        val stations = stationsAhead
            .onEach {
                require(it.name.isNotBlank())
                require(it.distanceAheadKm.isFinite() && it.distanceAheadKm >= 0.0)
                require(it.hoursCheckedAtEpochMillis == null || it.hoursCheckedAtEpochMillis >= 0)
            }
            .filter { it.distanceAheadKm <= remainingRouteKm }
            .sortedBy { it.distanceAheadKm }
        if (remainingRouteKm <= estimate.conservativeKm) {
            return FuelAdvice(
                FuelAdviceLevel.ENOUGH_RANGE,
                "Estimate only: the conservative fuel range covers the remaining route.",
            )
        }
        if (
            evaluatedAtEpochMillis != null &&
            stationDataUpdatedAtEpochMillis == null
        ) {
            return FuelAdvice(
                FuelAdviceLevel.STATION_DATA_UNAVAILABLE,
                "Station information has no verified update time. Refresh it before choosing " +
                    "where to refuel.",
            )
        }
        if (
            stationDataUpdatedAtEpochMillis.isOlderThan(
                evaluatedAtEpochMillis,
                STATION_DATA_MAX_AGE_MILLIS,
            )
        ) {
            return FuelAdvice(
                FuelAdviceLevel.STATION_DATA_UNAVAILABLE,
                "Station information is too old to rely on. Refresh it before choosing " +
                    "where to refuel.",
            )
        }
        if (isOffline && stations.isEmpty()) {
            return FuelAdvice(
                FuelAdviceLevel.STATION_DATA_UNAVAILABLE,
                "Offline and no cached fuel stations are available for the remaining route.",
            )
        }

        val withinRange = stations.filter {
            it.distanceAheadKm <= estimate.conservativeKm
        }
        fun FuelStationAhead.currentOpenStatus(): Boolean? =
            if (evaluatedAtEpochMillis != null && hoursCheckedAtEpochMillis == null) {
                null
            } else if (
                hoursCheckedAtEpochMillis.isOlderThan(
                    evaluatedAtEpochMillis,
                    STATION_HOURS_MAX_AGE_MILLIS,
                )
            ) {
                null
            } else {
                isOpen
            }

        val selected = withinRange.lastOrNull { it.currentOpenStatus() == true }
            ?: withinRange.lastOrNull { it.currentOpenStatus() == null }
        if (selected == null) {
            val closed = withinRange.count { it.currentOpenStatus() == false }
            val nextOpen = stations.firstOrNull { it.currentOpenStatus() == true }
            val reason = when {
                closed > 0 && nextOpen != null ->
                    "Stations within range are listed closed, and ${nextOpen.name} is beyond " +
                        "the conservative estimated range."

                closed > 0 -> "All listed stations within range are closed."
                nextOpen != null ->
                    "${nextOpen.name} is beyond the conservative estimated range."

                else -> "No usable fuel station is listed within the conservative estimated range."
            }
            val dataMode = if (isOffline) " Cached station data may be incomplete." else ""
            return FuelAdvice(
                FuelAdviceLevel.NO_REACHABLE_STATION,
                "Estimate only: $reason$dataMode",
            )
        }

        val nextOpen = stations.firstOrNull {
            it.distanceAheadKm > selected.distanceAheadKm &&
                it.currentOpenStatus() == true
        }
        val availability = if (selected.currentOpenStatus() == true) {
            "is listed open"
        } else {
            "has unknown opening status; verify it before relying on it"
        }
        val gap = if (nextOpen == null) {
            "No later station is listed open on this route."
        } else {
            "If you miss it, ${nextOpen.name} is beyond the conservative estimated range."
        }
        val dataMode = if (isOffline) " Using cached station data while offline." else ""
        return FuelAdvice(
            FuelAdviceLevel.FUEL_AT_UPCOMING_STATION,
            "Estimate only: fuel at ${selected.name}, which $availability. $gap$dataMode",
            selected,
        )
    }
}

private fun FuelRangeEstimate.isStaleAt(nowEpochMillis: Long?): Boolean {
    val observedAt = observedAtEpochMillis ?: return false
    val maxAge = validForMillis ?: return false
    val now = nowEpochMillis ?: return false
    require(observedAt >= 0 && maxAge > 0)
    return now > observedAt && now - observedAt > maxAge
}

private fun Long?.isOlderThan(nowEpochMillis: Long?, maxAgeMillis: Long): Boolean {
    val observedAt = this ?: return false
    val now = nowEpochMillis ?: return false
    return now > observedAt && now - observedAt > maxAgeMillis
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

private const val OBD_FUEL_READING_MAX_AGE_MILLIS = 2L * 60 * 1_000
private const val MANUAL_FUEL_READING_MAX_AGE_MILLIS = 4L * 60 * 60 * 1_000
private const val STATION_HOURS_MAX_AGE_MILLIS = 2L * 60 * 60 * 1_000
private const val STATION_DATA_MAX_AGE_MILLIS = 24L * 60 * 60 * 1_000
