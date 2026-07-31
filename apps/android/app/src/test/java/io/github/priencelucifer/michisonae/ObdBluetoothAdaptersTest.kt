package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdBluetoothAdaptersTest {
    @Test
    fun android12RequiresBluetoothConnectPermission() {
        assertFalse(needsBluetoothConnectPermission(30, permissionGranted = false))
        assertTrue(needsBluetoothConnectPermission(31, permissionGranted = false))
        assertFalse(needsBluetoothConnectPermission(31, permissionGranted = true))
    }

    @Test
    fun permissionAndBluetoothFailuresAreDistinct() {
        assertEquals(
            ObdBluetoothAvailability.PERMISSION_REQUIRED,
            bondedObdAdapters(false, true, true, emptyList()).availability,
        )
        assertEquals(
            ObdBluetoothAvailability.BLUETOOTH_UNAVAILABLE,
            bondedObdAdapters(true, false, false, emptyList()).availability,
        )
        assertEquals(
            ObdBluetoothAvailability.BLUETOOTH_DISABLED,
            bondedObdAdapters(true, true, false, emptyList()).availability,
        )
        assertEquals(
            ObdBluetoothAvailability.NO_PAIRED_DEVICES,
            bondedObdAdapters(true, true, true, emptyList()).availability,
        )
    }

    @Test
    fun deviceOptionsAreSanitizedDeduplicatedAndSimulatable() {
        val snapshot = bondedObdAdapters(
            permissionGranted = true,
            bluetoothAvailable = true,
            bluetoothEnabled = true,
            devices = listOf(
                BondedObdAdapter("  ELM\u0000 327  ", "aa:bb:cc:dd:ee:ff"),
                BondedObdAdapter("duplicate", "AA:BB:CC:DD:EE:FF"),
                BondedObdAdapter("", "02:00:00:00:00:02"),
                BondedObdAdapter("invalid", "not-an-address"),
            ),
        )

        assertEquals(ObdBluetoothAvailability.READY, snapshot.availability)
        assertEquals(2, snapshot.adapters.size)
        assertEquals(
            setOf("ELM 327", "Paired Bluetooth adapter"),
            snapshot.adapters.map(BondedObdAdapter::name).toSet(),
        )
        assertEquals(
            ObdBluetoothAvailability.READY,
            simulatedBondedObdAdapters().availability,
        )
    }
}
