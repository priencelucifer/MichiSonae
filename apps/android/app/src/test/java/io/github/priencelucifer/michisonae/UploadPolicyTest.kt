package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
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
}
