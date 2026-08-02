package io.github.priencelucifer.michisonae

import android.content.Context
import android.util.AtomicFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

internal class OfflineObservationQueue(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("pending-road-observations.json"))

    fun enqueue(observation: RoadObservationDraft): Boolean = lock.withLock {
        if (!acceptingObservations) return@withLock false
        val pending = read()
        val updated = appendUniqueObservation(pending, observation) ?: return@withLock false
        if (updated === pending) return@withLock true
        write(updated)
        true
    }

    fun pending(limit: Int = 100): List<RoadObservationDraft> = lock.withLock {
        require(limit in 1..100)
        read().take(limit)
    }

    fun pendingCount(): Int = lock.withLock { read().size }

    fun clearAll() = lock.withLock {
        acceptingObservations = false
        write(emptyList())
    }

    fun acknowledgeAfterDurableAcceptance(
        eventIds: Set<String>,
        outcome: UploadOutcome,
    ) = lock.withLock {
        val acknowledged = acknowledgedEventIds(outcome, eventIds)
        if (acknowledged.isNotEmpty()) {
            write(
                removeFirstMatchingObservations(read(), acknowledged),
                allowOversizedDrain = true,
            )
        }
    }

    fun discardPermanentlyRejected(eventId: String) = lock.withLock {
        write(
            removeFirstMatchingObservations(read(), setOf(eventId)),
            allowOversizedDrain = true,
        )
    }

    private fun read(): List<RoadObservationDraft> {
        if (!file.baseFile.exists()) return emptyList()
        val decoded = file.openRead().use { input ->
            decodeObservationQueue(input.readUtf8AtMost(MAX_LEGACY_OBSERVATION_QUEUE_BYTES))
        }
        if (decoded.needsRewrite) {
            write(decoded.observations, allowOversizedDrain = true)
        }
        return decoded.observations
    }

    private fun write(
        observations: List<RoadObservationDraft>,
        allowOversizedDrain: Boolean = false,
    ) {
        val encoded = encodeObservationQueue(observations).toByteArray(Charsets.UTF_8)
        val maximumBytes = if (allowOversizedDrain) {
            MAX_LEGACY_OBSERVATION_QUEUE_BYTES
        } else {
            MAX_OBSERVATION_QUEUE_BYTES
        }
        require(encoded.size <= maximumBytes) {
            "Observation queue reached its local storage limit"
        }
        val output = file.startWrite()
        try {
            output.write(encoded)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = ReentrantLock()

        @Volatile
        private var acceptingObservations = true

        fun resumeAcceptingObservations() {
            acceptingObservations = true
        }
    }
}

private const val OBSERVATION_QUEUE_SCHEMA_VERSION = 2
internal const val MAX_PENDING_OBSERVATIONS = 10_000
private const val MAX_OBSERVATION_QUEUE_BYTES = 8 * 1_024 * 1_024
private const val MAX_LEGACY_OBSERVATION_QUEUE_BYTES = 64 * 1_024 * 1_024

internal data class DecodedObservationQueue(
    val observations: List<RoadObservationDraft>,
    val needsRewrite: Boolean,
)

internal fun appendUniqueObservation(
    observations: List<RoadObservationDraft>,
    observation: RoadObservationDraft,
): List<RoadObservationDraft>? {
    val existing = observations.firstOrNull { it.eventId == observation.eventId }
    return when {
        existing == observation -> observations
        existing != null || observations.size >= MAX_PENDING_OBSERVATIONS -> null
        else -> observations + observation
    }
}

internal fun removeFirstMatchingObservations(
    observations: List<RoadObservationDraft>,
    eventIds: Set<String>,
): List<RoadObservationDraft> {
    val remaining = eventIds.toMutableSet()
    return observations.filter { observation ->
        observation.eventId !in remaining || !remaining.remove(observation.eventId)
    }
}

internal fun encodeObservationQueue(observations: List<RoadObservationDraft>): String =
    buildString {
        append(JSONObject().put("schema_version", OBSERVATION_QUEUE_SCHEMA_VERSION))
        append('\n')
        observations.forEach {
            append(it.toStoredJson())
            append('\n')
        }
    }

internal fun decodeObservationQueue(
    serialized: String,
    decodeRecord: (String) -> RoadObservationDraft? = { record ->
        runCatching { RoadObservationDraft.fromStoredJson(JSONObject(record)) }.getOrNull()
    },
): DecodedObservationQueue {
    val trimmed = serialized.trim()
    if (trimmed.isEmpty()) return DecodedObservationQueue(emptyList(), needsRewrite = true)
    if (trimmed.startsWith("[")) {
        val records = completeJsonObjects(trimmed)
        val observations = records.mapNotNull(decodeRecord)
        return DecodedObservationQueue(
            observations = observations,
            needsRewrite = true,
        )
    }

    val lines = serialized.lineSequence().filter { it.isNotBlank() }.toList()
    val schemaVersion = Regex("\"schema_version\"\\s*:\\s*(\\d+)")
        .find(lines.first())
        ?.groupValues
        ?.get(1)
        ?.toInt()
    if (schemaVersion != null) {
        require(schemaVersion == OBSERVATION_QUEUE_SCHEMA_VERSION) {
            "Unsupported observation queue schema"
        }
    }
    val records = if (schemaVersion == OBSERVATION_QUEUE_SCHEMA_VERSION) {
        lines.drop(1)
    } else {
        lines
    }
    val observations = records.mapNotNull(decodeRecord)
    return DecodedObservationQueue(
        observations = observations,
        needsRewrite = schemaVersion != OBSERVATION_QUEUE_SCHEMA_VERSION ||
            observations.size != records.size,
    )
}

private fun completeJsonObjects(serialized: String): List<String> {
    val objects = mutableListOf<String>()
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    serialized.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }

                '}' -> if (depth > 0) {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        objects += serialized.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
    }
    return objects
}
