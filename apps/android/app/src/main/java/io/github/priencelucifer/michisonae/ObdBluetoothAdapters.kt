package io.github.priencelucifer.michisonae

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

internal enum class ObdBluetoothAvailability {
    READY,
    PERMISSION_REQUIRED,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    NO_PAIRED_DEVICES,
}

internal data class BondedObdAdapter(
    val name: String,
    val address: String,
) {
    val displayName: String
        get() = "$name (${address.takeLast(5)})"
}

internal data class BondedObdAdapters(
    val availability: ObdBluetoothAvailability,
    val adapters: List<BondedObdAdapter> = emptyList(),
)

internal fun needsBluetoothConnectPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.S && !permissionGranted

internal fun bondedObdAdapters(
    permissionGranted: Boolean,
    bluetoothAvailable: Boolean,
    bluetoothEnabled: Boolean,
    devices: Iterable<BondedObdAdapter>,
): BondedObdAdapters {
    if (!permissionGranted) {
        return BondedObdAdapters(ObdBluetoothAvailability.PERMISSION_REQUIRED)
    }
    if (!bluetoothAvailable) {
        return BondedObdAdapters(ObdBluetoothAvailability.BLUETOOTH_UNAVAILABLE)
    }
    if (!bluetoothEnabled) {
        return BondedObdAdapters(ObdBluetoothAvailability.BLUETOOTH_DISABLED)
    }
    val paired = devices
        .mapNotNull { device ->
            val address = device.address.trim().uppercase(Locale.ROOT)
            if (!BLUETOOTH_ADDRESS.matches(address)) return@mapNotNull null
            BondedObdAdapter(
                name = device.name
                    .filterNot { it.isISOControl() }
                    .trim()
                    .ifBlank { "Paired Bluetooth adapter" }
                    .take(MAX_DEVICE_NAME_CHARS),
                address = address,
            )
        }
        .distinctBy(BondedObdAdapter::address)
        .sortedWith(compareBy(BondedObdAdapter::name, BondedObdAdapter::address))
    return BondedObdAdapters(
        availability = if (paired.isEmpty()) {
            ObdBluetoothAvailability.NO_PAIRED_DEVICES
        } else {
            ObdBluetoothAvailability.READY
        },
        adapters = paired,
    )
}

@SuppressLint("MissingPermission")
internal fun loadBondedObdAdapters(context: Context): BondedObdAdapters {
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    if (needsBluetoothConnectPermission(Build.VERSION.SDK_INT, permissionGranted)) {
        return BondedObdAdapters(ObdBluetoothAvailability.PERMISSION_REQUIRED)
    }
    val adapter = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()
        ?: return BondedObdAdapters(ObdBluetoothAvailability.BLUETOOTH_UNAVAILABLE)
    return try {
        val enabled = adapter.isEnabled
        bondedObdAdapters(
            permissionGranted = true,
            bluetoothAvailable = true,
            bluetoothEnabled = enabled,
            devices = if (enabled) {
                adapter.bondedDevices.map { device ->
                    BondedObdAdapter(device.name.orEmpty(), device.address)
                }
            } else {
                emptyList()
            },
        )
    } catch (_: SecurityException) {
        BondedObdAdapters(ObdBluetoothAvailability.PERMISSION_REQUIRED)
    } catch (_: IllegalStateException) {
        BondedObdAdapters(ObdBluetoothAvailability.BLUETOOTH_DISABLED)
    }
}

@SuppressLint("MissingPermission")
internal fun findBondedObdDevice(
    context: Context,
    address: String,
): BluetoothDevice? {
    if (!BLUETOOTH_ADDRESS.matches(address.uppercase(Locale.ROOT))) return null
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        adapter.bondedDevices.firstOrNull {
            it.address.equals(address, ignoreCase = true)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun simulatedBondedObdAdapters(): BondedObdAdapters = bondedObdAdapters(
    permissionGranted = true,
    bluetoothAvailable = true,
    bluetoothEnabled = true,
    devices = listOf(BondedObdAdapter("ELM327 simulator", "02:00:00:00:00:01")),
)

private const val MAX_DEVICE_NAME_CHARS = 60
private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
