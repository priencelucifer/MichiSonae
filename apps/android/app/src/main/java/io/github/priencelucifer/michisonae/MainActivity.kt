package io.github.priencelucifer.michisonae

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MichiSonaeApp()
            }
        }
    }
}

@Composable
internal fun MichiSonaeApp() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var hasConsent by remember { mutableStateOf(preferences.hasAcceptedPrivacy()) }
    var vehicleProfile by remember { mutableStateOf(preferences.loadVehicleProfile()) }
    var isEditingVehicle by rememberSaveable { mutableStateOf(false) }
    var isDrivingDemoOpen by rememberSaveable { mutableStateOf(false) }
    var isVehicleDemoOpen by rememberSaveable { mutableStateOf(false) }
    var isAssistanceOpen by rememberSaveable { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var hasDrivingPermissions by remember {
        mutableStateOf(hasDrivingPermissions(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasDrivingPermissions = hasDrivingPermissions(context)
    }

    LaunchedEffect(hasConsent, vehicleProfile, hasDrivingPermissions) {
        if (hasConsent && vehicleProfile != null && hasDrivingPermissions) {
            runCatching {
                context.startForegroundService(
                    Intent(context, DrivingMonitorService::class.java),
                )
            }
        }
    }

    when {
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
            onBack = { isVehicleDemoOpen = false },
        )

        isAssistanceOpen -> AssistanceScreen(
            onBack = { isAssistanceOpen = false },
        )

        isSettingsOpen -> SettingsScreen(
            pendingObservationCount = remember {
                runCatching {
                    OfflineObservationQueue(context).pendingCount()
                }.getOrDefault(0)
            },
            onDeleteAllData = {
                context.stopService(Intent(context, DrivingMonitorService::class.java))
                runCatching {
                    OfflineObservationQueue(context).clearAll()
                    preferences.clearAll()
                }.isSuccess.also { deleted ->
                    if (deleted) {
                        vehicleProfile = null
                        hasConsent = false
                        isSettingsOpen = false
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
            onOpenSettings = { isSettingsOpen = true },
        )
    }
}

@Composable
private fun DrivingPermissionScreen(onRequest: () -> Unit) {
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Keep detection active", style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
            Text("Your car", style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save vehicle")
            }
            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
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
    onOpenSettings: () -> Unit,
) {
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ready to drive", style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try phone detection demo")
            }
            OutlinedButton(
                onClick = onOpenVehicleDemo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try OBD and fuel demo")
            }
            OutlinedButton(
                onClick = onOpenAssistance,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Fuel pumps and service centers")
            }
            OutlinedButton(
                onClick = onEditVehicle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit vehicle")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Settings and privacy")
            }
        }
    }
}

@Composable
private fun VehicleDemoScreen(
    profile: VehicleProfile,
    onBack: () -> Unit,
) {
    var manualFuelPercent by rememberSaveable { mutableStateOf("") }
    var showGauges by rememberSaveable { mutableStateOf(false) }
    val fuelReading = checkNotNull(
        Elm327Parser.reading(
            Elm327Command.FUEL_LEVEL,
            Elm327Simulator.response(Elm327Command.FUEL_LEVEL),
        ),
    )
    val manualValue = manualFuelPercent.toDoubleOrNull()
    val usesManualValue = manualValue != null && manualValue in 0.0..100.0
    val estimate = estimateFuelRange(
        profile = profile,
        fuelPercent = if (usesManualValue) checkNotNull(manualValue) else fuelReading.value,
        source = if (usesManualValue) FuelLevelSource.MANUAL else FuelLevelSource.OBD,
    )
    val advice = FuelCoverageGuardian.evaluate(
        estimate = estimate,
        stationsAhead = listOf(
            FuelStationAhead(
                name = "Upcoming fuel pump",
                distanceAheadKm = estimate.conservativeKm * 0.25,
                isOpen = true,
            ),
            FuelStationAhead(
                name = "Following fuel pump",
                distanceAheadKm = estimate.conservativeKm * 1.25,
                isOpen = null,
            ),
        ),
        remainingRouteKm = estimate.conservativeKm * 2,
    )
    val diagnosticCode = Elm327Parser.troubleCodes(
        Elm327Simulator.response(Elm327Command.READ_TROUBLE_CODES),
    ).first()
    val finding = DiagnosticPolicy.interpret(diagnosticCode)
    val explained = attachLocalExplanation(finding, mockLocalExplanation(finding))
    var showExplanation by rememberSaveable { mutableStateOf(false) }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Vehicle demo", style = MaterialTheme.typography.headlineMedium)
            Text("Simulated cheap ELM327 adapter. Every command is read-only.")
            CapabilityCard(
                name = "Diagnostic $diagnosticCode",
                detail = "${finding.title}. ${finding.safeAction}",
                capability = Capability.DEGRADED,
            )
            OutlinedButton(
                onClick = { showExplanation = !showExplanation },
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun AssistanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
            Text("Nearby help", style = MaterialTheme.typography.headlineMedium)
            Text(
                "MichiSonae hands search and navigation to your map app. Opening hours must " +
                    "be confirmed there because they can change.",
            )
            Button(
                onClick = { context.openMapSearch("fuel station near me") },
                modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    pendingObservationCount: Int,
    onDeleteAllData: () -> Boolean,
    onBack: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deletionFailed by rememberSaveable { mutableStateOf(false) }

    Page {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings and privacy", style = MaterialTheme.typography.headlineMedium)
            Text("Phone-only detection is always active after location permission is granted.")
            Text("Warnings use English voice, sound, vibration, and temporary music pause.")
            Text("$pendingObservationCount road observations are waiting for durable upload.")
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
                    "I understand this deletes the vehicle profile, anonymous local identity, " +
                        "consent, and queued road observations.",
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                )
            }
            Button(
                onClick = {
                    deletionFailed = !onDeleteAllData()
                },
                enabled = confirmDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete all local data")
            }
            if (deletionFailed) {
                Text(
                    "Some local data could not be deleted. Please try again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
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
            Text("Phone detection demo", style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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

internal enum class Capability(val displayName: String) {
    PLANNED("planned"),
    AVAILABLE("available"),
    DEGRADED("degraded"),
}
