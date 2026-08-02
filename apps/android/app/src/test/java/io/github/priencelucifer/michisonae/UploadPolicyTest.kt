package io.github.priencelucifer.michisonae

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadPolicyTest {
    @Test
    fun completeDurableAcceptanceCanAcknowledgeTheQueue() {
        assertEquals(
            UploadOutcome.ACCEPTED,
            classifyUpload(
                statusCode = 202,
                submittedCount = 3,
                schemaVersion = "1.0",
                receivedCount = 3,
                storedCount = 2,
                duplicateCount = 1,
            ),
        )
        assertEquals(
            setOf("event"),
            acknowledgedEventIds(UploadOutcome.ACCEPTED, setOf("event")),
        )
    }

    @Test
    fun malformedAcceptanceNeverAcknowledgesAndRetriesSafely() {
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(
                statusCode = 202,
                submittedCount = 3,
                schemaVersion = "1.0",
                receivedCount = 2,
                storedCount = 2,
                duplicateCount = 0,
            ),
        )
        assertEquals(
            emptySet<String>(),
            acknowledgedEventIds(UploadOutcome.RETRY, setOf("event")),
        )
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(
                202,
                3,
                "1.0",
                receivedCount = 3,
                storedCount = -1,
                duplicateCount = 4,
            ),
        )
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(
                202,
                3,
                "1.0",
                receivedCount = 3,
                storedCount = 4,
                duplicateCount = -1,
            ),
        )
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(202, 3, "1.0", receivedCount = 3, storedCount = 3),
        )
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(
                202,
                0,
                "1.0",
                receivedCount = 0,
                storedCount = 0,
                duplicateCount = 0,
            ),
        )
        assertEquals(
            UploadOutcome.RETRY,
            classifyUpload(
                202,
                1,
                "2.0",
                receivedCount = 1,
                storedCount = 1,
                duplicateCount = 0,
            ),
        )
    }

    @Test
    fun temporaryFailuresRemainQueuedForRetry() {
        assertEquals(UploadOutcome.RETRY, classifyUpload(408, 1))
        assertEquals(UploadOutcome.RETRY, classifyUpload(425, 1))
        assertEquals(UploadOutcome.RETRY, classifyUpload(429, 1))
        assertEquals(UploadOutcome.RETRY, classifyUpload(503, 1))
    }

    @Test
    fun recordSpecificRejectionsAreIsolatedWithoutStarvingLaterWork() {
        assertEquals(UploadOutcome.PERMANENT_RECORD_REJECTION, classifyUpload(409, 2))
        assertEquals(UploadOutcome.PERMANENT_RECORD_REJECTION, classifyUpload(422, 2))
        val first = draft("00000000-0000-0000-0000-000000000001")
        val second = draft("00000000-0000-0000-0000-000000000002")
        val attemptedSizes = mutableListOf<Int>()

        val acceptedFirst = resolvePendingUpload(listOf(first, second)) { attempted ->
            attemptedSizes += attempted.size
            if (attempted.size == 1) UploadOutcome.ACCEPTED else {
                UploadOutcome.PERMANENT_RECORD_REJECTION
            }
        }

        assertEquals(listOf(2, 1), attemptedSizes)
        assertEquals(UploadOutcome.ACCEPTED, acceptedFirst.outcome)
        assertEquals(setOf(first.eventId), acceptedFirst.acknowledgedEventIds)
        assertEquals(null, acceptedFirst.permanentlyRejectedEventId)

        val rejectedFirst = resolvePendingUpload(listOf(first, second)) {
            UploadOutcome.PERMANENT_RECORD_REJECTION
        }
        assertEquals(UploadOutcome.PERMANENT_RECORD_REJECTION, rejectedFirst.outcome)
        assertEquals(emptySet<String>(), rejectedFirst.acknowledgedEventIds)
        assertEquals(first.eventId, rejectedFirst.permanentlyRejectedEventId)
    }

    @Test
    fun brokenTruncatedAndOversizedAcceptanceBodiesRetryWithoutAcknowledgement() {
        val failures = listOf<() -> java.io.InputStream>(
            { throw IOException("disconnected") },
            { ByteArrayInputStream("{\"schema_version\":\"1.0\"".toByteArray()) },
            { ByteArrayInputStream("x".repeat(16 * 1_024 + 1).toByteArray()) },
        )

        failures.forEach { responseBody ->
            val outcome = classifyUploadResponse(202, 1, responseBody)
            assertEquals(UploadOutcome.RETRY, outcome)
            assertEquals(emptySet<String>(), acknowledgedEventIds(outcome, setOf("event")))
        }
    }

    @Test
    fun expiredAccessTokenRequestsSingleFlightRefresh() {
        assertEquals(UploadOutcome.AUTH_EXPIRED, classifyUpload(401, 1))
    }

    @Test
    fun backendUrlMustBeACleanOrigin() {
        assertEquals("https://api.example.com", validatedApiBaseUrl("HTTPS://API.Example.com/"))
        assertEquals("http://localhost:8080", validatedApiBaseUrl("http://localhost:8080"))

        listOf(
            "http://api.example.com",
            "https://user@example.com",
            "https://api.example.com/v1",
            "https://api.example.com?debug=true",
            "https://api.example.com#fragment",
            "https://api.example.com:0",
            "https://",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                validatedApiBaseUrl(invalid)
            }
        }
    }

    private fun draft(eventId: String): RoadObservationDraft = RoadObservationDraft(
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
