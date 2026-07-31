package io.github.priencelucifer.michisonae

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors

class DrivingMonitorService : Service(), SensorEventListener, LocationListener {
    private val drivingDetector = AutomaticDrivingDetector()
    private val roadDetector = PhoneRoadHazardDetector()
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val publicHazardWarningGate = PublicHazardWarningGate()
    private val snapshotRefreshGate = RegionalSnapshotRefreshGate()
    private lateinit var warningPlayer: DriverWarningPlayer
    private lateinit var observationQueue: OfflineObservationQueue
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var vehicleClass: VehicleClass
    private var nearbyHazards: NearbyHazardSnapshots? = null
    @Volatile
    private var cachedHazards: RegionalHazardSnapshot? = null
    private var drivingState = DrivingState.IDLE
    private var lastLocation: Location? = null
    private var lastLocationAtMillis = Long.MIN_VALUE
    private var lastWarningAt = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val monitoringStatus = MonitoringStatusStore(this)

        val preferences = AppPreferences(this)
        val profile = preferences.loadVehicleProfile()
        val locationPermission = hasLocationPermission()
        val privacyAccepted = preferences.hasAcceptedPrivacy()
        val deletionInProgress = DataLifecycleGate.isDeletionInProgress(this)
        if (privacyAccepted && !deletionInProgress) {
            runCatching {
                monitoringStatus.record(
                    MonitoringState.STARTING,
                    "Starting phone road detection.",
                )
            }
        }
        if (
            !monitoringStartAllowed(
                deletionInProgress = deletionInProgress,
                privacyAccepted = privacyAccepted,
                hasVehicleProfile = profile != null,
                hasLocationPermission = locationPermission,
            )
        ) {
            if (privacyAccepted && !deletionInProgress) {
                runCatching {
                    monitoringStatus.record(
                        MonitoringState.DEGRADED,
                        if (profile == null) {
                            "A vehicle profile is required before monitoring can start."
                        } else {
                            "Location permission is missing; monitoring could not start."
                        },
                    )
                }
            }
            stopSelf()
            return
        }
        vehicleClass = checkNotNull(profile).vehicleClass
        warningPlayer = DriverWarningPlayer(this)
        OfflineObservationQueue.resumeAcceptingObservations()
        HazardSnapshotCache.resumeAcceptingSnapshots()
        observationQueue = OfflineObservationQueue(this)
        cachedHazards = HazardSnapshotCache(this).read()
        BuildConfig.MICHI_API_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
            nearbyHazards = NearbyHazardSnapshots(this, baseUrl)
            storageExecutor.execute {
                val pendingCount = runCatching { observationQueue.pendingCount() }
                    .onFailure {
                        runCatching {
                            MonitoringStatusStore(this).record(
                                MonitoringState.DEGRADED,
                                "Queued reports need local storage recovery.",
                            )
                        }
                    }
                    .getOrNull()
                if (pendingCount != null && pendingCount > 0) {
                    runCatching { ObservationSyncScheduler.schedule(this, baseUrl) }
                }
            }
        }
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        val capabilities = startMonitoring()
        runCatching {
            monitoringStatus.record(
                if (capabilities.sensorActive && capabilities.locationActive) {
                    MonitoringState.ACTIVE
                } else {
                    MonitoringState.DEGRADED
                },
                when {
                    !capabilities.sensorActive ->
                        "The linear acceleration sensor is unavailable; road detection is paused."
                    !capabilities.locationActive ->
                        "No location provider is active; road detection is waiting for location."
                    else -> "Phone road detection is active."
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        lastLocationAtMillis = SystemClock.elapsedRealtime()
        drivingState = drivingDetector.update(
            MotionSample(
                timestampMillis = lastLocationAtMillis,
                speedKph = location.speed.coerceAtLeast(0f) * 3.6,
                speedAccuracyKph = location.speedAccuracyKph(),
            ),
        )
        if (drivingState == DrivingState.DRIVING) {
            refreshNearbyHazards(location)
            warnForCachedHazard(location)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION || event.values.size < 3) return
        val location = lastLocation ?: return
        val now = SystemClock.elapsedRealtime()
        val hazard = roadDetector.observe(
            RoadSample(
                speedKph = location.speed.coerceAtLeast(0f) * 3.6,
                verticalLinearAccelerationG = linearAccelerationMagnitudeG(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                ),
                timestampMillis = event.timestamp / NANOS_PER_MILLISECOND,
                speedAccuracyKph = location.speedAccuracyKph(),
                locationAccuracyMetres = location.accuracy
                    .toDouble()
                    .takeIf { location.hasAccuracy() }
                    ?: Double.POSITIVE_INFINITY,
                locationAgeMillis = if (lastLocationAtMillis == Long.MIN_VALUE) {
                    Long.MAX_VALUE
                } else {
                    (now - lastLocationAtMillis).coerceAtLeast(0)
                },
            ),
            vehicleClass,
            drivingState,
        ) ?: return
        if (
            lastWarningAt != Long.MIN_VALUE &&
            now - lastWarningAt < WARNING_COOLDOWN_MILLIS
        ) {
            return
        }
        lastWarningAt = now

        val observation = hazard.toObservation(location)
        storageExecutor.execute {
            val stored = runCatching { observationQueue.enqueue(observation) }
                .getOrDefault(false)
            if (stored) {
                mainExecutor.execute { warningPlayer.warn(hazard.userMessage) }
                BuildConfig.MICHI_API_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    runCatching { ObservationSyncScheduler.schedule(this, baseUrl) }
                }
                StatusChangeNotifier.notify(this)
            } else {
                mainExecutor.execute {
                    if (lastWarningAt == now) lastWarningAt = Long.MIN_VALUE
                }
                runCatching {
                    MonitoringStatusStore(this).record(
                        MonitoringState.DEGRADED,
                        "A road report could not be saved; no detection acknowledgement was played.",
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        if (::warningPlayer.isInitialized) warningPlayer.close()
        storageExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        if (
            !DataLifecycleGate.isDeletionInProgress(this) &&
            AppPreferences(this).hasAcceptedPrivacy()
        ) {
            runCatching {
                MonitoringStatusStore(this).record(
                    MonitoringState.STOPPED,
                    "Phone road detection is not currently running.",
                )
            }
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startMonitoring(): MonitoringCapabilities {
        val sensorActive = sensorManager.getDefaultSensor(
            Sensor.TYPE_LINEAR_ACCELERATION,
        )?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        var locationActive = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) {
                locationActive = runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_INTERVAL_MILLIS,
                        0f,
                        this,
                    )
                }.isSuccess || locationActive
            }
        }
        return MonitoringCapabilities(sensorActive, locationActive)
    }

    private fun RoadHazard.toObservation(location: Location): RoadObservationDraft =
        RoadObservationDraft(
            detectedAtMillis = System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            locationAccuracyMetres = location.accuracy.toDouble().coerceIn(1.0, 500.0),
            speedMetresPerSecond = location.speed.toDouble().coerceIn(0.0, 100.0),
            kind = if (this == RoadHazard.SUDDEN_IMPACT) {
                ObservationKind.ROAD_DAMAGE
            } else {
                ObservationKind.ROUGH_ROAD
            },
            severity = if (this == RoadHazard.SUDDEN_IMPACT) 0.8 else 0.5,
            confidence = if (this == RoadHazard.SUDDEN_IMPACT) 0.75 else 0.65,
            detectorVersion = "phone-v1",
        )

    private fun refreshNearbyHazards(location: Location) {
        val snapshots = nearbyHazards ?: return
        val syncPolicy = AppPreferences(this).backgroundSyncPolicy()
        val networkMetered = getSystemService(ConnectivityManager::class.java)
            .isActiveNetworkMetered
        val batteryLow = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) ?: false
        if (!backgroundDownloadAllowed(syncPolicy, networkMetered, batteryLow)) return
        val region = regionalHazardId(location.latitude, location.longitude)
        val now = SystemClock.elapsedRealtime()
        if (!snapshotRefreshGate.shouldRefresh(region, now)) return
        networkExecutor.execute {
            val refreshed = snapshots.refresh(
                location.latitude,
                location.longitude,
                snapshotRefreshGate,
            ).snapshot
            if (
                snapshotRefreshGate.isCurrent(region) &&
                refreshed?.regionId == region
            ) {
                cachedHazards = refreshed
                StatusChangeNotifier.notify(this)
            }
        }
    }

    private fun warnForCachedHazard(location: Location) {
        val warning = cachedHazards?.let { snapshot ->
            findUpcomingHazard(
                snapshot = snapshot,
                latitude = location.latitude,
                longitude = location.longitude,
                headingDegrees = location.bearing
                    .toDouble()
                    .takeIf { location.hasBearing() },
            )
        }
        if (
            publicHazardWarningGate.shouldWarn(
                warning,
                SystemClock.elapsedRealtime(),
            )
        ) {
            warningPlayer.warn(checkNotNull(warning).message)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.speedAccuracyKph(): Double =
        if (hasSpeedAccuracy()) {
            speedAccuracyMetersPerSecond.toDouble().coerceAtLeast(0.0) * 3.6
        } else {
            0.0
        }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Driving detection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps phone-only road detection active while the screen is off."
            },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("MichiSonae is watching the road")
        .setContentText("Phone-only driving and road-hazard detection is active.")
        .setOngoing(true)
        .build()

    private companion object {
        const val CHANNEL_ID = "driving-detection"
        const val NOTIFICATION_ID = 1001
        const val LOCATION_INTERVAL_MILLIS = 1_000L
        const val WARNING_COOLDOWN_MILLIS = 8_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private data class MonitoringCapabilities(
        val sensorActive: Boolean,
        val locationActive: Boolean,
    )
}
