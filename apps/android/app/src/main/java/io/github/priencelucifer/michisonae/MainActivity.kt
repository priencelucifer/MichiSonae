package io.github.priencelucifer.michisonae

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deletionRecoveryBlocked = if (DataLifecycleGate.isDeletionInProgress(this)) {
            val result = runCatching {
                DataLifecycleGate.beginDeletion(this)
                stopService(Intent(this, DrivingMonitorService::class.java))
                clearAllLocalData(this)
            }.getOrNull()
            result?.succeeded != true ||
                runCatching { DataLifecycleGate.endDeletion(this) }.isFailure
        } else {
            false
        }
        setContent {
            MaterialTheme {
                MichiSonaeApp(
                    sharedDestinationText = intent
                        ?.takeIf { it.action == Intent.ACTION_SEND }
                        ?.getStringExtra(Intent.EXTRA_TEXT),
                    deletionRecoveryBlocked = deletionRecoveryBlocked,
                )
            }
        }
    }
}

@Composable
internal fun MichiSonaeApp(
    sharedDestinationText: String? = null,
    deletionRecoveryBlocked: Boolean = false,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var hasConsent by remember { mutableStateOf(preferences.hasAcceptedPrivacy()) }
    var vehicleProfile by remember { mutableStateOf(preferences.loadVehicleProfile()) }
    var isEditingVehicle by rememberSaveable { mutableStateOf(false) }
    var isDrivingDemoOpen by rememberSaveable { mutableStateOf(false) }
    var isVehicleDemoOpen by rememberSaveable { mutableStateOf(false) }
    var isAssistanceOpen by rememberSaveable {
        mutableStateOf(!sharedDestinationText.isNullOrBlank())
    }
    var isManualReportOpen by rememberSaveable { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isSystemStatusOpen by rememberSaveable { mutableStateOf(false) }
    var statusRevision by remember { mutableStateOf(0) }
    var hasDrivingPermissions by remember {
        mutableStateOf(hasDrivingPermissions(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasDrivingPermissions = hasDrivingPermissions(context)
        statusRevision += 1
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        statusRevision += 1
    }
    val statusListener = remember { { statusRevision += 1 } }

    DisposableEffect(statusListener) {
        StatusChangeNotifier.register(statusListener)
        onDispose { StatusChangeNotifier.unregister(statusListener) }
    }

    LaunchedEffect(
        hasConsent,
        vehicleProfile,
        hasDrivingPermissions,
        deletionRecoveryBlocked,
    ) {
        if (
            !deletionRecoveryBlocked &&
            hasConsent &&
            vehicleProfile != null &&
            hasDrivingPermissions
        ) {
            runCatching {
                preferences.setShouldResumeMonitoring(true)
                context.startForegroundService(
                    Intent(context, DrivingMonitorService::class.java),
                )
            }
        }
    }

    when {
        deletionRecoveryBlocked -> DeletionRecoveryBlockedScreen()

        !hasConsent -> OnboardingScreen {
            preferences.acceptPrivacy()
            hasConsent = true
        }

        vehicleProfile == null || isEditingVehicle -> VehicleProfileScreen(
            initialProfile = vehicleProfile,
            canCancel = vehicleProfile != null,
            onCancel = { isEditingVehicle = false },
            onSave = {
                preferences.saveVehicleProfile(it)
                vehicleProfile = it
                isEditingVehicle = false
            },
        )

        !hasDrivingPermissions -> DrivingPermissionScreen {
            permissionLauncher.launch(requiredDrivingPermissions())
        }

        isDrivingDemoOpen -> DrivingDemoScreen(
            vehicleClass = checkNotNull(vehicleProfile).vehicleClass,
            onBack = { isDrivingDemoOpen = false },
        )

        isVehicleDemoOpen -> VehicleDemoScreen(
            profile = checkNotNull(vehicleProfile),
            revision = statusRevision,
            onRequestBluetoothPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            onBack = { isVehicleDemoOpen = false },
        )

        isAssistanceOpen -> AssistanceScreen(
            initialDestinationText = sharedDestinationText,
            onBack = { isAssistanceOpen = false },
        )

        isManualReportOpen -> ManualHazardReportScreen(
            onBack = { isManualReportOpen = false },
        )

        isSystemStatusOpen -> SystemStatusScreen(
            revision = statusRevision,
            onRequestLocationPermission = {
                permissionLauncher.launch(requiredDrivingPermissions())
            },
            onBack = { isSystemStatusOpen = false },
        )

        isSettingsOpen -> SettingsScreen(
            backendStatus = remember(statusRevision) {
                val snapshot = HazardSnapshotCache(context).read()
                val latestSync = ObservationSyncStatus(context).latest()
                val now = System.currentTimeMillis()
                backendStatus(
                    pendingUploadCount = runCatching {
                        OfflineObservationQueue(context).pendingCount()
                    }.getOrNull(),
                    hasNetwork = hasInternetConnection(context),
                    lastSyncFailureAtMillis = now.takeIf {
                        latestSync == LatestSyncStatus.RETRYING ||
                            latestSync == LatestSyncStatus.REJECTED
                    },
                    hasCachedSnapshot = snapshot != null,
                    snapshotGeneratedAtMillis = snapshot?.generatedAtMillis,
                    nowMillis = now,
                    backendConfigured = BuildConfig.MICHI_API_BASE_URL.isNotBlank(),
                )
            },
            monitoringStatus = remember(statusRevision) {
                MonitoringStatusStore(context).read()
            },
            backgroundSyncPolicy = remember(statusRevision) {
                preferences.backgroundSyncPolicy()
            },
            onBackgroundSyncPolicyChanged = { policy ->
                preferences.saveBackgroundSyncPolicy(policy)
                ObservationSyncScheduler.configuredBaseUrl(context)?.let { baseUrl ->
                    runCatching { ObservationSyncScheduler.schedule(context, baseUrl) }
                }
                statusRevision += 1
            },
            onDeleteAllData = { onComplete ->
                val deletionStarted = runCatching {
                    DataLifecycleGate.beginDeletion(context)
                }.isSuccess
                if (!deletionStarted) {
                    onComplete(false)
                } else {
                    context.stopService(Intent(context, DrivingMonitorService::class.java))
                    thread(name = "michisonae-data-deletion") {
                        val result = clearAllLocalData(context)
                        val credentials = result.credentialsToRevoke
                        val baseUrl = BuildConfig.MICHI_API_BASE_URL.takeIf(String::isNotBlank)
                        if (credentials != null && baseUrl != null) {
                            runCatching {
                                revokeCredentialsBestEffort(
                                    MichiSonaeApi(baseUrl),
                                    credentials,
                                )
                            }
                        }
                        val deletionCompleted = result.succeeded &&
                            runCatching {
                                DataLifecycleGate.endDeletion(context)
                            }.isSuccess
                        context.stopService(Intent(context, DrivingMonitorService::class.java))
                        context.mainExecutor.execute {
                            if (deletionCompleted) {
                                vehicleProfile = null
                                hasConsent = false
                                isSettingsOpen = false
                            }
                            onComplete(deletionCompleted)
                        }
                    }
                }
            },
            onBack = { isSettingsOpen = false },
        )

        else -> HomeScreen(
            profile = checkNotNull(vehicleProfile),
            onEditVehicle = { isEditingVehicle = true },
            onOpenDrivingDemo = { isDrivingDemoOpen = true },
            onOpenVehicleDemo = { isVehicleDemoOpen = true },
            onOpenAssistance = { isAssistanceOpen = true },
            onOpenManualReport = { isManualReportOpen = true },
            onOpenSystemStatus = { isSystemStatusOpen = true },
            onOpenSettings = { isSettingsOpen = true },
        )
    }
}

private data class LocalDeletionResult(
    val succeeded: Boolean,
    val credentialsToRevoke: AnonymousCredentials?,
)

private fun clearAllLocalData(context: Context): LocalDeletionResult {
    var credentialsToRevoke: AnonymousCredentials? = null
    val succeeded = runCatching {
        CredentialOperationCoordinator.runExclusive {
            ObservationSyncScheduler.cancel(context)
            val credentialStore = EncryptedCredentialStore(context)
            credentialsToRevoke = credentialStore.load()
            credentialStore.clear()
            AppPreferences(context).clearAll()
            OfflineObservationQueue(context).clearAll()
            HazardSnapshotCache(context).clear()
            DiagnosticCardStore(context).clear()
            MonitoringStatusStore(context).clear()
        }
    }.isSuccess
    return LocalDeletionResult(succeeded, credentialsToRevoke)
}

@Composable
private fun DeletionRecoveryBlockedScreen() {
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Finishing data deletion")
            Text(
                "MichiSonae could not finish removing local data. Monitoring, uploads and " +
                    "new local records remain blocked. Restart the app to retry safely.",
            )
        }
    }
}

@Composable
private fun DrivingPermissionScreen(onRequest: () -> Unit) {
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Keep detection active")
            Text(
                "Location lets MichiSonae detect driving speed and attach a position only when " +
                    "a road hazard is detected.",
            )
            Text(
                "A permanent low-priority notification keeps phone detection active while the " +
                    "screen is dark. MichiSonae does not create trip history.",
            )
            Button(
                onClick = onRequest,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Allow driving detection")
            }
        }
    }
}

