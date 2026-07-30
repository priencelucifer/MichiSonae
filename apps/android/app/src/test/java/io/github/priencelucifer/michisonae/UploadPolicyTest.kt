package io.github.priencelucifer.michisonae

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
    fun malformedAcceptanceNeverAcknowledgesTheQueue() {
        assertEquals(
            UploadOutcome.REJECTED,
            classifyUpload(
                statusCode = 202,
                submittedCount = 3,
                receivedCount = 2,
                storedCount = 2,
                duplicateCount = 0,
            ),
        )
        assertEquals(
            emptySet<String>(),
            acknowledgedEventIds(UploadOutcome.REJECTED, setOf("event")),
        )
        assertEquals(
            UploadOutcome.REJECTED,
            classifyUpload(202, 3, receivedCount = 3, storedCount = -1, duplicateCount = 4),
        )
        assertEquals(
            UploadOutcome.REJECTED,
            classifyUpload(202, 3, receivedCount = 3, storedCount = 4, duplicateCount = -1),
        )
        assertEquals(
            UploadOutcome.REJECTED,
            classifyUpload(202, 3, receivedCount = 3, storedCount = 3),
        )
        assertEquals(
            UploadOutcome.REJECTED,
            classifyUpload(202, 0, receivedCount = 0, storedCount = 0, duplicateCount = 0),
        )
    }

    @Test
    fun temporaryFailuresRemainQueuedForRetry() {
        assertEquals(UploadOutcome.RETRY, classifyUpload(408, 1))
        assertEquals(UploadOutcome.RETRY, classifyUpload(429, 1))
        assertEquals(UploadOutcome.RETRY, classifyUpload(503, 1))
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
}
