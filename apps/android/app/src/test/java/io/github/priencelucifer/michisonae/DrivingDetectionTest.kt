package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun uncertainSpeedNeverSilentlyStartsDriving() {
        val detector = AutomaticDrivingDetector()

        assertEquals(
            DrivingState.UNCERTAIN,
            detector.update(
                MotionSample(
                    timestampMillis = 0,
                    speedKph = 12.0,
                    speedAccuracyKph = 5.0,
                ),
            ),
        )
        assertEquals(
            DrivingState.CHECKING,
            detector.update(
                MotionSample(
                    timestampMillis = 1_000,
                    speedKph = 16.0,
                    speedAccuracyKph = 5.0,
                ),
            ),
        )
        assertEquals(
            DrivingState.DRIVING,
            detector.update(
                MotionSample(
                    timestampMillis = 31_000,
                    speedKph = 16.0,
                    speedAccuracyKph = 5.0,
                ),
            ),
        )
    }

    @Test
    fun reorderedOrInvalidMotionSamplesCannotAdvanceState() {
        val detector = AutomaticDrivingDetector()
        detector.update(MotionSample(timestampMillis = 10_000, speedKph = 20.0))

        assertEquals(
            DrivingState.CHECKING,
            detector.update(MotionSample(timestampMillis = 9_000, speedKph = 20.0)),
        )
        assertEquals(
            DrivingState.CHECKING,
            detector.update(MotionSample(timestampMillis = 50_000, speedKph = Double.NaN)),
        )
        assertEquals(
            DrivingState.DRIVING,
            detector.update(MotionSample(timestampMillis = 40_000, speedKph = 20.0)),
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
    fun roughRoadUsesElapsedTimeInsteadOfDeviceSampleCount() {
        fun replay(intervalMillis: Long): List<RoadHazard> {
            val detector = PhoneRoadHazardDetector()
            return (0L..300L step intervalMillis).mapNotNull { timestamp ->
                detector.observe(
                    RoadSample(
                        speedKph = 25.0,
                        verticalLinearAccelerationG = 0.4,
                        timestampMillis = timestamp,
                    ),
                    VehicleClass.STANDARD,
                    DrivingState.DRIVING,
                )
            }
        }

        assertEquals(listOf(RoadHazard.ROUGH_ROAD), replay(intervalMillis = 10))
        assertEquals(listOf(RoadHazard.ROUGH_ROAD), replay(intervalMillis = 50))
    }

    @Test
    fun aSampleGapDoesNotConfirmRoughRoad() {
        val detector = PhoneRoadHazardDetector()

        assertNull(
            detector.observe(
                RoadSample(25.0, 0.4, timestampMillis = 0),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
        assertNull(
            detector.observe(
                RoadSample(25.0, 0.4, timestampMillis = 1_000),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
    }

    @Test
    fun staleInaccurateOrSpeedUncertainLocationsAreIgnored() {
        val samples = listOf(
            RoadSample(
                speedKph = 30.0,
                verticalLinearAccelerationG = 1.0,
                locationAgeMillis = 5_001,
            ),
            RoadSample(
                speedKph = 30.0,
                verticalLinearAccelerationG = 1.0,
                locationAccuracyMetres = 60.01,
            ),
            RoadSample(
                speedKph = 12.0,
                speedAccuracyKph = 5.0,
                verticalLinearAccelerationG = 1.0,
            ),
        )

        samples.forEach { sample ->
            assertNull(
                PhoneRoadHazardDetector().observe(
                    sample,
                    VehicleClass.COMPACT,
                    DrivingState.DRIVING,
                ),
            )
        }
    }

    @Test
    fun impossibleSensorSpikeIsRejectedAndImpactNeedsAQuietRearm() {
        val detector = PhoneRoadHazardDetector()

        assertNull(
            detector.observe(
                RoadSample(30.0, 7.0),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
        assertEquals(
            RoadHazard.SUDDEN_IMPACT,
            detector.observe(
                RoadSample(30.0, 1.0),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
        assertNull(
            detector.observe(
                RoadSample(30.0, 7.0),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
        repeat(5) {
            assertNull(
                detector.observe(
                    RoadSample(30.0, 1.0),
                    VehicleClass.STANDARD,
                    DrivingState.DRIVING,
                ),
            )
        }
        detector.observe(
            RoadSample(30.0, 0.05),
            VehicleClass.STANDARD,
            DrivingState.DRIVING,
        )
        assertEquals(
            RoadHazard.SUDDEN_IMPACT,
            detector.observe(
                RoadSample(30.0, 1.0),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
    }

    @Test
    fun malformedSensorValueCannotPoisonTheMonotonicReplayClock() {
        val detector = PhoneRoadHazardDetector()

        assertNull(
            detector.observe(
                RoadSample(30.0, Double.NaN, timestampMillis = 10_000),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
        assertEquals(
            RoadHazard.SUDDEN_IMPACT,
            detector.observe(
                RoadSample(30.0, 1.0, timestampMillis = 1_000),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            ),
        )
    }

    @Test
    fun deterministicNoisyDriveProducesOnlyTheExpectedHazards() {
        val detector = PhoneRoadHazardDetector()
        val acceleration = listOf(
            0.02,
            -0.04,
            0.38,
            0.36,
            0.34,
            0.03,
            0.90,
            0.85,
            0.02,
            0.90,
        )

        val hazards = acceleration.mapIndexedNotNull { index, value ->
            detector.observe(
                RoadSample(
                    speedKph = 35.0,
                    verticalLinearAccelerationG = value,
                    timestampMillis = index * 50L,
                ),
                VehicleClass.STANDARD,
                DrivingState.DRIVING,
            )
        }

        assertEquals(
            listOf(
                RoadHazard.ROUGH_ROAD,
                RoadHazard.SUDDEN_IMPACT,
                RoadHazard.SUDDEN_IMPACT,
            ),
            hazards,
        )
    }

    @Test
    fun sensorFuzzNeverThrowsOrWarnsWithoutConfirmedDriving() {
        val random = kotlin.random.Random(0x50484F45)
        val unusual = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -1.0,
            0.0,
            0.1,
            1.0,
            301.0,
        )

        repeat(5_000) { index ->
            val sample = RoadSample(
                speedKph = unusual[random.nextInt(unusual.size)],
                speedAccuracyKph = unusual[random.nextInt(unusual.size)],
                verticalLinearAccelerationG = unusual[random.nextInt(unusual.size)],
                timestampMillis = index.toLong(),
                locationAccuracyMetres = unusual[random.nextInt(unusual.size)],
                locationAgeMillis = random.nextLong(-1, 5_002),
            )
            DrivingState.entries.filterNot { it == DrivingState.DRIVING }.forEach { state ->
                assertNull(
                    PhoneRoadHazardDetector().observe(
                        sample,
                        VehicleClass.STANDARD,
                        state,
                    ),
                )
            }
        }
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

    @Test
    fun invalidDetectorCalibrationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RoadDetectionTuning(
                impactThresholdG = 0.2,
                roughThresholdG = 0.3,
            )
        }
    }
}
