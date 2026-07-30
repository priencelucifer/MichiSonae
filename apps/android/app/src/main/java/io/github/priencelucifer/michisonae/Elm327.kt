package io.github.priencelucifer.michisonae

internal enum class Elm327Command(
    val wireValue: String,
    val displayName: String,
) {
    DISABLE_ECHO("ATE0", "Disable adapter echo"),
    AUTOMATIC_PROTOCOL("ATSP0", "Automatic protocol"),
    SUPPORTED_PIDS("0100", "Supported values 01-20"),
    SUPPORTED_PIDS_21_TO_40("0120", "Supported values 21-40"),
    SUPPORTED_PIDS_41_TO_60("0140", "Supported values 41-60"),
    ENGINE_LOAD("0104", "Engine load"),
    COOLANT_TEMPERATURE("0105", "Coolant temperature"),
    ENGINE_RPM("010C", "Engine speed"),
    VEHICLE_SPEED("010D", "Vehicle speed"),
    FUEL_LEVEL("012F", "Fuel level"),
    CONTROL_MODULE_VOLTAGE("0142", "Battery voltage"),
    READ_TROUBLE_CODES("03", "Diagnostic trouble codes"),
}

internal val Elm327Command.mode01Pid: Int?
    get() = wireValue
        .takeIf { it.length == 4 && it.startsWith("01") }
        ?.takeLast(2)
        ?.toInt(16)

internal fun Elm327Command.isAllowedReadOnlyCommand(): Boolean =
    this == Elm327Command.DISABLE_ECHO ||
        this == Elm327Command.AUTOMATIC_PROTOCOL ||
        wireValue == "03" ||
        mode01Pid != null

internal data class ObdReading(
    val label: String,
    val value: Double,
    val unit: String,
)

internal enum class Elm327ResponseStatus {
    DATA,
    OK,
    NO_DATA,
    STOPPED,
    UNABLE_TO_CONNECT,
    INVALID,
}

internal data class Elm327Response(
    val status: Elm327ResponseStatus,
    val bytes: List<Int> = emptyList(),
    val payloads: List<List<Int>> = emptyList(),
)

internal object Elm327Parser {
    private const val MAX_RESPONSE_CHARS = 8_192

    fun response(response: String): Elm327Response {
        if (response.length > MAX_RESPONSE_CHARS) {
            return Elm327Response(Elm327ResponseStatus.INVALID)
        }
        val normalized = response.uppercase()
        val adapterStatus = when {
            "UNABLE TO CONNECT" in normalized -> Elm327ResponseStatus.UNABLE_TO_CONNECT
            "STOPPED" in normalized -> Elm327ResponseStatus.STOPPED
            "NO DATA" in normalized -> Elm327ResponseStatus.NO_DATA
            else -> null
        }
        if (adapterStatus != null) return Elm327Response(adapterStatus)

        val frames = normalized
            .replace(Regex("SEARCHING\\.{0,3}"), "")
            .replace(">", "\n")
            .lineSequence()
            .mapNotNull(::frame)
            .toList()
        val payloads = reassemble(frames)
        val bytes = payloads.flatten()
        return when {
            bytes.isNotEmpty() -> Elm327Response(
                Elm327ResponseStatus.DATA,
                bytes,
                payloads,
            )

            Regex("(^|[\\r\\n])\\s*OK\\s*([\\r\\n]|$)").containsMatchIn(normalized) ->
                Elm327Response(Elm327ResponseStatus.OK)

            else -> Elm327Response(Elm327ResponseStatus.INVALID)
        }
    }

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

    fun reading(
        command: Elm327Command,
        response: String,
        supportedPids: Set<Int>,
    ): ObdReading? {
        val pid = command.mode01Pid ?: return null
        return reading(command, response).takeIf { pid in supportedPids }
    }

    fun supportedPids(command: Elm327Command, response: String): Set<Int> {
        val basePid = when (command) {
            Elm327Command.SUPPORTED_PIDS -> 0x00
            Elm327Command.SUPPORTED_PIDS_21_TO_40 -> 0x20
            Elm327Command.SUPPORTED_PIDS_41_TO_60 -> 0x40
            else -> return emptySet()
        }
        val bitmap = dataBytes(command, response, required = 4) ?: return emptySet()
        val bits = bitmap.fold(0L) { result, byte -> (result shl 8) or byte.toLong() }
        return (1..32)
            .filterTo(mutableSetOf()) { offset ->
                bits and (1L shl (32 - offset)) != 0L
            }
            .mapTo(mutableSetOf()) { offset -> basePid + offset }
    }

