package io.github.priencelucifer.michisonae

internal enum class ManualHazardCategory(
    val displayName: String,
    val observationKind: ObservationKind,
) {
    ROAD_DAMAGE("Road damage or pothole", ObservationKind.ROAD_DAMAGE),
    ROUGH_ROAD("Rough road", ObservationKind.ROUGH_ROAD),
    OBSTRUCTION("Obstruction or debris", ObservationKind.OBSTRUCTION),
    FLOODING("Flooding or waterlogging", ObservationKind.FLOODING),
    DAMAGED_MANHOLE("Open or damaged manhole", ObservationKind.MANHOLE_HAZARD),
    CONSTRUCTION("Road construction", ObservationKind.ROAD_CONSTRUCTION),
    DISABLED_VEHICLE("Disabled vehicle", ObservationKind.DISABLED_VEHICLE),
}

internal enum class ManualHazardSeverity(val score: Double) {
    LOW(0.35),
    MEDIUM(0.6),
    HIGH(0.85),
}

internal enum class ManualReportBlockReason {
    VEHICLE_MAY_BE_MOVING,
    LOCATION_UNAVAILABLE,
    LOCATION_TOO_INACCURATE,
}

internal data class ManualHazardReportDecision(
    val draft: RoadObservationDraft? = null,
    val blockedReason: ManualReportBlockReason? = null,
) {
    init {
        require((draft == null) != (blockedReason == null))
    }
}

internal object ManualRoadHazardReportPolicy {
    const val TAXONOMY_VERSION = 1

    fun prepare(
        category: ManualHazardCategory,
        severity: ManualHazardSeverity,
        drivingState: DrivingState,
        detectedAtMillis: Long,
        latitude: Double?,
        longitude: Double?,
        locationAccuracyMetres: Double?,
        speedMetresPerSecond: Double?,
    ): ManualHazardReportDecision {
        if (
            drivingState != DrivingState.IDLE ||
            speedMetresPerSecond == null ||
            !speedMetresPerSecond.isFinite() ||
            speedMetresPerSecond !in 0.0..MAX_STOPPED_SPEED_METRES_PER_SECOND
        ) {
            return ManualHazardReportDecision(
                blockedReason = ManualReportBlockReason.VEHICLE_MAY_BE_MOVING,
            )
        }
        if (
            latitude == null ||
            longitude == null ||
            locationAccuracyMetres == null ||
            !latitude.isFinite() ||
            !longitude.isFinite() ||
            !locationAccuracyMetres.isFinite() ||
            latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {
            return ManualHazardReportDecision(
                blockedReason = ManualReportBlockReason.LOCATION_UNAVAILABLE,
            )
        }
        if (locationAccuracyMetres !in 0.1..MAX_LOCATION_ACCURACY_METRES) {
            return ManualHazardReportDecision(
                blockedReason = ManualReportBlockReason.LOCATION_TOO_INACCURATE,
            )
        }
        val confidence = (
            MAX_MANUAL_CONFIDENCE -
                locationAccuracyMetres / LOCATION_CONFIDENCE_DIVISOR
            ).coerceIn(MIN_MANUAL_CONFIDENCE, MAX_MANUAL_CONFIDENCE)
        return ManualHazardReportDecision(
            draft = RoadObservationDraft(
                detectedAtMillis = detectedAtMillis,
                latitude = latitude,
                longitude = longitude,
                locationAccuracyMetres = locationAccuracyMetres,
                speedMetresPerSecond = speedMetresPerSecond,
                kind = category.observationKind,
                severity = severity.score,
                confidence = confidence,
                detectorVersion = "manual-v$TAXONOMY_VERSION",
            ),
        )
    }

    private const val MAX_STOPPED_SPEED_METRES_PER_SECOND = 1.0
    private const val MAX_LOCATION_ACCURACY_METRES = 60.0
    private const val MAX_MANUAL_CONFIDENCE = 0.78
    private const val MIN_MANUAL_CONFIDENCE = 0.55
    private const val LOCATION_CONFIDENCE_DIVISOR = 200.0
}
