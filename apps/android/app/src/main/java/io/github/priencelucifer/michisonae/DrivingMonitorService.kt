package io.github.priencelucifer.michisonae

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors

class DrivingMonitorService : Service(), SensorEventListener, LocationListener {
    private val drivingDetector = AutomaticDrivingDetector()
    private val roadDetector = PhoneRoadHazardDetector()
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private lateinit var warningPlayer: DriverWarningPlayer
    private lateinit var observationQueue: OfflineObservationQueue
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var vehicleClass: VehicleClass
    private var drivingState = DrivingState.IDLE
    private var lastLocation: Location? = null
    private var lastWarningAt = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        val profile = AppPreferences(this).loadVehicleProfile()
        if (profile == null || !hasLocationPermission()) {
            stopSelf()
            return
        }
        vehicleClass = profile.vehicleClass
        warningPlayer = DriverWarningPlayer(this)
        observationQueue = OfflineObservationQueue(this)
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        drivingState = drivingDetector.update(
            MotionSample(
                timestampMillis = SystemClock.elapsedRealtime(),
                speedKph = location.speed.coerceAtLeast(0f) * 3.6,
            ),
        )
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

        warningPlayer.warn(hazard.userMessage)
        val observation = hazard.toObservation(location)
        storageExecutor.execute {
            observationQueue.enqueue(observation)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        if (::warningPlayer.isInitialized) warningPlayer.close()
        storageExecutor.shutdown()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) {
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MILLIS,
                    0f,
                    this,
                )
            }
        }
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

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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
    }
}