    fun troubleCodes(response: String): List<String> {
        return response(response).payloads.flatMap { payload ->
            val headerIndex = payload.indexOf(0x43)
            if (headerIndex < 0) {
                emptyList()
            } else {
                payload.drop(headerIndex + 1)
                    .chunked(2)
                    .takeWhile { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                    .map { decodeTroubleCode(it[0], it[1]) }
            }
        }
    }

    private fun dataBytes(
        command: Elm327Command,
        response: String,
        required: Int? = null,
    ): List<Int>? {
        val pid = command.mode01Pid ?: return null
        val byteCount = required ?: when (command) {
            Elm327Command.ENGINE_RPM,
            Elm327Command.CONTROL_MODULE_VOLTAGE,
            -> 2

            else -> 1
        }
        return response(response).payloads.firstNotNullOfOrNull { payload ->
            val headerIndex = payload.indices.firstOrNull { index ->
                index + 1 < payload.size &&
                    payload[index] == 0x41 &&
                    payload[index + 1] == pid
            } ?: return@firstNotNullOfOrNull null
            payload.drop(headerIndex + 2)
                .take(byteCount)
                .takeIf { it.size == byteCount }
        }
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

    private data class Frame(
        val source: String,
        val bytes: List<Int>,
    )

    private data class Assembly(
        val order: Int,
        val size: Int,
        var nextSequence: Int,
        val bytes: MutableList<Int>,
    )

    private fun frame(line: String): Frame? {
        val clean = line.trim()
        if (
            clean.isEmpty() ||
            clean == "OK" ||
            clean == "03" ||
            clean.matches(Regex("AT[A-Z0-9]+")) ||
            clean.matches(Regex("01[0-9A-F]{2}"))
        ) {
            return null
        }

        val tokens = clean.split(Regex("\\s+")).filter(String::isNotEmpty)
        val spacedHeader = tokens.firstOrNull()?.takeIf {
            tokens.size > 1 &&
                (it.matches(Regex("[0-9A-F]{3}")) ||
                    it.matches(Regex("[0-9A-F]{8}")))
        }
        var compact = tokens.drop(if (spacedHeader == null) 0 else 1)
            .joinToString("")
            .filter { it in '0'..'9' || it in 'A'..'F' }
        val compactHeader = when {
            compact.length >= 5 &&
                compact.length % 2 == 1 &&
                compact.take(3).matches(Regex("[0-9A-F]{3}")) -> compact.take(3)

            compact.length >= 12 &&
                compact.length % 2 == 0 &&
                compact.startsWith("18") -> compact.take(8)

            else -> null
        }
        if (compactHeader != null) compact = compact.drop(compactHeader.length)
        if (compact.length % 2 != 0) return null
        val bytes = compact
            .chunked(2)
            .mapNotNull { it.takeIf { pair -> pair.length == 2 }?.toIntOrNull(16) }
        return bytes.takeIf { it.isNotEmpty() }?.let {
            Frame(spacedHeader ?: compactHeader ?: "NO_HEADER", it)
        }
    }

    private fun reassemble(frames: List<Frame>): List<List<Int>> {
        val pending = mutableMapOf<String, Assembly>()
        val complete = mutableListOf<Pair<Int, List<Int>>>()
        frames.forEachIndexed { order, frame ->
            val first = frame.bytes.first()
            when (first shr 4) {
                0 -> {
                    pending.remove(frame.source)
                    val size = first and 0x0F
                    if (size <= frame.bytes.size - 1) {
                        complete += order to frame.bytes.drop(1).take(size)
                    }
                }

                1 -> if (frame.bytes.size >= 2) {
                    val size = ((first and 0x0F) shl 8) or frame.bytes[1]
                    val data = frame.bytes.drop(2).take(size).toMutableList()
                    if (data.size == size) {
                        complete += order to data
                    } else {
                        pending[frame.source] = Assembly(order, size, 1, data)
                    }
                }

                2 -> {
                    val assembly = pending[frame.source]
                    val sequence = first and 0x0F
                    if (assembly != null && sequence == assembly.nextSequence) {
                        assembly.bytes += frame.bytes.drop(1)
                        assembly.nextSequence = (assembly.nextSequence + 1) and 0x0F
                        if (assembly.bytes.size >= assembly.size) {
                            complete += assembly.order to assembly.bytes.take(assembly.size)
                            pending.remove(frame.source)
                        }
                    } else {
                        pending.remove(frame.source)
                    }
                }

                else -> {
                    pending.remove(frame.source)
                    complete += order to frame.bytes
                }
            }
        }
        return complete.sortedBy { it.first }.map { it.second }
    }
}

internal object Elm327Simulator {
    fun response(command: Elm327Command): String = when (command) {
        Elm327Command.DISABLE_ECHO -> "OK\r>"
        Elm327Command.AUTOMATIC_PROTOCOL -> "OK\r>"
        Elm327Command.SUPPORTED_PIDS -> "41 00 BE 3F A8 13\r>"
        Elm327Command.SUPPORTED_PIDS_21_TO_40 -> "41 20 80 02 00 01\r>"
        Elm327Command.SUPPORTED_PIDS_41_TO_60 -> "41 40 40 00 00 00\r>"
        Elm327Command.ENGINE_LOAD -> "41 04 66\r>"
        Elm327Command.COOLANT_TEMPERATURE -> "41 05 7B\r>"
        Elm327Command.ENGINE_RPM -> "41 0C 1A F8\r>"
        Elm327Command.VEHICLE_SPEED -> "41 0D 28\r>"
        Elm327Command.FUEL_LEVEL -> "41 2F 19\r>"
        Elm327Command.CONTROL_MODULE_VOLTAGE -> "41 42 36 B0\r>"
        Elm327Command.READ_TROUBLE_CODES -> "43 01 33 00 00\r>"
    }
}
