package io.github.priencelucifer.michisonae

import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun currentQueueRoundTripsAFullDeterministicBatch() {
        val observations = List(100) { index ->
            draft("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        }

        val decoded = decodeObservationQueue(encodeObservationQueue(observations))

        assertEquals(observations, decoded.observations)
        assertEquals(false, decoded.needsRewrite)
    }

    @Test
    fun enqueueIsIdempotentAndRejectsConflictingEventIdReuse() {
        val original = draft("00000000-0000-0000-0000-000000000001")

        assertEquals(listOf(original), appendUniqueObservation(listOf(original), original))
        assertEquals(
            null,
            appendUniqueObservation(
                listOf(original),
                original.copy(severity = 0.1),
            ),
        )
    }

    @Test
    fun acknowledgingFirstHundredCannotDeleteUnsubmittedDuplicateId() {
        val submitted = List(100) { index ->
            draft("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        }
        val unsubmittedConflict = submitted.first().copy(severity = 0.1)

        val remaining = removeFirstMatchingObservations(
            submitted + unsubmittedConflict,
            submitted.mapTo(mutableSetOf()) { it.eventId },
        )

        assertEquals(listOf(unsubmittedConflict), remaining)
    }

    @Test
    fun aTruncatedTailKeepsEveryPreviouslyDurableRecord() {
        val first = draft("00000000-0000-0000-0000-000000000001")
        val second = draft("00000000-0000-0000-0000-000000000002")
        val truncated = encodeObservationQueue(listOf(first, second)).dropLast(20)

        val decoded = decodeObservationQueue(truncated)

        assertEquals(listOf(first), decoded.observations)
        assertEquals(true, decoded.needsRewrite)
    }

    @Test
    fun legacyArrayScannerHandlesEscapedQuotesAndBraces() {
        val observation = draft("00000000-0000-0000-0000-000000000001").copy(
            detectorVersion = "test-{brace}-\"quoted\"",
        )
        val decoded = decodeObservationQueue("[${observation.toStoredJson()}]")

        assertEquals(listOf(observation), decoded.observations)
        assertEquals(true, decoded.needsRewrite)
    }

    @Test
    fun deterministicCorruptionFuzzAlwaysSalvagesTheFollowingValidRecord() {
        val random = Random(0x51554555)
        val expected = draft("00000000-0000-0000-0000-000000000099")
        val alphabet = "{}[],:\\\" garbage 0123456789"

        repeat(1_000) {
            val garbage = "!" + buildString {
                repeat(random.nextInt(0, 256)) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }.replace('\n', ' ')
            val decoded = decodeObservationQueue(
                """
                    {"schema_version":2}
                    $garbage
                    ${expected.toStoredJson()}
                """.trimIndent(),
            )

            assertTrue(expected in decoded.observations)
            assertEquals(true, decoded.needsRewrite)
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
