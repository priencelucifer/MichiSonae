package io.github.priencelucifer.michisonae

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLifecycleGateTest {
    @Test
    fun monitoringCannotRestartDuringOrAfterSuccessfulDeletion() {
        assertFalse(monitoringStartAllowed(true, true, true, true))
        assertFalse(monitoringStartAllowed(false, false, false, true))
        assertTrue(monitoringStartAllowed(false, true, true, true))
    }
}
