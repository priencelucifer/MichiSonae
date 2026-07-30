package io.github.priencelucifer.michisonae

internal enum class Elm327Command(
    val wireValue: String,
    val displayName: String,
) {
    DISABLE_ECHO("ATE0", "Disable adapter echo"),
    AUTOMATIC_PROTOCOL("ATSP0", "Automatic protocol"),
    SUPPORTED_PIDS("0100", "Supported values"),
    ENGINE_LOAD("0104", "Engine load"),
    COOLANT_TEMPERATURE("0105", "Coolant temperature"),
    ENGINE_RPM("010C", "Engine speed"),
    VEHICLE_SPEED("010D", "Vehicle speed"),
    FUEL_LEVEL("012F", "Fuel level"),
    CONTROL_MODULE_VOLTAGE("0142", "Battery voltage"),
    READ_TROUBLE_CODES("03", "Diagnostic trouble codes"),
}

internal data class ObdReading(
    val label: String,
    val value: Double,
    val unit: String,
)

internal object Elm327Parser {
    fun reading(command: Elm327Command, response: String): ObdReading? {
        val bytes = dataBytes(command, response) ?: return null
        return when (command) {
            Elm327Command.ENGINE_LOAD -> ObdReading(
                "Engine load",
                bytes.first() * 100.0 / 255.0,
                "%",
            )

            Elm327Command.COOLANT_TEMPERATURE -> ObdReading(
                "Coolant",
                bytes.first() - 40.0,
                "°C",
            )

            Elm327Command.ENGINE_RPM -> ObdReading(
                "Engine speed",
                (bytes[0] * 256 + bytes[1]) / 4.0,
                "rpm",
            )

            Elm327Command.VEHICLE_SPEED -> ObdReading(
                "Vehicle speed",
                bytes.first().toDouble(),
                "km/h",
            )

            Elm327Command.FUEL_LEVEL -> ObdReading(
                "Fuel level",
                bytes.first() * 100.0 / 255.0,
                "%",
            )

            Elm327Command.CONTROL_MODULE_VOLTAGE -> ObdReading(
                "Battery voltage",
                (bytes[0] * 256 + bytes[1]) / 1000.0,
                "V",
            )

            else -> null
        }
    }

    fun troubleCodes(response: String): List<String> {
        val bytes = responseBytes(response)
        val headerIndex = bytes.indexOf(0x43)
        if (headerIndex < 0) return emptyList()
        return bytes.drop(headerIndex + 1)
            .chunked(2)
            .takeWhile { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { decodeTroubleCode(it[0], it[1]) }
    }

    private fun dataBytes(command: Elm327Command, response: String): List<Int>? {
        if (!command.wireValue.startsWith("01") || command.wireValue.length != 4) return null
        val bytes = responseBytes(response)
        val pid = command.wireValue.takeLast(2).toInt(16)
        val headerIndex = bytes.indices.firstOrNull { index ->
            index + 1 < bytes.size && bytes[index] == 0x41 && bytes[index + 1] == pid
        } ?: return null
        val required = if (
            command == Elm327Command.ENGINE_RPM ||
            command == Elm327Command.CONTROL_MODULE_VOLTAGE
        ) {
            2
        } else {
            1
        }
        return bytes.drop(headerIndex + 2).take(required).takeIf { it.size == required }
    }

    private fun responseBytes(response: String): List<Int> {
        if ("NO DATA" in response.uppercase()) return emptyList()
        return Regex("(?i)(?<![0-9A-F])[0-9A-F]{2}(?![0-9A-F])")
            .findAll(response)
            .map { it.value.toInt(16) }
            .toList()
    }

    private fun decodeTroubleCode(first: Int, second: Int): String {
        val family = "PCBU"[(first shr 6) and 0x03]
        return buildString {
            append(family)
            append((first shr 4) and 0x03)
            append((first and 0x0F).toString(16).uppercase())
            append(((second shr 4) and 0x0F).toString(16).uppercase())
            append((second and 0x0F).toString(16).uppercase())
        }
    }
}

internal object Elm327Simulator {
    fun response(command: Elm327Command): String = when (command) {
        Elm327Command.DISABLE_ECHO -> "OK\r>"
        Elm327Command.AUTOMATIC_PROTOCOL -> "OK\r>"
        Elm327Command.SUPPORTED_PIDS -> "41 00 BE 3F A8 13\r>"
        Elm327Command.ENGINE_LOAD -> "41 04 66\r>"
        Elm327Command.COOLANT_TEMPERATURE -> "41 05 7B\r>"
        Elm327Command.ENGINE_RPM -> "41 0C 1A F8\r>"
        Elm327Command.VEHICLE_SPEED -> "41 0D 28\r>"
        Elm327Command.FUEL_LEVEL -> "41 2F 19\r>"
        Elm327Command.CONTROL_MODULE_VOLTAGE -> "41 42 36 B0\r>"
        Elm327Command.READ_TROUBLE_CODES -> "43 01 33 00 00\r>"
    }
}
