package io.github.priencelucifer.michisonae

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineObservationQueueTest {
    @Test
    fun malformedLegacyRecordDoesNotHideValidNeighbours() {
        val first = draft("00000000-0000-0000-0000-000000000001")
        val second = draft("00000000-0000-0000-0000-000000000002")
        val serialized = """[{"record":"first"},{broken},{"record":"second"}]"""

        val decoded = decodeObservationQueue(serialized) { record ->
            when {
                "\"first\"" in record -> first
                "\"second\"" in record -> second
                else -> null
            }
        }

        assertEquals(listOf(first, second), decoded.observations)
        assertEquals(true, decoded.needsRewrite)
    }

    @Test
    fun malformedVersionedRecordIsDroppedWithoutDroppingLaterRecords() {
        val first = draft("00000000-0000-0000-0000-000000000001")
        val second = draft("00000000-0000-0000-0000-000000000002")
        val serialized = """
            {"schema_version":2}
            {"record":"first"}
            broken
            {"record":"second"}
        """.trimIndent()

        val decoded = decodeObservationQueue(serialized) { record ->
            when {
                "\"first\"" in record -> first
                "\"second\"" in record -> second
                else -> null
            }
        }

        assertEquals(listOf(first, second), decoded.observations)
        assertEquals(true, decoded.needsRewrite)
    }

    @Test
    fun cleanCurrentSchemaDoesNotNeedMigration() {
        val observation = draft("00000000-0000-0000-0000-000000000001")
        val decoded = decodeObservationQueue(
            """
                {"schema_version":2}
                {"record":"first"}
            """.trimIndent(),
        ) { observation }

        assertEquals(listOf(observation), decoded.observations)
        assertEquals(false, decoded.needsRewrite)
    }

    @Test
    fun unknownFutureSchemaIsNotDowngraded() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeObservationQueue("""{"schema_version":3}""") { null }
        }
    }

    private fun draft(eventId: String): RoadObservationDraft {
        UUID.fromString(eventId)
        return RoadObservationDraft(
            eventId = eventId,
            detectedAtMillis = 1_000,
            latitude = 26.1445,
            longitude = 91.7362,
            locationAccuracyMetres = 4.0,
            speedMetresPerSecond = 5.0,
            kind = ObservationKind.ROAD_DAMAGE,
            severity = 0.7,
            confidence = 0.8,
            detectorVersion = "test",
        )
    }
}
