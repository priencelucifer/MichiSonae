package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRoadHazardReportingTest {
    @Test
    fun stoppedUserCanPrepareAContractCompatibleReport() {
        val decision = prepare()

        assertNotNull(decision.draft)
        assertNull(decision.blockedReason)
        assertEquals(ObservationKind.ROAD_DAMAGE, decision.draft?.kind)
        assertEquals(0.6, decision.draft?.severity ?: 0.0, 0.0)
        assertEquals("manual-v1", decision.draft?.detectorVersion)
    }

    @Test
    fun everyNonIdleDrivingStateBlocksManualReporting() {
        DrivingState.entries.filterNot { it == DrivingState.IDLE }.forEach { state ->
            assertEquals(
                ManualReportBlockReason.VEHICLE_MAY_BE_MOVING,
                prepare(drivingState = state).blockedReason,
            )
        }
    }

    @Test
    fun measuredMovementBlocksEvenWhenDriveStateIsIdle() {
        assertEquals(
            ManualReportBlockReason.VEHICLE_MAY_BE_MOVING,
            prepare(speedMetresPerSecond = 1.01).blockedReason,
        )
    }

    @Test
    fun inaccurateLocationDoesNotCreateAnObservation() {
        val decision = prepare(locationAccuracyMetres = 60.01)

        assertNull(decision.draft)
        assertEquals(
            ManualReportBlockReason.LOCATION_TOO_INACCURATE,
            decision.blockedReason,
        )
    }

    @Test
    fun everyManualTaxonomyCategoryMapsToItsContractKind() {
        ManualHazardCategory.entries.forEach { category ->
            val decision = prepare(category = category)

            assertEquals(category.observationKind, decision.draft?.kind)
            assertNull(decision.blockedReason)
        }
    }

    @Test
    fun worseLocationAccuracyLowersButBoundsManualConfidence() {
        val accurate = prepare(locationAccuracyMetres = 1.0).draft
        val acceptedLimit = prepare(locationAccuracyMetres = 60.0).draft

        assertNotNull(accurate)
        assertNotNull(acceptedLimit)
        checkNotNull(accurate)
        checkNotNull(acceptedLimit)
        assertTrue(accurate.confidence > acceptedLimit.confidence)
        assertTrue(acceptedLimit.confidence >= 0.55)
    }

    private fun prepare(
        category: ManualHazardCategory = ManualHazardCategory.ROAD_DAMAGE,
        drivingState: DrivingState = DrivingState.IDLE,
        locationAccuracyMetres: Double? = 10.0,
        speedMetresPerSecond: Double? = 0.0,
    ): ManualHazardReportDecision = ManualRoadHazardReportPolicy.prepare(
        category = category,
        severity = ManualHazardSeverity.MEDIUM,
        drivingState = drivingState,
        detectedAtMillis = 1_700_000_000_000,
        latitude = 26.1445,
        longitude = 91.7362,
        locationAccuracyMetres = locationAccuracyMetres,
        speedMetresPerSecond = speedMetresPerSecond,
    )
}
