package io.github.priencelucifer.michisonae

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MichiSonaeHome()
            }
        }
    }
}

@Composable
internal fun MichiSonaeHome() {
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "MichiSonae",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = "Road awareness that works from the phone first.",
                style = MaterialTheme.typography.bodyLarge,
            )
            CapabilityText("Phone road detection", Capability.PLANNED)
            CapabilityText("Read-only OBD-II", Capability.PLANNED)
            CapabilityText("Optional RoadSense sensor", Capability.PLANNED)
        }
    }
}

@Composable
private fun CapabilityText(name: String, capability: Capability) {
    Text(
        text = "$name: ${capability.displayName}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal enum class Capability(val displayName: String) {
    PLANNED("planned"),
    AVAILABLE("available"),
    DEGRADED("degraded"),
}
