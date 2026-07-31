package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationSyncPolicyTest {
    @Test
    fun temporaryAndAuthFailuresRetry() {
        assertTrue(shouldRetrySync(UploadOutcome.RETRY, false))
        assertTrue(shouldRetrySync(UploadOutcome.AUTH_EXPIRED, false))
    }

    @Test
    fun acceptedBatchRetriesOnlyWhenMoreWorkRemains() {
        assertTrue(shouldRetrySync(UploadOutcome.ACCEPTED, true))
        assertFalse(shouldRetrySync(UploadOutcome.ACCEPTED, false))
    }

    @Test
    fun emptyAndPermanentlyRejectedWorkDoNotLoop() {
        assertFalse(shouldRetrySync(null, false))
        assertFalse(shouldRetrySync(UploadOutcome.REJECTED, true))
    }

    @Test
    fun latestResultKeepsRealFailureState() {
        assertEquals(LatestSyncStatus.SUCCEEDED, latestSyncStatus(UploadOutcome.ACCEPTED))
        assertEquals(LatestSyncStatus.RETRYING, latestSyncStatus(UploadOutcome.RETRY))
        assertEquals(LatestSyncStatus.REJECTED, latestSyncStatus(UploadOutcome.REJECTED))
    }

    @Test
    fun deletionGateBlocksNewSyncScheduling() {
        assertFalse(syncScheduleAllowed(deletionInProgress = true))
        assertTrue(syncScheduleAllowed(deletionInProgress = false))
    }
}
