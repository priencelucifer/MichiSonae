package io.github.priencelucifer.michisonae

import android.content.Context
import android.util.AtomicFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class DiagnosticCard(
    val finding: DiagnosticFinding,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(finding.code != "UNKNOWN")
        require(firstObservedAtEpochMillis >= 0)
        require(lastObservedAtEpochMillis >= firstObservedAtEpochMillis)
        require(expiresAtEpochMillis > lastObservedAtEpochMillis)
    }
}

internal class DiagnosticCardStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("diagnostic-cards.json"))

    fun read(nowEpochMillis: Long = System.currentTimeMillis()): List<DiagnosticCard> =
        lock.withLock {
            if (!file.baseFile.exists()) return@withLock emptyList()
            val decoded = runCatching {
                decodeDiagnosticCards(
                    file.openRead().use { it.readUtf8AtMost(MAX_DIAGNOSTIC_CARD_BYTES) },
                )
            }.getOrElse {
                write(emptyList())
                return@withLock emptyList()
            }
            val current = refreshDiagnosticCards(
                existing = decoded,
                activeCodes = emptyList(),
                observedAtEpochMillis = nowEpochMillis,
            )
            if (current != decoded) write(current)
            current
        }

    fun replace(cards: List<DiagnosticCard>) = lock.withLock {
        write(cards)
    }

    fun update(
        nowEpochMillis: Long = System.currentTimeMillis(),
        transform: (List<DiagnosticCard>) -> List<DiagnosticCard>,
    ): List<DiagnosticCard> = lock.withLock {
        val updated = transform(read(nowEpochMillis))
        write(updated)
        updated
    }

    fun clear() = lock.withLock {
        if (file.baseFile.exists()) file.delete()
    }

    private fun write(cards: List<DiagnosticCard>) {
        val encoded = encodeDiagnosticCards(cards).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_DIAGNOSTIC_CARD_BYTES)
        val output = file.startWrite()
        try {
            output.write(encoded)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private companion object {
        val lock = ReentrantLock()
    }
}

internal fun refreshDiagnosticCards(
    existing: List<DiagnosticCard>,
    activeCodes: Iterable<String>,
    observedAtEpochMillis: Long,
    retentionMillis: Long = DEFAULT_DIAGNOSTIC_RETENTION_MILLIS,
): List<DiagnosticCard> {
    require(observedAtEpochMillis >= 0)
    require(retentionMillis in 1..MAX_DIAGNOSTIC_RETENTION_MILLIS)
    val expiresAt = if (Long.MAX_VALUE - observedAtEpochMillis < retentionMillis) {
        Long.MAX_VALUE
    } else {
        observedAtEpochMillis + retentionMillis
    }
    val previous = existing
        .asSequence()
        .filter { it.expiresAtEpochMillis > observedAtEpochMillis }
        .associateBy { it.finding.code }
        .toMutableMap()

    activeCodes
        .asSequence()
        .map(DiagnosticPolicy::interpret)
        .filter { it.code != "UNKNOWN" }
        .distinctBy(DiagnosticFinding::code)
        .take(MAX_DIAGNOSTIC_CARDS)
        .forEach { finding ->
            val old = previous[finding.code]
            previous[finding.code] = DiagnosticCard(
                finding = finding,
                firstObservedAtEpochMillis = old?.firstObservedAtEpochMillis
                    ?: observedAtEpochMillis,
                lastObservedAtEpochMillis = observedAtEpochMillis,
                expiresAtEpochMillis = expiresAt,
            )
        }

    return previous.values
        .sortedWith(
            compareByDescending<DiagnosticCard> { it.finding.severity.ordinal }
                .thenByDescending(DiagnosticCard::lastObservedAtEpochMillis)
                .thenBy { it.finding.code },
        )
        .take(MAX_DIAGNOSTIC_CARDS)
}

internal fun deleteDiagnosticCard(
    cards: List<DiagnosticCard>,
    code: String,
): List<DiagnosticCard> {
    val normalized = DiagnosticPolicy.interpret(code).code
    return if (normalized == "UNKNOWN") cards else cards.filterNot {
        it.finding.code == normalized
    }
}

internal fun encodeDiagnosticCards(cards: List<DiagnosticCard>): String = JSONObject()
    .put("schema_version", DIAGNOSTIC_CARD_SCHEMA_VERSION)
    .put(
        "cards",
        JSONArray().also { array ->
            cards.take(MAX_DIAGNOSTIC_CARDS).forEach { card ->
                array.put(
                    JSONObject()
                        .put("code", card.finding.code)
                        .put("first_observed_at", card.firstObservedAtEpochMillis)
                        .put("last_observed_at", card.lastObservedAtEpochMillis)
                        .put("expires_at", card.expiresAtEpochMillis),
                )
            }
        },
    )
    .toString()

internal fun decodeDiagnosticCards(serialized: String): List<DiagnosticCard> {
    val root = JSONObject(serialized)
    validateDiagnosticCardSchema(root.getInt("schema_version"))
    val cards = root.getJSONArray("cards")
    return buildList {
        repeat(minOf(cards.length(), MAX_DIAGNOSTIC_CARDS)) { index ->
            val decoded = runCatching {
                val item = cards.getJSONObject(index)
                DiagnosticCard(
                    finding = DiagnosticPolicy.interpret(item.getString("code")),
                    firstObservedAtEpochMillis = item.getLong("first_observed_at"),
                    lastObservedAtEpochMillis = item.getLong("last_observed_at"),
                    expiresAtEpochMillis = item.getLong("expires_at"),
                )
            }.getOrNull()
            if (decoded != null) add(decoded)
        }
    }
}

internal fun validateDiagnosticCardSchema(schemaVersion: Int) {
    require(schemaVersion == DIAGNOSTIC_CARD_SCHEMA_VERSION) {
        "Unsupported diagnostic card schema"
    }
}

private const val DIAGNOSTIC_CARD_SCHEMA_VERSION = 1
private const val MAX_DIAGNOSTIC_CARD_BYTES = 64 * 1_024
private const val MAX_DIAGNOSTIC_CARDS = 64
private const val DEFAULT_DIAGNOSTIC_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
private const val MAX_DIAGNOSTIC_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1_000
