package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingDetectionTest {
    @Test
    fun drivingRequiresConfirmedMovement() {
        val detector = AutomaticDrivingDetector()

        assertEquals(
            DrivingState.CHECKING,
            detector.update(MotionSample(timestampMillis = 0, speedKph = 20.0)),
        )
        assertEquals(
            DrivingState.CHECKING,
            detector.update(MotionSample(timestampMillis = 29_999, speedKph = 20.0)),
        )
        assertEquals(
            DrivingState.DRIVING,
            detector.update(MotionSample(timestampMillis = 30_000, speedKph = 20.0)),
        )
    }

    @Test
    fun aBriefStopDoesNotEndDriving() {
        val detector = AutomaticDrivingDetector()
        detector.update(MotionSample(timestampMillis = 0, speedKph = 20.0))
        detector.update(MotionSample(timestampMillis = 30_000, speedKph = 20.0))

        assertEquals(
            DrivingState.DRIVING,
            detector.update(MotionSample(timestampMillis = 31_000, speedKph = 0.0)),
        )
        assertEquals(
            DrivingState.DRIVING,
            detector.update(MotionSample(timestampMillis = 210_999, speedKph = 0.0)),
        )
        assertEquals(
            DrivingState.IDLE,
            detector.update(MotionSample(timestampMillis = 211_000, speedKph = 0.0)),
        )
    }

    @Test
    fun impactDetectionChangesWithVehicleSize() {
        val sample = RoadSample(
            speedKph = 30.0,
            verticalLinearAccelerationG = 0.6,
        )

        assertEquals(
            RoadHazard.SUDDEN_IMPACT,
            PhoneRoadHazardDetector().observe(
                sample,
                VehicleClass.COMPACT,
                DrivingState.DRIVING,
            ),
        )
        assertNull(
            PhoneRoadHazardDetector().observe(
                sample,
                VehicleClass.SUV,
                DrivingState.DRIVING,
            ),
        )
    }

    @Test
    fun phoneDetectionWorksWithoutObdOrHardware() {
        val detector = PhoneRoadHazardDetector()

        assertEquals(
            RoadHazard.ROUGH_ROAD,
            List(3) {
                detector.observe(
                    RoadSample(
                        speedKph = 25.0,
                        verticalLinearAccelerationG = 0.4,
                    ),
                    VehicleClass.STANDARD,
                    DrivingState.DRIVING,
                )
            }.last(),
        )
    }

    @Test
    fun roadSamplesAreIgnoredUntilDrivingIsConfirmed() {
        assertNull(
            PhoneRoadHazardDetector().observe(
                RoadSample(
                    speedKph = 30.0,
                    verticalLinearAccelerationG = 2.0,
                ),
                VehicleClass.COMPACT,
                DrivingState.CHECKING,
            ),
        )
    }

    @Test
    fun phoneOrientationIndependentAccelerationIsConvertedToG() {
        assertEquals(
            1.0,
            linearAccelerationMagnitudeG(9.80665f, 0f, 0f),
            0.0001,
        )
        assertEquals(
            1.0,
            linearAccelerationMagnitudeG(0f, 0f, 9.80665f),
            0.0001,
        )
    }
}
