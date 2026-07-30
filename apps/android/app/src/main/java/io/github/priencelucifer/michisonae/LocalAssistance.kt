package io.github.priencelucifer.michisonae

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

internal enum class PlaceAvailability(val displayName: String) {
    OPEN("Reported open"),
    CLOSED("Reported closed"),
    UNKNOWN("Hours uncertain"),
}

internal data class ServiceCenterOption(
    val name: String,
    val availability: PlaceAvailability,
    val mapQuery: String,
)

internal fun mapSearchUri(query: String): String {
    require(query.isNotBlank())
    val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
    return "geo:0,0?q=$encoded"
}

internal fun Context.openMapSearch(query: String): Boolean {
    val uri = Uri.parse(mapSearchUri(query))
    val googleMaps = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(googleMaps) }.isSuccess) return true
    return runCatching {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, uri),
                "Open map",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

internal data class ExplainedDiagnostic(
    val finding: DiagnosticFinding,
    val explanation: String,
)

internal fun localGemmaPrompt(finding: DiagnosticFinding): String =
    """
    Explain this vehicle diagnostic in plain English using at most three sentences.
    Code: ${finding.code}
    Policy title: ${finding.title}
    Deterministic safe action: ${finding.safeAction}
    Do not change the severity or safe action. Do not invent a repair or certainty.
    """.trimIndent()

internal fun attachLocalExplanation(
    finding: DiagnosticFinding,
    modelExplanation: String,
): ExplainedDiagnostic {
    val explanation = modelExplanation.trim().take(600)
    require(explanation.isNotBlank())
    return ExplainedDiagnostic(finding, explanation)
}

internal fun mockLocalExplanation(finding: DiagnosticFinding): String =
    "The car reported ${finding.code}, which usually points to ${finding.title.lowercase()}. " +
        "The exact cause still needs a professional diagnosis."

internal enum class LocalVoiceCommand {
    FIND_FUEL,
    FIND_SERVICE,
    REPEAT_WARNING,
    NONE,
}

internal fun parseLocalVoiceTranscript(transcript: String): LocalVoiceCommand {
    val normalized = transcript.trim().lowercase()
    if (!normalized.startsWith("michisonae")) return LocalVoiceCommand.NONE
    return when {
        "fuel" in normalized -> LocalVoiceCommand.FIND_FUEL
        "service" in normalized || "mechanic" in normalized ->
            LocalVoiceCommand.FIND_SERVICE

        "repeat" in normalized -> LocalVoiceCommand.REPEAT_WARNING
        else -> LocalVoiceCommand.NONE
    }
}
