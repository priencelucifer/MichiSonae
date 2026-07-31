package io.github.priencelucifer.michisonae

import android.app.job.JobInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncPolicyTest {
    @Test
    fun defaultDefersOnLowBatteryButAllowsSmallMeteredUploads() {
        val policy = BackgroundSyncPolicy()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, requiredNetworkType(policy))
        assertTrue(policy.requireBatteryNotLow)
        assertFalse(policy.unmeteredOnly)
    }

    @Test
    fun userCanRequireUnmeteredNetwork() {
        val policy = BackgroundSyncPolicy(
            unmeteredOnly = true,
            requireBatteryNotLow = false,
        )
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, requiredNetworkType(policy))
        assertFalse(policy.requireBatteryNotLow)
    }

    @Test
    fun snapshotRefreshUsesTheSameMeteredAndBatteryPolicyAsUploads() {
        val cautious = BackgroundSyncPolicy(
            unmeteredOnly = true,
            requireBatteryNotLow = true,
        )

        assertFalse(backgroundDownloadAllowed(cautious, true, false))
        assertFalse(backgroundDownloadAllowed(cautious, false, true))
        assertTrue(backgroundDownloadAllowed(cautious, false, false))
        assertTrue(
            backgroundDownloadAllowed(
                BackgroundSyncPolicy(false, false),
                networkMetered = true,
                batteryLow = true,
            ),
        )
    }
}