private fun hasDrivingPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredDrivingPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun hasInternetConnection(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork ?: return false
    return connectivity.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Scaffold { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { content() }
        }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("MichiSonae", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Quiet road and vehicle warnings while you drive.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Works with phone sensors alone. OBD-II is optional and always read-only.")
            Text("No account and no trip history.")
            Text(
                "Raw microphone audio, raw vehicle diagnostics, local AI conversations, " +
                    "and sensor-tuning traces are never uploaded.",
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { accepted = it },
                )
                Text(
                    text = "I understand that warnings and fuel range are estimates, not a " +
                        "replacement for safe driving or professional vehicle service.",
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                )
            }
            Button(
                onClick = onContinue,
                enabled = accepted,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun VehicleProfileScreen(
    initialProfile: VehicleProfile?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (VehicleProfile) -> Unit,
) {
    var nickname by rememberSaveable { mutableStateOf(initialProfile?.nickname.orEmpty()) }
    var vehicleClassName by rememberSaveable {
        mutableStateOf((initialProfile?.vehicleClass ?: VehicleClass.COMPACT).name)
    }
    var fuelTypeName by rememberSaveable {
        mutableStateOf((initialProfile?.fuelType ?: FuelType.PETROL).name)
    }
    var tankCapacity by rememberSaveable {
        mutableStateOf(initialProfile?.tankCapacityLitres?.toString().orEmpty())
    }
    var efficiency by rememberSaveable {
        mutableStateOf(initialProfile?.efficiencyKmPerLitre?.toString().orEmpty())
    }

    val candidate = VehicleProfile(
        nickname = nickname.trim(),
        vehicleClass = VehicleClass.valueOf(vehicleClassName),
        fuelType = FuelType.valueOf(fuelTypeName),
        tankCapacityLitres = tankCapacity.toDoubleOrNull() ?: 0.0,
        efficiencyKmPerLitre = efficiency.toDoubleOrNull() ?: 0.0,
    )
    val error = candidate.validationError()

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Your car")
            Text("Used only on this phone to tune warnings and estimate fuel range.")
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Car name") },
                supportingText = { Text("Example: My car") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceRow(
                label = "Vehicle size",
                choices = VehicleClass.entries,
                selected = VehicleClass.valueOf(vehicleClassName),
                choiceLabel = { it.displayName },
                onSelect = { vehicleClassName = it.name },
            )
            ChoiceRow(
                label = "Fuel",
                choices = FuelType.entries,
                selected = FuelType.valueOf(fuelTypeName),
                choiceLabel = { it.displayName },
                onSelect = { fuelTypeName = it.name },
            )
            DecimalField(
                value = tankCapacity,
                onValueChange = { tankCapacity = it },
                label = "Tank capacity (litres)",
            )
            DecimalField(
                value = efficiency,
                onValueChange = { efficiency = it },
                label = "Estimated efficiency (km/litre)",
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSave(candidate) },
                enabled = error == null,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Save vehicle")
            }
            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.drivingControl(),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(choiceLabel(choice)) },
                )
            }
        }
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input.count { it == '.' } <= 1 && input.all { it.isDigit() || it == '.' }) {
                onValueChange(input)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HomeScreen(
    profile: VehicleProfile,
    onEditVehicle: () -> Unit,
    onOpenDrivingDemo: () -> Unit,
    onOpenVehicleDemo: () -> Unit,
    onOpenAssistance: () -> Unit,
    onOpenManualReport: () -> Unit,
    onOpenSystemStatus: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Ready to drive",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "${profile.nickname} • ${profile.vehicleClass.displayName} • " +
                    "${profile.fuelType.displayName}",
            )
            CapabilityCard(
                name = "Phone road detection",
                detail = "Deterministic demo available without hardware or OBD-II",
                capability = Capability.AVAILABLE,
            )
            CapabilityCard(
                name = "Read-only OBD-II",
                detail = "Read-only ELM327 simulator; never writes to the car",
                capability = Capability.AVAILABLE,
            )
            CapabilityCard(
                name = "Fuel Coverage Guardian",
                detail = "Conservative range and upcoming-station simulation",
                capability = Capability.AVAILABLE,
            )
            OutlinedButton(
                onClick = onOpenDrivingDemo,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Try phone detection demo")
            }
            OutlinedButton(
                onClick = onOpenVehicleDemo,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Try OBD and fuel demo")
            }
            OutlinedButton(
                onClick = onOpenAssistance,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Fuel pumps and service centers")
            }
            OutlinedButton(
                onClick = onOpenManualReport,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Report a road hazard while stopped")
            }
            OutlinedButton(
                onClick = onOpenSystemStatus,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Device status and self-test")
            }
            OutlinedButton(
                onClick = onEditVehicle,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Edit vehicle")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Settings and privacy")
            }
        }
    }
}

