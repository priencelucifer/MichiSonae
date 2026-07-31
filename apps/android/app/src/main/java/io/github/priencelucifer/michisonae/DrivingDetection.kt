package io.github.priencelucifer.michisonae

import kotlin.math.abs
import kotlin.math.sqrt

internal data class MotionSample(
    val timestampMillis: Long,
    val speedKph: Double,
    val speedAccuracyKph: Double = 0.0,
)

internal enum class DrivingState(val displayName: String) {
    IDLE("Not driving"),
    UNCERTAIN("Movement uncertain"),
    CHECKING("Checking movement"),
    DRIVING("Driving detected"),
}

internal class AutomaticDrivingDetector(
    private val startSpeedKph: Double = 10.0,
    private val startConfirmationMillis: Long = 30_000,
    private val stopSpeedKph: Double = 3.0,
    private val stopConfirmationMillis: Long = 180_000,
) {
    private var state = DrivingState.IDLE
    private var candidateSince: Long? = null
    private var lastTimestampMillis: Long? = null

    fun update(sample: MotionSample): DrivingState {
        if (
            sample.timestampMillis < 0 ||
            !sample.speedKph.isFinite() ||
            !sample.speedAccuracyKph.isFinite() ||
            sample.speedKph !in 0.0..MAX_SPEED_KPH ||
            sample.speedAccuracyKph !in 0.0..MAX_SPEED_ACCURACY_KPH ||
            lastTimestampMillis?.let { sample.timestampMillis <= it } == true
        ) {
            return state
        }
        lastTimestampMillis = sample.timestampMillis
        val confidentlyMoving =
            sample.speedKph - sample.speedAccuracyKph >= startSpeedKph
        val confidentlyIdle =
            sample.speedKph + sample.speedAccuracyKph < startSpeedKph
        val confidentlyStopped =
            sample.speedKph + sample.speedAccuracyKph <= stopSpeedKph

        when (state) {
            DrivingState.IDLE -> {
                when {
                    confidentlyMoving -> {
                        candidateSince = sample.timestampMillis
                        state = DrivingState.CHECKING
                    }
                    !confidentlyIdle -> state = DrivingState.UNCERTAIN
                }
            }

            DrivingState.UNCERTAIN -> {
                when {
                    confidentlyMoving -> {
                        candidateSince = sample.timestampMillis
                        state = DrivingState.CHECKING
                    }
                    confidentlyIdle -> state = DrivingState.IDLE
                }
            }

            DrivingState.CHECKING -> {
                when {
                    confidentlyIdle -> {
                        candidateSince = null
                        state = DrivingState.IDLE
                    }
                    !confidentlyMoving -> {
                        candidateSince = null
                        state = DrivingState.UNCERTAIN
                    }
                    sample.timestampMillis - checkNotNull(candidateSince) >=
                        startConfirmationMillis -> {
                        candidateSince = null
                        state = DrivingState.DRIVING
                    }
                }
            }

            DrivingState.DRIVING -> {
                if (confidentlyStopped) {
                    val stoppedSince = candidateSince
                    if (stoppedSince == null) {
                        candidateSince = sample.timestampMillis
                    } else if (sample.timestampMillis - stoppedSince >= stopConfirmationMillis) {
                        candidateSince = null
                        state = DrivingState.IDLE
                    }
                } else {
                    candidateSince = null
                }
            }
        }
        return state
    }

    private companion object {
        const val MAX_SPEED_KPH = 300.0
        const val MAX_SPEED_ACCURACY_KPH = 100.0
    }
}

internal data class RoadSample(
    val speedKph: Double,
    val verticalLinearAccelerationG: Double,
    val timestampMillis: Long? = null,
    val speedAccuracyKph: Double = 0.0,
    val locationAccuracyMetres: Double = 0.0,
    val locationAgeMillis: Long = 0,
)

internal fun linearAccelerationMagnitudeG(x: Float, y: Float, z: Float): Double =
    sqrt((x * x + y * y + z * z).toDouble()) / STANDARD_GRAVITY

private const val STANDARD_GRAVITY = 9.80665

internal enum class RoadHazard(val userMessage: String) {
    SUDDEN_IMPACT("Road hazard ahead. Slow down safely."),
    ROUGH_ROAD("Rough road detected. Reduce speed safely."),
}

