package io.github.priencelucifer.michisonae

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
}
