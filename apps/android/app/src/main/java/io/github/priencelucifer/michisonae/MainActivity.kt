package io.github.priencelucifer.michisonae

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

        else -> HomeScreen(
            profile = checkNotNull(vehicleProfile),
            onEditVehicle = { isEditingVehicle = true },
        )
    }
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
                detail = "Available without hardware or OBD-II",
                capability = Capability.AVAILABLE,
            )
            CapabilityCard(
                name = "Read-only OBD-II",
                detail = "Optional adapter; never writes to the car",
                capability = Capability.PLANNED,
            )
            CapabilityCard(
                name = "Fuel Coverage Guardian",
                detail = "Range and upcoming-station estimates",
                capability = Capability.PLANNED,
            )
            OutlinedButton(
                onClick = onEditVehicle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit vehicle")
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
