package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityTest {
    @Test
    fun degradedCapabilityUsesHonestLabel() {
        assertEquals("degraded", Capability.DEGRADED.displayName)
    }
}
