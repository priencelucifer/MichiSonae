package io.github.priencelucifer.michisonae

import java.util.Locale

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
    fun interpret(rawCode: String): DiagnosticFinding {
        val code = rawCode.trim().uppercase(Locale.ROOT).takeIf(DIAGNOSTIC_CODE::matches)
            ?: return DiagnosticFinding(
                "UNKNOWN",
                "Unrecognized diagnostic value",
                DiagnosticSeverity.ADVISORY,
                "Reconnect the adapter and read the codes again. Do not act on an invalid value.",
            )
        return when {
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

        code in "P0300".."P0312" -> DiagnosticFinding(
            code,
            "Engine misfire detected",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange service soon. If the warning flashes or the car shakes strongly, stop safely.",
        )

        code in SYSTEM_VOLTAGE_CODES -> DiagnosticFinding(
            code,
            "Vehicle voltage is outside its expected range",
            DiagnosticSeverity.SERVICE_SOON,
            "Reduce unnecessary electrical loads and arrange service. Stop safely if steering, braking, or engine operation changes.",
        )

        code in FUEL_MIXTURE_CODES -> DiagnosticFinding(
            code,
            "Fuel and air mixture is outside its expected range",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange a professional inspection soon.",
        )

        code in CATALYST_CODES -> DiagnosticFinding(
            code,
            "Emissions catalyst efficiency is below its expected level",
            DiagnosticSeverity.SERVICE_SOON,
            "The car may continue normally, but arrange a professional diagnosis soon.",
        )

        code in EVAPORATIVE_SYSTEM_CODES -> DiagnosticFinding(
            code,
            "Fuel-vapour control system issue",
            DiagnosticSeverity.ADVISORY,
            "When safely parked, check that the fuel cap is secure. Arrange service if the code returns.",
        )

        code == "P0128" -> DiagnosticFinding(
            code,
            "Engine is taking too long to reach normal temperature",
            DiagnosticSeverity.SERVICE_SOON,
            "Monitor the temperature warning and arrange a professional inspection soon.",
        )

        code in "P0115".."P0119" -> DiagnosticFinding(
            code,
            "Engine coolant temperature signal issue",
            DiagnosticSeverity.SERVICE_SOON,
            "Watch for an overheating warning and arrange service soon. Stop safely if the engine overheats.",
        )

        code in "P0100".."P0104" -> DiagnosticFinding(
            code,
            "Engine airflow signal issue",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange a professional inspection soon.",
        )

        code in "P0130".."P0167" -> DiagnosticFinding(
            code,
            "Exhaust oxygen-sensor circuit issue",
            DiagnosticSeverity.SERVICE_SOON,
            "The car may use more fuel or run poorly. Arrange a professional diagnosis soon.",
        )

        code in "P0200".."P0208" -> DiagnosticFinding(
            code,
            "Fuel-injector circuit issue",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange service soon. Stop safely if the engine runs very roughly.",
        )

        code in "P0335".."P0349" -> DiagnosticFinding(
            code,
            "Engine position-sensor signal issue",
            DiagnosticSeverity.SERVICE_SOON,
            "The engine may stall or fail to restart. Arrange service soon and stop safely if operation changes.",
        )

        code in "P0400".."P0409" -> DiagnosticFinding(
            code,
            "Exhaust-gas recirculation system issue",
            DiagnosticSeverity.SERVICE_SOON,
            "Avoid hard acceleration and arrange a professional inspection soon.",
        )

        code in "P0500".."P0503" -> DiagnosticFinding(
            code,
            "Vehicle-speed signal issue",
            DiagnosticSeverity.SERVICE_SOON,
            "Do not rely on app speed or fuel-range estimates until the signal is checked. Arrange service soon.",
        )

        code == "P0700" -> DiagnosticFinding(
            code,
            "Transmission control system requested attention",
            DiagnosticSeverity.SERVICE_SOON,
            "Drive gently and arrange a transmission diagnosis soon. Stop safely if shifting becomes unsafe.",
        )

        code.startsWith("P01") -> DiagnosticFinding(
            code,
            "Engine sensor or emissions issue",
            DiagnosticSeverity.SERVICE_SOON,
            "The car may continue normally, but arrange a professional diagnosis soon.",
        )

        code[0] == 'P' && code[1] != '0' -> DiagnosticFinding(
            code,
            "Manufacturer-specific powertrain code",
            DiagnosticSeverity.SERVICE_SOON,
            "Use the vehicle maker's service information and arrange a professional diagnosis.",
        )

        code.startsWith("C") -> DiagnosticFinding(
            code,
            "Chassis system diagnostic code",
            DiagnosticSeverity.SERVICE_SOON,
            "Arrange a professional inspection. Stop safely if steering, braking, or stability warnings appear.",
        )

        code.startsWith("U") -> DiagnosticFinding(
            code,
            "Vehicle network communication code",
            DiagnosticSeverity.SERVICE_SOON,
            "Some vehicle systems may not be communicating. Arrange a professional inspection soon.",
        )

        code.startsWith("B") -> DiagnosticFinding(
            code,
            "Body, cabin, or restraint system diagnostic code",
            DiagnosticSeverity.SERVICE_SOON,
            "Arrange a professional diagnosis soon. If an airbag or restraint warning is shown, do not assume that system will operate normally.",
        )

        else -> DiagnosticFinding(
            code,
            "Vehicle diagnostic code detected",
            DiagnosticSeverity.ADVISORY,
            "Check the owner's manual and arrange a professional diagnosis.",
        )
        }
    }
}

private val DIAGNOSTIC_CODE = Regex("^[PCBU][0-3][0-9A-F]{3}$")
private val SYSTEM_VOLTAGE_CODES = setOf("P0560", "P0561", "P0562", "P0563")
private val FUEL_MIXTURE_CODES = setOf("P0171", "P0172", "P0174", "P0175")
private val CATALYST_CODES = setOf("P0420", "P0430")
private val EVAPORATIVE_SYSTEM_CODES = setOf(
    "P0440",
    "P0441",
    "P0442",
    "P0443",
    "P0444",
    "P0445",
    "P0446",
    "P0447",
    "P0448",
    "P0449",
    "P0450",
    "P0451",
    "P0452",
    "P0453",
    "P0454",
    "P0455",
    "P0456",
    "P0457",
)