internal data class RoadDetectionTuning(
    val minimumSpeedKph: Double = 8.0,
    val impactThresholdG: Double = 0.65,
    val roughThresholdG: Double = 0.25,
    val impactReleaseThresholdG: Double = 0.15,
    val roughConfirmationMillis: Long = 100,
    val maximumSampleGapMillis: Long = 150,
    val maximumLocationAgeMillis: Long = 5_000,
    val maximumLocationAccuracyMetres: Double = 60.0,
    val maximumAccelerationG: Double = 6.0,
) {
    init {
        require(minimumSpeedKph > 0.0)
        require(impactThresholdG > roughThresholdG)
        require(roughThresholdG > impactReleaseThresholdG)
        require(impactReleaseThresholdG >= 0.0)
        require(roughConfirmationMillis > 0)
        require(maximumSampleGapMillis > 0)
        require(maximumLocationAgeMillis > 0)
        require(maximumLocationAccuracyMetres > 0.0)
        require(maximumAccelerationG > impactThresholdG)
    }
}

internal class PhoneRoadHazardDetector(
    private val tuning: RoadDetectionTuning = RoadDetectionTuning(),
) {
    private var implicitTimestampMillis = 0L
    private var lastTimestampMillis: Long? = null
    private var roughSinceMillis: Long? = null
    private var roughReported = false
    private var impactArmed = true

    fun observe(
        sample: RoadSample,
        vehicleClass: VehicleClass,
        drivingState: DrivingState,
    ): RoadHazard? {
        val timestampMillis = sample.timestampMillis ?: implicitTimestampMillis.also {
            implicitTimestampMillis += DEFAULT_SAMPLE_INTERVAL_MILLIS
        }
        val previousTimestampMillis = lastTimestampMillis
        if (
            timestampMillis < 0 ||
            previousTimestampMillis?.let { timestampMillis <= it } == true
        ) {
            return null
        }
        lastTimestampMillis = timestampMillis

        if (!sample.isUsable(drivingState)) {
            resetSegment()
            return null
        }

        val impact = abs(sample.verticalLinearAccelerationG)
        val multiplier = vehicleClass.roadImpactThresholdMultiplier
        if (impact > tuning.maximumAccelerationG) {
            resetSegment()
            return null
        }
        if (impact <= tuning.impactReleaseThresholdG * multiplier) {
            impactArmed = true
        }
        if (impactArmed && impact >= tuning.impactThresholdG * multiplier) {
            impactArmed = false
            roughSinceMillis = null
            roughReported = false
            return RoadHazard.SUDDEN_IMPACT
        }
        if (!impactArmed) return null

        if (impact < tuning.roughThresholdG * multiplier) {
            roughSinceMillis = null
            roughReported = false
            return null
        }
        val continuous = previousTimestampMillis?.let {
            timestampMillis - it <= tuning.maximumSampleGapMillis
        } ?: true
        if (roughSinceMillis == null || !continuous) {
            roughSinceMillis = timestampMillis
            roughReported = false
        }
        if (
            !roughReported &&
            timestampMillis - checkNotNull(roughSinceMillis) >= tuning.roughConfirmationMillis
        ) {
            roughReported = true
            return RoadHazard.ROUGH_ROAD
        }
        return null
    }

    private fun RoadSample.isUsable(drivingState: DrivingState): Boolean =
        drivingState == DrivingState.DRIVING &&
            speedKph.isFinite() &&
            speedAccuracyKph.isFinite() &&
            locationAccuracyMetres.isFinite() &&
            verticalLinearAccelerationG.isFinite() &&
            speedKph in 0.0..MAX_SPEED_KPH &&
            speedAccuracyKph in 0.0..MAX_SPEED_ACCURACY_KPH &&
            speedKph - speedAccuracyKph >= tuning.minimumSpeedKph &&
            locationAccuracyMetres in 0.0..tuning.maximumLocationAccuracyMetres &&
            locationAgeMillis in 0..tuning.maximumLocationAgeMillis

    private fun resetSegment() {
        roughSinceMillis = null
        roughReported = false
        impactArmed = true
    }

    private companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 50L
        const val MAX_SPEED_KPH = 300.0
        const val MAX_SPEED_ACCURACY_KPH = 100.0
    }
}
