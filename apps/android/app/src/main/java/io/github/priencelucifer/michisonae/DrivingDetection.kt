package io.github.priencelucifer.michisonae

import kotlin.math.abs

internal data class MotionSample(
    val timestampMillis: Long,
    val speedKph: Double,
)

internal enum class DrivingState(val displayName: String) {
    IDLE("Not driving"),
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

    fun update(sample: MotionSample): DrivingState {
        when (state) {
            DrivingState.IDLE -> {
                if (sample.speedKph >= startSpeedKph) {
                    candidateSince = sample.timestampMillis
                    state = DrivingState.CHECKING
                }
            }

            DrivingState.CHECKING -> {
                if (sample.speedKph < startSpeedKph) {
                    candidateSince = null
                    state = DrivingState.IDLE
                } else if (sample.timestampMillis - checkNotNull(candidateSince) >=
                    startConfirmationMillis
                ) {
                    candidateSince = null
                    state = DrivingState.DRIVING
                }
            }

            DrivingState.DRIVING -> {
                if (sample.speedKph <= stopSpeedKph) {
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
}

internal data class RoadSample(
    val speedKph: Double,
    val verticalLinearAccelerationG: Double,
)

internal enum class RoadHazard(val userMessage: String) {
    SUDDEN_IMPACT("Road hazard ahead. Slow down safely."),
    ROUGH_ROAD("Rough road detected. Reduce speed safely."),
}

internal data class RoadDetectionTuning(
    val minimumSpeedKph: Double = 8.0,
    val impactThresholdG: Double = 0.65,
    val roughThresholdG: Double = 0.25,
    val roughSampleCount: Int = 3,
)

internal class PhoneRoadHazardDetector(
    private val tuning: RoadDetectionTuning = RoadDetectionTuning(),
) {
    private var consecutiveRoughSamples = 0

    fun observe(
        sample: RoadSample,
        vehicleClass: VehicleClass,
        drivingState: DrivingState,
    ): RoadHazard? {
        if (drivingState != DrivingState.DRIVING || sample.speedKph < tuning.minimumSpeedKph) {
            consecutiveRoughSamples = 0
            return null
        }

        val impact = abs(sample.verticalLinearAccelerationG)
        val multiplier = vehicleClass.roadImpactThresholdMultiplier
        if (impact >= tuning.impactThresholdG * multiplier) {
            consecutiveRoughSamples = 0
            return RoadHazard.SUDDEN_IMPACT
        }

        consecutiveRoughSamples = if (impact >= tuning.roughThresholdG * multiplier) {
            consecutiveRoughSamples + 1
        } else {
            0
        }
        if (consecutiveRoughSamples >= tuning.roughSampleCount) {
            consecutiveRoughSamples = 0
            return RoadHazard.ROUGH_ROAD
        }
        return null
    }
}
