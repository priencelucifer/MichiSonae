package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendStatusTest {
    @Test
    fun offlineStatusKeepsPendingCountAndSavedDataVisible() {
        val status = backendStatus(
            pendingUploadCount = 3,
            hasNetwork = false,
            lastSyncFailureAtMillis = null,
            hasCachedSnapshot = true,
            snapshotGeneratedAtMillis = 1_000,
            nowMillis = 60_000,
        )

        assertEquals(SnapshotFreshness.CURRENT, status.snapshotFreshness)
        assertTrue(status.connectionLabel.startsWith("Offline"))
        assertTrue(status.uploadLabel.startsWith("3 road reports"))
    }

    @Test
    fun oldSnapshotIsClearlyMarkedStale() {
        val status = backendStatus(
            pendingUploadCount = 0,
            hasNetwork = true,
            lastSyncFailureAtMillis = 30 * 60 * 1_000L,
            hasCachedSnapshot = true,
            snapshotGeneratedAtMillis = 0,
            nowMillis = 31 * 60 * 1_000L,
        )

        assertEquals(SnapshotFreshness.STALE, status.snapshotFreshness)
        assertTrue(status.connectionLabel.startsWith("Sync delayed"))
        assertEquals("All road reports uploaded.", status.uploadLabel)
    }

    @Test
    fun missingSnapshotDoesNotClaimCoverage() {
        val status = backendStatus(
            pendingUploadCount = 0,
            hasNetwork = true,
            lastSyncFailureAtMillis = null,
            hasCachedSnapshot = false,
            snapshotGeneratedAtMillis = null,
            nowMillis = 1_000,
        )

        assertEquals(SnapshotFreshness.NOT_AVAILABLE, status.snapshotFreshness)
        assertEquals("No saved nearby hazard data yet.", status.snapshotLabel)
    }

    @Test
    fun unconfiguredBackendIsNotReportedAsConnected() {
        val status = backendStatus(
            pendingUploadCount = 2,
            hasNetwork = true,
            lastSyncFailureAtMillis = null,
            hasCachedSnapshot = false,
            snapshotGeneratedAtMillis = null,
            nowMillis = 1_000,
            backendConfigured = false,
        )

        assertEquals("Backend endpoint is not configured yet.", status.connectionLabel)
        assertTrue(status.uploadLabel.startsWith("2 road reports"))
    }
}