@Composable
private fun ManualHazardReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var category by rememberSaveable { mutableStateOf(ManualHazardCategory.ROAD_DAMAGE) }
    var severity by rememberSaveable { mutableStateOf(ManualHazardSeverity.MEDIUM) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Report a road hazard")
            Text(
                "For safety, reporting works only while the phone has a fresh location " +
                    "showing the vehicle is stopped. No photo or trip history is stored.",
            )
            Text("Hazard type", style = MaterialTheme.typography.titleMedium)
            ManualHazardCategory.entries.forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { category = option },
                    label = { Text(option.displayName) },
                    modifier = Modifier.drivingControl(),
                )
            }
            Text("Severity", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualHazardSeverity.entries.forEach { option ->
                    FilterChip(
                        selected = severity == option,
                        onClick = { severity = option },
                        label = {
                            Text(
                                option.name.lowercase().replaceFirstChar(Char::uppercase),
                            )
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
            Button(
                enabled = !isSaving,
                onClick = {
                    isSaving = true
                    resultMessage = null
                    val selectedCategory = category
                    val selectedSeverity = severity
                    thread(name = "michisonae-manual-hazard-report") {
                        val location = freshestStoppedReportLocation(context)
                        val decision = ManualRoadHazardReportPolicy.prepare(
                            category = selectedCategory,
                            severity = selectedSeverity,
                            drivingState = if (
                                location?.hasSpeed() == true &&
                                location.speed <= MAX_MANUAL_REPORT_SPEED_MPS
                            ) {
                                DrivingState.IDLE
                            } else {
                                DrivingState.DRIVING
                            },
                            detectedAtMillis = System.currentTimeMillis(),
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            locationAccuracyMetres = location?.accuracy?.toDouble(),
                            speedMetresPerSecond = location
                                ?.takeIf(Location::hasSpeed)
                                ?.speed
                                ?.toDouble(),
                        )
                        val draft = decision.draft
                        val stored = draft != null && runCatching {
                            OfflineObservationQueue(context).enqueue(draft)
                        }.getOrDefault(false)
                        if (stored) {
                            BuildConfig.MICHI_API_BASE_URL
                                .takeIf(String::isNotBlank)
                                ?.let { baseUrl ->
                                    runCatching {
                                        ObservationSyncScheduler.schedule(context, baseUrl)
                                    }
                                }
                            StatusChangeNotifier.notify(context)
                        }
                        val message = when {
                            stored ->
                                "Report saved securely on this phone and queued for upload."
                            decision.blockedReason ==
                                ManualReportBlockReason.LOCATION_TOO_INACCURATE ->
                                "Location is not accurate enough yet. Wait safely and try again."
                            decision.blockedReason ==
                                ManualReportBlockReason.LOCATION_UNAVAILABLE ->
                                "A fresh location is unavailable. Wait safely and try again."
                            decision.blockedReason ==
                                ManualReportBlockReason.VEHICLE_MAY_BE_MOVING ->
                                "Reporting is blocked until the vehicle is confirmed stopped."
                            else ->
                                "The report could not be saved. Nothing was acknowledged."
                        }
                        context.mainExecutor.execute {
                            resultMessage = message
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text(if (isSaving) "Saving…" else "Save road-hazard report")
            }
            resultMessage?.let { Text(it) }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun freshestStoppedReportLocation(context: Context): Location? {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val location = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).mapNotNull { provider ->
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.elapsedRealtimeNanos } ?: return null
    val ageMillis = (
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        ).coerceAtLeast(0) / MANUAL_REPORT_NANOS_PER_MILLISECOND
    return location.takeIf {
        ageMillis <= MAX_MANUAL_REPORT_LOCATION_AGE_MILLIS &&
            it.hasAccuracy() &&
            it.accuracy > 0f
    }
}

@Composable
private fun VehicleDemoScreen(
    profile: VehicleProfile,
    revision: Int,
    onRequestBluetoothPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var manualFuelPercent by rememberSaveable { mutableStateOf("") }
    var showGauges by rememberSaveable { mutableStateOf(false) }
    var adapterRevision by remember { mutableStateOf(0) }
    val pairedAdapters = remember(revision, adapterRevision) {
        loadBondedObdAdapters(context)
    }
    var controller by remember { mutableStateOf<Elm327ConnectionController?>(null) }
    var liveConnectionState by remember {
        mutableStateOf<Elm327ConnectionState>(Elm327ConnectionState.Stopped)
    }
    var liveReadings by remember { mutableStateOf<List<String>>(emptyList()) }
    var liveFuelSample by remember { mutableStateOf<FuelLevelSample?>(null) }
    val diagnosticCardStore = remember { DiagnosticCardStore(context) }
    var diagnosticCards by remember {
        mutableStateOf(
            runCatching { diagnosticCardStore.read() }.getOrDefault(emptyList()),
        )
    }
    val connectionState = remember { Elm327ConnectionSimulator.connect() }
    val fuelReading = checkNotNull(
        Elm327Parser.reading(
            Elm327Command.FUEL_LEVEL,
            Elm327Simulator.response(Elm327Command.FUEL_LEVEL),
        ),
    )
    val manualValue = manualFuelPercent.toDoubleOrNull()
    val usesManualValue = manualValue != null && manualValue in 0.0..100.0
    val simulatorObservedAt = remember { System.currentTimeMillis() }
    val manualObservedAt = remember(manualFuelPercent) { System.currentTimeMillis() }
    val fuelSample = when {
        usesManualValue -> FuelLevelSample(
            percent = checkNotNull(manualValue),
            source = FuelLevelSource.MANUAL,
            observedAtEpochMillis = manualObservedAt,
        )

        liveFuelSample != null -> checkNotNull(liveFuelSample)
        else -> FuelLevelSample(
            percent = fuelReading.value,
            source = FuelLevelSource.OBD,
            observedAtEpochMillis = simulatorObservedAt,
        )
    }
    val estimate = estimateFuelRange(
        profile = profile,
        sample = fuelSample,
    )
    val evaluatedAt = System.currentTimeMillis()
    val advice = FuelCoverageGuardian.evaluate(
        FuelRouteScenarioSimulator.criticalGap(estimate).copy(
            evaluatedAtEpochMillis = evaluatedAt,
            stationDataUpdatedAtEpochMillis = evaluatedAt,
        ),
    )
    val diagnosticCode = Elm327Parser.troubleCodes(
        Elm327Simulator.response(Elm327Command.READ_TROUBLE_CODES),
    ).first()
    val finding = DiagnosticPolicy.interpret(diagnosticCode)
    val explained = attachLocalExplanation(finding, mockLocalExplanation(finding))
    var showExplanation by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { controller?.close() }
    }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Vehicle demo")
            Text(
                "Connect an already-paired ELM327 adapter or use the simulator below. " +
                    "Every permitted command is read-only.",
            )
            CapabilityCard(
                name = "Paired OBD-II adapter",
                detail = liveConnectionState.statusText,
                capability = if (liveConnectionState is Elm327ConnectionState.Ready) {
                    Capability.AVAILABLE
                } else {
                    Capability.DEGRADED
                },
            )
            when (pairedAdapters.availability) {
                ObdBluetoothAvailability.PERMISSION_REQUIRED -> Button(
                    onClick = onRequestBluetoothPermission,
                    modifier = Modifier.drivingControl(),
                ) {
                    Text("Allow Bluetooth for OBD-II")
                }

                ObdBluetoothAvailability.BLUETOOTH_DISABLED -> {
                    Text("Bluetooth is off. Phone-only road detection remains active.")
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        modifier = Modifier.drivingControl(),
                    ) {
                        Text("Open Bluetooth settings")
                    }
                }

                ObdBluetoothAvailability.NO_PAIRED_DEVICES -> {
                    Text(
                        "No paired adapter was found. Pair the ELM327 in Android Bluetooth " +
                            "settings, then return and refresh.",
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        modifier = Modifier.drivingControl(),
                    ) {
                        Text("Pair in Bluetooth settings")
                    }
                }

                ObdBluetoothAvailability.BLUETOOTH_UNAVAILABLE ->
                    Text("This phone has no Bluetooth adapter. OBD-II remains optional.")

                ObdBluetoothAvailability.READY -> pairedAdapters.adapters.forEach { adapter ->
                    Button(
                        onClick = {
                            controller?.close()
                            liveReadings = emptyList()
                            val device = findBondedObdDevice(context, adapter.address)
                            if (device == null) {
                                liveConnectionState = Elm327ConnectionState.Stopped
                            } else {
                                var nextController: Elm327ConnectionController? = null
                                nextController = Elm327ConnectionController(device) { state ->
                                    context.mainExecutor.execute {
                                        if (controller === nextController) {
                                            liveConnectionState = state
                                            StatusChangeNotifier.notify(context)
                                        }
                                    }
                                }
                                controller = nextController
                                checkNotNull(nextController).start()
                            }
                        },
                        modifier = Modifier.drivingControl(),
                    ) {
                        Text("Connect read-only: ${adapter.displayName}")
                    }
                }
            }
            OutlinedButton(
                onClick = { adapterRevision += 1 },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Refresh paired adapters")
            }
            if (liveConnectionState is Elm327ConnectionState.Ready) {
                Button(
                    onClick = {
                        liveReadings = emptyList()
                        controller?.let { activeController ->
                            listOf(
                                Elm327Command.ENGINE_RPM,
                                Elm327Command.VEHICLE_SPEED,
                                Elm327Command.COOLANT_TEMPERATURE,
                                Elm327Command.CONTROL_MODULE_VOLTAGE,
                                Elm327Command.FUEL_LEVEL,
                            ).forEach { command ->
                                activeController.query(command) { result ->
                                    val reading = result.getOrNull()?.let { response ->
                                        Elm327Parser.reading(command, response)
                                    } ?: return@query
                                    context.mainExecutor.execute {
                                        if (controller === activeController) {
                                            if (command == Elm327Command.FUEL_LEVEL) {
                                                liveFuelSample = FuelLevelSample(
                                                    percent = reading.value,
                                                    source = FuelLevelSource.OBD,
                                                    observedAtEpochMillis =
                                                        System.currentTimeMillis(),
                                                )
                                            }
                                            liveReadings = (
                                                liveReadings +
                                                    "${reading.label}: ${reading.value} ${reading.unit}"
                                                ).distinct()
                                        }
                                    }
                                }
                            }
                            activeController.query(Elm327Command.READ_TROUBLE_CODES) { result ->
                                val codes = result.getOrNull()?.let(Elm327Parser::troubleCodes)
                                    ?: return@query
                                val updatedCards = runCatching {
                                    val observedAt = System.currentTimeMillis()
                                    diagnosticCardStore.update(observedAt) { existing ->
                                        refreshDiagnosticCards(
                                            existing = existing,
                                            activeCodes = codes,
                                            observedAtEpochMillis = observedAt,
                                        )
                                    }
                                }.getOrNull()
                                val summaries = if (codes.isEmpty()) {
                                    listOf("No stored engine trouble code was reported.")
                                } else {
                                    codes.map { code ->
                                        val interpreted = DiagnosticPolicy.interpret(code)
                                        "$code: ${interpreted.title}. ${interpreted.safeAction}"
                                    }
                                }
                                context.mainExecutor.execute {
                                    if (controller === activeController) {
                                        liveReadings = (liveReadings + summaries).distinct()
                                        if (updatedCards != null) {
                                            diagnosticCards = updatedCards
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.drivingControl(),
                ) {
                    Text("Read supported live values")
                }
                liveReadings.forEach { Text(it) }
                OutlinedButton(
                    onClick = {
                        controller?.close()
                        controller = null
                        liveConnectionState = Elm327ConnectionState.Stopped
                    },
                    modifier = Modifier.drivingControl(),
                ) {
                    Text("Disconnect adapter")
                }
            }
            if (diagnosticCards.isNotEmpty()) {
                Text("Saved diagnostic cards")
                diagnosticCards.forEach { card ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${card.finding.code}: ${card.finding.title}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(card.finding.severity.displayName)
                            Text(card.finding.safeAction)
                            OutlinedButton(
                                onClick = {
                                    thread(name = "michisonae-delete-diagnostic-card") {
                                        val updated = runCatching {
                                            diagnosticCardStore.update { existing ->
                                                deleteDiagnosticCard(
                                                    existing,
                                                    card.finding.code,
                                                )
                                            }
                                        }.getOrNull()
                                        if (updated != null) {
                                            context.mainExecutor.execute {
                                                diagnosticCards = updated
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.drivingControl(),
                            ) {
                                Text("Delete local card")
                            }
                        }
                    }
                }
            }
            Text("ELM327 simulator")
            Text(connectionState.statusText)
            CapabilityCard(
                name = "Diagnostic $diagnosticCode",
                detail = "${finding.title}. ${finding.safeAction}",
                capability = Capability.DEGRADED,
            )
            OutlinedButton(
                onClick = { showExplanation = !showExplanation },
                modifier = Modifier.drivingControl(),
            ) {
                Text(
                    if (showExplanation) {
                        "Hide local explanation"
                    } else {
                        "Explain in simple English"
                    },
                )
            }
            if (showExplanation) {
                Text(explained.explanation)
                Text(
                    "Mock local explanation. A Gemma model is not bundled yet; severity and " +
                        "safe action always come from deterministic policy.",
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Fuel Coverage Guardian", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${estimate.fuelPercent.toInt()}% fuel from " +
                            "${estimate.source.displayName} • about " +
                            "${estimate.conservativeKm.toInt()} km conservative range",
                    )
                    Text("Estimate only. Actual range changes with traffic and driving.")
                    Text(advice.message, color = MaterialTheme.colorScheme.error)
                }
            }
            DecimalField(
                value = manualFuelPercent,
                onValueChange = { manualFuelPercent = it },
                label = "Manual fuel % (optional)",
            )
            if (manualFuelPercent.isNotBlank() && !usesManualValue) {
                Text(
                    "Manual fuel must be between 0 and 100%.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = { showGauges = !showGauges },
                modifier = Modifier.drivingControl(),
            ) {
                Text(if (showGauges) "Hide detailed gauges" else "Show detailed gauges")
            }
            if (showGauges) {
                listOf(
                    Elm327Command.ENGINE_RPM,
                    Elm327Command.VEHICLE_SPEED,
                    Elm327Command.COOLANT_TEMPERATURE,
                    Elm327Command.CONTROL_MODULE_VOLTAGE,
                ).forEach { command ->
                    val reading = checkNotNull(
                        Elm327Parser.reading(command, Elm327Simulator.response(command)),
                    )
                    Text("${reading.label}: ${reading.value} ${reading.unit}")
                }
            }
            Text(
                "If an adapter does not support fuel level, the same calculation accepts a " +
                    "manual fuel percentage.",
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun AssistanceScreen(
    initialDestinationText: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val initialDestination = remember(initialDestinationText) {
        initialDestinationText?.let {
            normalizeDestination(it, DestinationSource.SHARED)
        }
    }
    var destinationText by rememberSaveable {
        mutableStateOf(initialDestination?.label.orEmpty())
    }
    var favorites by remember {
        mutableStateOf(preferences.loadFavoriteDestinations())
    }
    val destination = normalizeDestination(
        destinationText,
        if (initialDestination != null) {
            DestinationSource.SHARED
        } else {
            DestinationSource.TYPED
        },
    )
    val options = listOf(
        ServiceCenterOption(
            "24-hour service center",
            PlaceAvailability.OPEN,
            "car service center open now",
        ),
        ServiceCenterOption(
            "Nearby independent mechanic",
            PlaceAvailability.UNKNOWN,
            "car mechanic near me",
        ),
        ServiceCenterOption(
            "Dealer workshop",
            PlaceAvailability.CLOSED,
            "car dealer service center",
        ),
    )

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Nearby help")
            Text(
                "MichiSonae hands search and navigation to your map app. Opening hours must " +
                    "be confirmed there because they can change.",
            )
            OutlinedTextField(
                value = destinationText,
                onValueChange = { destinationText = it },
                label = { Text("Destination or shared Maps text") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = destination != null,
                onClick = {
                    destination?.let { context.openMapSearch(it.label) }
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Open destination in Maps")
            }
            OutlinedButton(
                enabled = destination != null,
                onClick = {
                    destination?.let {
                        preferences.saveFavoriteDestination(it)
                        favorites = preferences.loadFavoriteDestinations()
                    }
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Save destination on this phone")
            }
            if (favorites.isNotEmpty()) {
                Text("Favorite destinations", style = MaterialTheme.typography.titleMedium)
                favorites.forEach { favorite ->
                    OutlinedButton(
                        onClick = { context.openMapSearch(favorite.label) },
                        modifier = Modifier.drivingControl(),
                    ) {
                        Text(favorite.label)
                    }
                }
            }
            Button(
                onClick = { context.openMapSearch("fuel station near me") },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Find nearby fuel pumps")
            }
            options.forEach { option ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(option.name, style = MaterialTheme.typography.titleMedium)
                        Text(option.availability.displayName)
                        OutlinedButton(
                            onClick = { context.openMapSearch(option.mapQuery) },
                            modifier = Modifier.drivingControl(),
                        ) {
                            Text("Check options in Maps")
                        }
                    }
                }
            }
            Text(
                "The three availability labels above are demo states. Live results will come " +
                    "from the map app until a places provider is connected.",
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun SystemStatusScreen(
    revision: Int,
    onRequestLocationPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val warningPlayer = remember { DriverWarningPlayer(context) }
    var storageCheck by remember { mutableStateOf<DeviceCheck?>(null) }
    var localRevision by remember { mutableStateOf(0) }
    val checks = remember(revision, localRevision, storageCheck) {
        deviceChecks(context, storageCheck)
    }

    DisposableEffect(warningPlayer) {
        onDispose { warningPlayer.close() }
    }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Device status and self-test",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "These checks stay on this phone. They do not upload sensor, location, " +
                    "diagnostic, or test data.",
            )
            checks.forEach { check ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(check.name, style = MaterialTheme.typography.titleMedium)
                        Text(check.state.displayName)
                        Text(check.detail)
                    }
                }
            }
            Button(
                onClick = {
                    storageCheck = runStorageSelfTest(context)
                    localRevision += 1
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Test private storage")
            }
            Button(
                onClick = { warningPlayer.warn("MichiSonae warning self-test.") },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Test voice, sound and vibration")
            }
            OutlinedButton(
                onClick = {
                    onRequestLocationPermission()
                    localRevision += 1
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Review location permission")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Open location settings")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Open Bluetooth settings")
            }
            OutlinedButton(
                onClick = {
                    localRevision += 1
                    StatusChangeNotifier.notify(context)
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Refresh checks")
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    backendStatus: BackendStatus,
    monitoringStatus: MonitoringStatus,
    backgroundSyncPolicy: BackgroundSyncPolicy,
    onBackgroundSyncPolicyChanged: (BackgroundSyncPolicy) -> Unit,
    onDeleteAllData: ((Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deletionFailed by rememberSaveable { mutableStateOf(false) }
    var deletionInProgress by rememberSaveable { mutableStateOf(false) }
    var unmeteredOnly by rememberSaveable(backgroundSyncPolicy) {
        mutableStateOf(backgroundSyncPolicy.unmeteredOnly)
    }
    var batteryNotLow by rememberSaveable(backgroundSyncPolicy) {
        mutableStateOf(backgroundSyncPolicy.requireBatteryNotLow)
    }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Settings and privacy")
            Text(
                "Phone-only detection starts after location permission is granted and reports " +
                    "a clear status if a sensor or location provider is unavailable.",
            )
            Text("Warnings use English voice, sound, vibration, and temporary music pause.")
            Text("Monitoring: ${monitoringStatus.detail}")
            Text(backendStatus.connectionLabel)
            Text(backendStatus.uploadLabel)
            Text(backendStatus.snapshotLabel)
            Text("Background uploads", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = unmeteredOnly,
                    onCheckedChange = { checked ->
                        unmeteredOnly = checked
                        onBackgroundSyncPolicyChanged(
                            BackgroundSyncPolicy(checked, batteryNotLow),
                        )
                    },
                )
                Text(
                    "Upload queued reports only on Wi-Fi or another unmetered network.",
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = batteryNotLow,
                    onCheckedChange = { checked ->
                        batteryNotLow = checked
                        onBackgroundSyncPolicyChanged(
                            BackgroundSyncPolicy(unmeteredOnly, checked),
                        )
                    },
                )
                Text(
                    "Wait until the battery is not low before uploading.",
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                )
            }
            Text(
                "No account, trip history, raw microphone audio, raw OBD stream, AI " +
                    "conversation, or sensor-tuning trace is stored on the server.",
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = confirmDelete,
                    onCheckedChange = { confirmDelete = it },
                )
                Text(
                    "I understand this deletes the vehicle profile, anonymous identities and " +
                        "credentials, consent, queued road reports, and saved hazard data.",
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                )
            }
            Button(
                onClick = {
                    deletionInProgress = true
                    onDeleteAllData { deleted ->
                        deletionInProgress = false
                        deletionFailed = !deleted
                    }
                },
                enabled = confirmDelete && !deletionInProgress,
                modifier = Modifier.drivingControl(),
            ) {
                Text(if (deletionInProgress) "Deleting..." else "Delete all local data")
            }
            if (deletionFailed) {
                Text(
                    "Some local data could not be deleted. Please try again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun DrivingDemoScreen(
    vehicleClass: VehicleClass,
    onBack: () -> Unit,
) {
    val drivingDetector = remember { AutomaticDrivingDetector() }
    val roadDetector = remember { PhoneRoadHazardDetector() }
    var drivingStateName by rememberSaveable { mutableStateOf(DrivingState.IDLE.name) }
    var warning by rememberSaveable { mutableStateOf<String?>(null) }
    val drivingState = DrivingState.valueOf(drivingStateName)

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle("Phone detection demo")
            Text(
                "Uses simulated speed and motion. No OBD-II, hardware, location, or " +
                    "sensor permission is needed.",
            )
            CapabilityCard(
                name = "Driving status",
                detail = drivingState.displayName,
                capability = if (drivingState == DrivingState.DRIVING) {
                    Capability.AVAILABLE
                } else {
                    Capability.DEGRADED
                },
            )
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    drivingDetector.update(MotionSample(now, 20.0))
                    drivingStateName = drivingDetector.update(
                        MotionSample(now + 30_000, 20.0),
                    ).name
                    warning = null
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Simulate automatic driving detection")
            }
            Button(
                onClick = {
                    warning = roadDetector.observe(
                        sample = RoadSample(
                            speedKph = 30.0,
                            verticalLinearAccelerationG = 1.5,
                        ),
                        vehicleClass = vehicleClass,
                        drivingState = drivingState,
                    )?.userMessage ?: "Start the driving simulation first."
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Simulate sudden road impact")
            }
            Button(
                onClick = {
                    repeat(3) {
                        warning = roadDetector.observe(
                            sample = RoadSample(
                                speedKph = 25.0,
                                verticalLinearAccelerationG = 0.4 *
                                    vehicleClass.roadImpactThresholdMultiplier,
                            ),
                            vehicleClass = vehicleClass,
                            drivingState = drivingState,
                        )?.userMessage ?: warning
                    }
                    if (warning == null) warning = "Start the driving simulation first."
                },
                modifier = Modifier.drivingControl(),
            ) {
                Text("Simulate rough road")
            }
            warning?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                "Detection thresholds are adjusted for ${vehicleClass.displayName.lowercase()} " +
                    "cars. These are test values and require later road calibration.",
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.drivingControl(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    name: String,
    detail: String,
    capability: Capability,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(capability.displayName, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
    )
}

private fun Modifier.drivingControl(): Modifier =
    fillMaxWidth().heightIn(min = 56.dp)

private const val MAX_MANUAL_REPORT_SPEED_MPS = 1.0f
private const val MAX_MANUAL_REPORT_LOCATION_AGE_MILLIS = 10_000L
private const val MANUAL_REPORT_NANOS_PER_MILLISECOND = 1_000_000L

internal enum class Capability(val displayName: String) {
    PLANNED("planned"),
    AVAILABLE("available"),
    DEGRADED("degraded"),
}
