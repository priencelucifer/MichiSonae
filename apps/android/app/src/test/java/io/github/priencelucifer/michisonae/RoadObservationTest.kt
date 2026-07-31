package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoadObservationTest {
    @Test
    fun invalidLocationIsRejectedBeforeItCanReachTheQueue() {
        assertThrows(IllegalArgumentException::class.java) {
            RoadObservationDraft(
                detectedAtMillis = 0,
                latitude = 91.0,
                longitude = 0.0,
                locationAccuracyMetres = 5.0,
                speedMetresPerSecond = 10.0,
                kind = ObservationKind.ROAD_DAMAGE,
                severity = 0.5,
                confidence = 0.8,
                detectorVersion = "phone-v1",
            )
        }
    }

    @Test
    fun everyContractHazardKindRoundTripsThroughLocalStorage() {
        ObservationKind.entries.forEach { kind ->
            RoadObservationDraft(
                detectedAtMillis = 1_700_000_000_000,
                latitude = 26.1445,
                longitude = 91.7362,
                locationAccuracyMetres = 8.0,
                speedMetresPerSecond = 0.0,
                kind = kind,
                severity = 0.6,
                confidence = 0.7,
                detectorVersion = "manual-v1",
            )

            assertEquals(kind, ObservationKind.fromContractName(kind.contractName))
        }
    }
}
