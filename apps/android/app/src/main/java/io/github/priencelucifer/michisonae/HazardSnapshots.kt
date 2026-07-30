package io.github.priencelucifer.michisonae

import android.content.Context
import android.util.AtomicFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

internal enum class PublicHazardKind(val wireName: String) {
    ROAD_DAMAGE("road_damage"),
    ROUGH_ROAD("rough_road"),
    ;

    companion object {
        fun fromWireName(value: String): PublicHazardKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unknown public hazard kind")
    }
}

internal data class PublicRoadHazard(
    val id: String,
    val kind: PublicHazardKind,
    val latitude: Double,
    val longitude: Double,
    val severity: Double,
    val confidence: Double,
    val contributorCount: Int,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{24}")))
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        require(severity in 0.0..1.0)
        require(confidence in 0.0..1.0)
        require(contributorCount >= 2)
    }
}

internal data class RegionalHazardSnapshot(
    val regionId: String,
    val version: String?,
    val generatedAtMillis: Long?,
    val hazards: List<PublicRoadHazard>,
) {
    init {
        val region = Regex("gh([3-8]):([0123456789bcdefghjkmnpqrstuvwxyz]{3,8})")
            .matchEntire(regionId)
        require(region != null && region.groupValues[1].toInt() == region.groupValues[2].length)
        require(version == null || version.matches(Regex("[0-9a-f]{64}")))
        require(generatedAtMillis == null || generatedAtMillis >= 0)
    }
}

internal fun parseRegionalHazardSnapshot(serialized: String): RegionalHazardSnapshot {
    val root = JSONObject(serialized)
    require(root.getString("schema_version") == "1.0")
    val hazardsJson = root.getJSONArray("hazards")
    require(root.getInt("hazard_count") == hazardsJson.length())
    val hazards = List(hazardsJson.length()) { index ->
        val hazard = hazardsJson.getJSONObject(index)
        PublicRoadHazard(
            id = hazard.getString("hazard_id"),
            kind = PublicHazardKind.fromWireName(hazard.getString("kind")),
            latitude = hazard.getDouble("latitude"),
            longitude = hazard.getDouble("longitude"),
            severity = hazard.getDouble("severity"),
            confidence = hazard.getDouble("confidence"),
            contributorCount = hazard.getInt("contributor_count"),
        )
    }
    return RegionalHazardSnapshot(
        regionId = root.getString("region_id"),
        version = root.nullableString("version"),
        generatedAtMillis = root.nullableString("generated_at")
            ?.let { Instant.parse(it).toEpochMilli() },
        hazards = hazards,
    )
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

internal fun regionalHazardId(
    latitude: Double,
    longitude: Double,
    precision: Int = 5,
): String {
    require(latitude in -90.0..90.0)
    require(longitude in -180.0..180.0)
    require(precision in 3..8)
    val alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
    var latLow = -90.0
    var latHigh = 90.0
    var lonLow = -180.0
    var lonHigh = 180.0
    var useLongitude = true
    var bitCount = 0
    var character = 0
    val cell = StringBuilder(precision)
    while (cell.length < precision) {
        val value = if (useLongitude) longitude else latitude
        val middle = if (useLongitude) {
            (lonLow + lonHigh) / 2
        } else {
            (latLow + latHigh) / 2
        }
        character = character shl 1
        if (value >= middle) {
            character += 1
            if (useLongitude) lonLow = middle else latLow = middle
        } else if (useLongitude) {
            lonHigh = middle
        } else {
            latHigh = middle
        }
        useLongitude = !useLongitude
        bitCount += 1
        if (bitCount == 5) {
            cell.append(alphabet[character])
            bitCount = 0
            character = 0
        }
    }
    return "gh$precision:$cell"
}

internal sealed interface HazardSnapshotDownload {
    data object NotModified : HazardSnapshotDownload

    data class Updated(
        val serialized: String,
        val snapshot: RegionalHazardSnapshot,
    ) : HazardSnapshotDownload
}

internal class HazardSnapshotClient(baseUrl: String) {
    private val baseUrl = baseUrl.trimEnd('/').also {
        val uri = URI(it)
        require(
            uri.scheme == "https" ||
                (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")),
        ) {
            "The API must use HTTPS outside local development"
        }
    }

    fun download(regionId: String, knownVersion: String?): HazardSnapshotDownload {
        require(RegionalHazardSnapshot(regionId, knownVersion, null, emptyList()).regionId == regionId)
        val connection =
            (URL("$baseUrl/v1/regions/$regionId/hazards").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    knownVersion?.let { setRequestProperty("If-None-Match", "\"$it\"") }
                }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> HazardSnapshotDownload.NotModified
                HttpURLConnection.HTTP_OK -> {
                    val serialized = connection.inputStream.bufferedReader().use { it.readText() }
                    val snapshot = parseRegionalHazardSnapshot(serialized)
                    require(snapshot.regionId == regionId)
                    HazardSnapshotDownload.Updated(serialized, snapshot)
                }

                else -> throw HazardSnapshotUnavailable(status)
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal class HazardSnapshotCache(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("nearby-hazard-snapshot.json"))

    fun read(): RegionalHazardSnapshot? = lock.withLock {
        if (!file.baseFile.exists()) return null
        runCatching {
            file.openRead().bufferedReader().use { parseRegionalHazardSnapshot(it.readText()) }
        }.getOrNull()
    }

    fun replace(serialized: String): RegionalHazardSnapshot {
        val snapshot = parseRegionalHazardSnapshot(serialized)
        lock.withLock {
            val output = file.startWrite()
            try {
                output.write(serialized.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
        return snapshot
    }

    fun clear() = lock.withLock { file.delete() }

    companion object {
        private val lock = ReentrantLock()
    }
}

internal data class HazardRefreshResult(
    val snapshot: RegionalHazardSnapshot?,
    val changed: Boolean,
    val error: Boolean,
)

internal class NearbyHazardSnapshots(
    context: Context,
    baseUrl: String,
) {
    private val cache = HazardSnapshotCache(context)
    private val client = HazardSnapshotClient(baseUrl)

    fun cached(): RegionalHazardSnapshot? = cache.read()

    fun refresh(latitude: Double, longitude: Double): HazardRefreshResult {
        val existing = cache.read()
        val regionId = regionalHazardId(latitude, longitude)
        return try {
            when (
                val result = client.download(
                    regionId,
                    existing?.takeIf { it.regionId == regionId }?.version,
                )
            ) {
                HazardSnapshotDownload.NotModified ->
                    HazardRefreshResult(existing, changed = false, error = false)

                is HazardSnapshotDownload.Updated ->
                    HazardRefreshResult(
                        cache.replace(result.serialized),
                        changed = true,
                        error = false,
                    )
            }
        } catch (_: Exception) {
            HazardRefreshResult(existing, changed = false, error = true)
        }
    }

    fun clear() = cache.clear()
}

internal class HazardSnapshotUnavailable(val statusCode: Int) :
    Exception("Hazard snapshot request failed")
