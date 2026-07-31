package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BootRecoveryPolicyTest {
    @Test
    fun deletionOrMissingConsentDisablesEveryRecoveryPath() {
        for (endpointConfigured in listOf(false, true)) {
            for (monitoringShouldResume in listOf(false, true)) {
                assertEquals(
                    BootRecoveryPlan(false, false),
                    bootRecoveryPlan(
                        privacyAccepted = false,
                        endpointConfigured = endpointConfigured,
                        monitoringShouldResume = monitoringShouldResume,
                    ),
                )
            }
        }
    }

    @Test
    fun acceptedUserRecoversOnlyPreviouslyConfiguredWork() {
        assertEquals(
            BootRecoveryPlan(rescheduleUploads = true, promptMonitoringRestart = false),
            bootRecoveryPlan(true, endpointConfigured = true, monitoringShouldResume = false),
        )
        assertEquals(
            BootRecoveryPlan(rescheduleUploads = false, promptMonitoringRestart = true),
            bootRecoveryPlan(true, endpointConfigured = false, monitoringShouldResume = true),
        )
        assertFalse(bootRecoveryPlan(true, false, false).rescheduleUploads)
    }
}
