package io.github.priencelucifer.michisonae

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

internal enum class DeviceCheckState(val displayName: String) {
    READY("Ready"),
    ATTENTION("Needs attention"),
    OPTIONAL("Optional"),
    FAILED("Failed"),
}

internal data class DeviceCheck(
    val name: String,
    val state: DeviceCheckState,
    val detail: String,
)

internal enum class MonitoringState {
    NEVER_STARTED,
    STARTING,
    ACTIVE,
    DEGRADED,
    STOPPED,
}

internal data class MonitoringStatus(
    val state: MonitoringState,
    val detail: String,
)

internal object DataLifecycleGate {
    @Volatile
    private var deletionInProgress: Boolean = false

    fun beginDeletion(context: Context) {
        check(
            deletionPreferences(context).edit()
                .putBoolean(DELETION_PENDING, true)
                .commit(),
        ) {
            "Deletion could not be marked pending"
        }
        deletionInProgress = true
    }

    fun isDeletionInProgress(context: Context): Boolean =
        deletionInProgress ||
            deletionPreferences(context).getBoolean(DELETION_PENDING, false)

    fun endDeletion(context: Context) {
        check(
            deletionPreferences(context).edit()
                .putBoolean(DELETION_PENDING, false)
                .commit(),
        ) {
            "Deletion completion could not be stored"
        }
        deletionInProgress = false
    }

    private fun deletionPreferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            DELETION_PREFERENCES,
            Context.MODE_PRIVATE,
        )

    private const val DELETION_PREFERENCES = "michisonae-deletion-state"
    private const val DELETION_PENDING = "deletion_pending"
}

internal fun monitoringStartAllowed(
    deletionInProgress: Boolean,
    privacyAccepted: Boolean,
    hasVehicleProfile: Boolean,
    hasLocationPermission: Boolean,
): Boolean =
    !deletionInProgress && privacyAccepted && hasVehicleProfile && hasLocationPermission

internal class MonitoringStatusStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): MonitoringStatus {
        val state = runCatching {
            MonitoringState.valueOf(
                preferences.getString(STATE, null) ?: MonitoringState.NEVER_STARTED.name,
            )
        }.getOrDefault(MonitoringState.NEVER_STARTED)
        return MonitoringStatus(
            state = state,
            detail = preferences.getString(DETAIL, null) ?: "Monitoring has not started yet.",
        )
    }

    fun record(state: MonitoringState, detail: String) {
        require(detail.isNotBlank())
        check(
            preferences.edit()
                .putString(STATE, state.name)
                .putString(DETAIL, detail.take(MAX_DETAIL_LENGTH))
                .commit(),
        ) {
            "Monitoring status could not be stored"
        }
        StatusChangeNotifier.notify(appContext)
    }

    fun clear() {
        check(preferences.edit().clear().commit()) {
            "Monitoring status could not be cleared"
        }
        StatusChangeNotifier.notify(appContext)
    }

    private companion object {
        const val PREFERENCES = "michisonae-monitoring-status"
        const val STATE = "state"
        const val DETAIL = "detail"
        const val MAX_DETAIL_LENGTH = 160
    }
}

internal object StatusChangeNotifier {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("UNUSED_PARAMETER")
    fun notify(context: Context) {
        val dispatch = {
            listeners.forEach { listener -> runCatching(listener) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
        } else {
            mainHandler.post(dispatch)
        }
    }

    fun register(listener: () -> Unit) = listeners.add(listener)

    fun unregister(listener: () -> Unit) = listeners.remove(listener)
}

internal fun deviceChecks(context: Context, storageCheck: DeviceCheck? = null): List<DeviceCheck> {
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val locationManager = context.getSystemService(LocationManager::class.java)
    val hasLocationPermission =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val hasLocationProvider = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).any { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    val hasBluetooth = context.packageManager.hasSystemFeature(
        PackageManager.FEATURE_BLUETOOTH,
    )
    val hasBluetoothPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    val bluetoothEnabled = if (hasBluetooth && hasBluetoothPermission) {
        runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        }.getOrDefault(false)
    } else {
        false
    }
    val hasNetwork = context.getSystemService(ConnectivityManager::class.java)
        .activeNetwork
        ?.let { network ->
            context.getSystemService(ConnectivityManager::class.java)
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
    val hasVibrator = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator()
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java).hasVibrator()
        }
    }.getOrDefault(false)

    return buildList {
        add(
            DeviceCheck(
                name = "Motion sensor",
                state = if (
                    sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
                ) {
                    DeviceCheckState.READY
                } else {
                    DeviceCheckState.FAILED
                },
                detail = if (
                    sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
                ) {
                    "Linear acceleration sensor is available for phone road detection."
                } else {
                    "This phone has no linear acceleration sensor; phone road detection cannot run."
                },
            ),
        )
        add(
            DeviceCheck(
                name = "Location",
                state = when {
                    !hasLocationPermission -> DeviceCheckState.ATTENTION
                    !hasLocationProvider -> DeviceCheckState.ATTENTION
                    else -> DeviceCheckState.READY
                },
                detail = when {
                    !hasLocationPermission -> "Location permission is missing."
                    !hasLocationProvider -> "Turn on a location provider before driving."
                    else -> "Location permission and a provider are available."
                },
            ),
        )
        add(
            DeviceCheck(
                name = "Internet",
                state = if (hasNetwork) DeviceCheckState.READY else DeviceCheckState.OPTIONAL,
                detail = if (hasNetwork) {
                    "Background uploads and fresh public warnings can use the network."
                } else {
                    "Offline mode is active; reports remain queued and saved warnings still work."
                },
            ),
        )
        add(
            DeviceCheck(
                name = "Bluetooth OBD-II",
                state = when {
                    !hasBluetooth -> DeviceCheckState.OPTIONAL
                    !hasBluetoothPermission -> DeviceCheckState.ATTENTION
                    bluetoothEnabled -> DeviceCheckState.READY
                    else -> DeviceCheckState.ATTENTION
                },
                detail = when {
                    !hasBluetooth -> "Bluetooth is unavailable; OBD-II remains optional."
                    !hasBluetoothPermission -> "Bluetooth permission is needed only for OBD-II."
                    bluetoothEnabled -> "Bluetooth is on. Select an already-paired ELM327 adapter."
                    else -> "Bluetooth is off. Phone-only road detection still works."
                },
            ),
        )
        add(
            DeviceCheck(
                name = "Vibration",
                state = if (hasVibrator) DeviceCheckState.READY else DeviceCheckState.OPTIONAL,
                detail = if (hasVibrator) {
                    "Vibration is available for combined warnings."
                } else {
                    "No vibrator was detected; sound and English voice remain available."
                },
            ),
        )
        storageCheck?.let(::add)
    }
}

internal fun runStorageSelfTest(context: Context): DeviceCheck {
    val target = File(context.cacheDir, SELF_TEST_FILE)
    val result = runCatching {
        target.writeText(SELF_TEST_VALUE, Charsets.UTF_8)
        check(target.readText(Charsets.UTF_8) == SELF_TEST_VALUE)
    }
    runCatching { target.delete() }
    return if (result.isSuccess) {
        DeviceCheck(
            "Local storage",
            DeviceCheckState.READY,
            "A temporary private file was written, read, and removed successfully.",
        )
    } else {
        DeviceCheck(
            "Local storage",
            DeviceCheckState.FAILED,
            "Private storage failed its local write/read test. Reports may not be saved.",
        )
    }
}

private const val SELF_TEST_FILE = ".michisonae-storage-self-test"
private const val SELF_TEST_VALUE = "michisonae-ok"
