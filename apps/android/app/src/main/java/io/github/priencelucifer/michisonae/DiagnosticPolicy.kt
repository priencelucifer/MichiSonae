package io.github.priencelucifer.michisonae

internal enum class DiagnosticSeverity(val displayName: String) {
    ADVISORY("Check when convenient"),
    SERVICE_SOON("Arrange service soon"),
    STOP_SAFELY("Stop safely"),
}

internal data class DiagnosticFinding(
    val code: String,
    val title: String,
    val severity: DiagnosticSeverity,
    val safeAction: String,
)

internal object DiagnosticPolicy {
    fun interpret(code: String): DiagnosticFinding = when {
        code == "P0217" -> DiagnosticFinding(
            code,
            "Engine temperature too high",
            DiagnosticSeverity.STOP_SAFELY,
            "Pull over safely, switch off the engine, and let it cool. Do not open a hot cap.",
        )

        code == "P0524" -> DiagnosticFinding(
            code,
            "Engine oil pressure may be too low",
            DiagnosticSeverity.STOP_SAFELY,
            "Pull over safely and switch off the engine. Check the oil only when it is safe.",
        )

        code.startsWith("P030") -> DiagnosticFinding(
            code,
            "Engine misfire detected",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange a professional inspection soon.",
        )

        code.startsWith("P01") -> DiagnosticFinding(
            code,
            "Engine sensor or emissions issue",
            DiagnosticSeverity.SERVICE_SOON,
            "The car may continue normally, but arrange a professional diagnosis soon.",
        )

        else -> DiagnosticFinding(
            code,
            "Vehicle diagnostic code detected",
            DiagnosticSeverity.ADVISORY,
            "Check the owner's manual and arrange a professional diagnosis.",
        )
    }
}
