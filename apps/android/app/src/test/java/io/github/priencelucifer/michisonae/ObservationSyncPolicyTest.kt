package io.github.priencelucifer.michisonae

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
}
