package io.github.priencelucifer.michisonae

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_HAZARD_SNAPSHOT_BYTES = 1_048_576
internal const val MAX_HAZARDS_PER_SNAPSHOT = 5_000
internal const val MAX_CACHED_HAZARD_REGIONS = 9

private val REGION_ID_PATTERN =
    Regex("gh([3-8]):([0123456789bcdefghjkmnpqrstuvwxyz]{3,8})")

internal enum class PublicHazardKind(
    val wireName: String,
    val warningLabel: String,
) {
    ROAD_DAMAGE("road_damage", "Road damage"),
    ROUGH_ROAD("rough_road", "Rough road"),
    OBSTRUCTION("obstruction", "Road obstruction"),
    FLOODING("flooding", "Flooding"),
    MANHOLE_HAZARD("manhole_hazard", "Manhole hazard"),
    ROAD_CONSTRUCTION("road_construction", "Road construction"),
    DISABLED_VEHICLE("disabled_vehicle", "Disabled vehicle"),
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
        val region = REGION_ID_PATTERN.matchEntire(regionId)
        require(region != null && region.groupValues[1].toInt() == region.groupValues[2].length)
        require(version == null || version.matches(Regex("[0-9a-f]{64}")))
        require(generatedAtMillis == null || generatedAtMillis >= 0)
        require(hazards.size <= MAX_HAZARDS_PER_SNAPSHOT)
    }
}

internal fun parseRegionalHazardSnapshot(serialized: String): RegionalHazardSnapshot {
    require(serialized.length <= MAX_HAZARD_SNAPSHOT_BYTES)
    require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_HAZARD_SNAPSHOT_BYTES)
    val root = JSONObject(serialized)
    require(root.getString("schema_version") == "1.0")
    val hazardsJson = root.getJSONArray("hazards")
    val hazardCount = root.getInt("hazard_count")
    require(hazardCount in 0..MAX_HAZARDS_PER_SNAPSHOT)
    require(hazardCount == hazardsJson.length())
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

internal fun adjacentRegionalHazardIds(regionId: String): List<String> {
    val match = requireNotNull(REGION_ID_PATTERN.matchEntire(regionId))
    val precision = match.groupValues[1].toInt()
    require(precision == match.groupValues[2].length)
    val bounds = decodeGeohashBounds(match.groupValues[2])
    val latitudeStep = bounds.latitudeHigh - bounds.latitudeLow
    val longitudeStep = bounds.longitudeHigh - bounds.longitudeLow
    val latitude = (bounds.latitudeLow + bounds.latitudeHigh) / 2
    val longitude = (bounds.longitudeLow + bounds.longitudeHigh) / 2
    return listOf(
        0 to 0,
        1 to 0,
        -1 to 0,
        0 to 1,
        0 to -1,
        1 to 1,
        1 to -1,
        -1 to 1,
        -1 to -1,
    ).map { (latitudeOffset, longitudeOffset) ->
        regionalHazardId(
            latitude = (latitude + latitudeOffset * latitudeStep)
                .coerceIn(-90.0 + latitudeStep / 2, 90.0 - latitudeStep / 2),
            longitude = wrapLongitude(longitude + longitudeOffset * longitudeStep),
            precision = precision,
        )
    }.distinct()
}

private data class GeohashBounds(
    val latitudeLow: Double,
    val latitudeHigh: Double,
    val longitudeLow: Double,
    val longitudeHigh: Double,
)

private fun decodeGeohashBounds(cell: String): GeohashBounds {
    val alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
    var latitudeLow = -90.0
    var latitudeHigh = 90.0
    var longitudeLow = -180.0
    var longitudeHigh = 180.0
    var useLongitude = true
    cell.forEach { character ->
        val value = alphabet.indexOf(character)
        require(value >= 0)
        for (bit in 4 downTo 0) {
            val upperHalf = value and (1 shl bit) != 0
            if (useLongitude) {
                val middle = (longitudeLow + longitudeHigh) / 2
                if (upperHalf) longitudeLow = middle else longitudeHigh = middle
            } else {
                val middle = (latitudeLow + latitudeHigh) / 2
                if (upperHalf) latitudeLow = middle else latitudeHigh = middle
            }
            useLongitude = !useLongitude
        }
    }
    return GeohashBounds(latitudeLow, latitudeHigh, longitudeLow, longitudeHigh)
}

private fun wrapLongitude(longitude: Double): Double = when {
    longitude < -180.0 -> longitude + 360.0
    longitude >= 180.0 -> longitude - 360.0
    else -> longitude
}

internal class RegionalSnapshotRefreshGate(
    private val refreshIntervalMillis: Long = 15 * 60 * 1_000L,
    private val maxTrackedRegions: Int = 8,
) {
    private val lastRefreshByRegion = LinkedHashMap<String, Long>()

    @Volatile
    private var currentRegion: String? = null

    init {
        require(refreshIntervalMillis > 0)
        require(maxTrackedRegions > 0)
    }

    @Synchronized
    fun shouldRefresh(regionId: String, elapsedRealtimeMillis: Long): Boolean {
        require(regionId.isNotBlank())
        require(elapsedRealtimeMillis >= 0)
        currentRegion = regionId
        val previous = lastRefreshByRegion[regionId]
        if (
            previous != null &&
            elapsedRealtimeMillis >= previous &&
            elapsedRealtimeMillis - previous < refreshIntervalMillis
        ) {
            return false
        }
        lastRefreshByRegion[regionId] = elapsedRealtimeMillis
        while (lastRefreshByRegion.size > maxTrackedRegions) {
            lastRefreshByRegion.remove(lastRefreshByRegion.keys.first())
        }
        return true
    }

    fun isCurrent(regionId: String): Boolean = currentRegion == regionId

    @Synchronized
    fun storeIfCurrent(
        regionId: String,
        store: () -> RegionalHazardSnapshot,
    ): RegionalHazardSnapshot? =
        if (currentRegion == regionId) store() else null
}

internal sealed interface HazardSnapshotDownload {
    data object NotModified : HazardSnapshotDownload

    data class Updated(
        val serialized: String,
        val snapshot: RegionalHazardSnapshot,
    ) : HazardSnapshotDownload
}

internal class HazardSnapshotClient(baseUrl: String) {
    private val baseUrl = validatedApiBaseUrl(baseUrl)

    fun download(regionId: String, knownVersion: String?): HazardSnapshotDownload {
        require(RegionalHazardSnapshot(regionId, knownVersion, null, emptyList()).regionId == regionId)
        val connection =
            (URL("$baseUrl/v1/regions/$regionId/hazards").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    knownVersion?.let { setRequestProperty("If-None-Match", "\"$it\"") }
                }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> HazardSnapshotDownload.NotModified
                HttpURLConnection.HTTP_OK -> {
                    val serialized = connection.inputStream.use { it.readUtf8Bounded() }
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

private const val HAZARD_CACHE_INDEX_SCHEMA_VERSION = 1
private const val HAZARD_CACHE_INDEX_FILE = "hazard-snapshot-index.json"
private const val LEGACY_HAZARD_CACHE_FILE = "nearby-hazard-snapshot.json"
private const val HAZARD_CACHE_FILE_PREFIX = "hazard-snapshot-gh"

internal data class HazardCacheIndex(
    val currentRegionId: String?,
    val regions: List<String>,
) {
    init {
        require(regions.size <= MAX_CACHED_HAZARD_REGIONS)
        require(regions.distinct().size == regions.size)
        regions.forEach { requireNotNull(REGION_ID_PATTERN.matchEntire(it)) }
        require(currentRegionId == null || currentRegionId in regions)
    }
}

internal fun encodeHazardCacheIndex(index: HazardCacheIndex): String = JSONObject()
    .put("schema_version", HAZARD_CACHE_INDEX_SCHEMA_VERSION)
    .put("current_region_id", index.currentRegionId)
    .put("regions", JSONArray(index.regions))
    .toString()

internal fun validateHazardCacheIndexSchema(schemaVersion: Int) {
    if (schemaVersion > HAZARD_CACHE_INDEX_SCHEMA_VERSION) {
        throw UnsupportedHazardCacheSchema(schemaVersion)
    }
    require(schemaVersion == HAZARD_CACHE_INDEX_SCHEMA_VERSION) {
        "Unsupported hazard cache index schema"
    }
}

internal fun decodeHazardCacheIndex(serialized: String): HazardCacheIndex {
    val root = JSONObject(serialized)
    val schemaVersion = root.getInt("schema_version")
    validateHazardCacheIndexSchema(schemaVersion)
    val regionsJson = root.getJSONArray("regions")
    return HazardCacheIndex(
        currentRegionId = root.nullableString("current_region_id"),
        regions = List(regionsJson.length()) { regionsJson.getString(it) },
    )
}

internal fun hazardCacheIndexAfterWrite(
    existing: HazardCacheIndex,
    regionId: String,
    makeCurrent: Boolean,
    maxRegions: Int = MAX_CACHED_HAZARD_REGIONS,
): HazardCacheIndex {
    require(maxRegions in 1..MAX_CACHED_HAZARD_REGIONS)
    requireNotNull(REGION_ID_PATTERN.matchEntire(regionId))
    val current = if (makeCurrent || existing.currentRegionId == null) {
        regionId
    } else {
        existing.currentRegionId
    }
    val regions = existing.regions.toMutableList().apply {
        remove(regionId)
        add(regionId)
        while (size > maxRegions) {
            val evict = firstOrNull { it != current } ?: first()
            remove(evict)
        }
    }
    return HazardCacheIndex(current, regions)
}

internal fun hazardCacheIndexForLegacySnapshot(
    snapshot: RegionalHazardSnapshot,
): HazardCacheIndex = HazardCacheIndex(snapshot.regionId, listOf(snapshot.regionId))

internal fun shouldMigrateLegacyHazardCache(
    indexExists: Boolean,
    legacyExists: Boolean,
): Boolean = !indexExists && legacyExists

internal class HazardSnapshotCache(context: Context) {
    private val appContext = context.applicationContext
    private val directory = appContext.filesDir
    private val indexFile = AtomicFile(directory.resolve(HAZARD_CACHE_INDEX_FILE))
    private val legacyFile = AtomicFile(directory.resolve(LEGACY_HAZARD_CACHE_FILE))

    fun read(): RegionalHazardSnapshot? = lock.withLock {
        val index = try {
            readIndexLocked()
        } catch (_: UnsupportedHazardCacheSchema) {
            return null
        }
        index.currentRegionId?.let(::readSnapshotLocked)
    }

    fun read(regionId: String): RegionalHazardSnapshot? = lock.withLock {
        requireNotNull(REGION_ID_PATTERN.matchEntire(regionId))
        val index = try {
            readIndexLocked()
        } catch (_: UnsupportedHazardCacheSchema) {
            return null
        }
        if (regionId !in index.regions) return null
        readSnapshotLocked(regionId)
    }

    fun replace(
        serialized: String,
        makeCurrent: Boolean = true,
    ): RegionalHazardSnapshot {
        val snapshot = parseRegionalHazardSnapshot(serialized)
        lock.withLock {
            check(acceptingSnapshots) { "Hazard snapshot storage is disabled" }
            val oldIndex = readIndexLocked()
            writeAtomic(snapshotFile(snapshot.regionId), serialized)
            val newIndex = hazardCacheIndexAfterWrite(
                existing = oldIndex,
                regionId = snapshot.regionId,
                makeCurrent = makeCurrent,
            )
            writeIndexLocked(newIndex)
            (oldIndex.regions - newIndex.regions.toSet()).forEach {
                AtomicFile(snapshotFile(it)).delete()
            }
        }
        StatusChangeNotifier.notify(appContext)
        return snapshot
    }

    fun markCurrent(regionId: String) {
        val changed = lock.withLock {
            check(acceptingSnapshots) { "Hazard snapshot storage is disabled" }
            val oldIndex = readIndexLocked()
            if (regionId in oldIndex.regions) {
                writeIndexLocked(
                    hazardCacheIndexAfterWrite(
                        existing = oldIndex,
                        regionId = regionId,
                        makeCurrent = true,
                    ),
                )
                true
            } else {
                false
            }
        }
        if (changed) StatusChangeNotifier.notify(appContext)
    }

    fun clear() {
        lock.withLock {
            acceptingSnapshots = false
            directory.listFiles()
                ?.filter { it.name.startsWith(HAZARD_CACHE_FILE_PREFIX) }
                ?.forEach { AtomicFile(it).delete() }
            legacyFile.delete()
            indexFile.delete()
        }
        StatusChangeNotifier.notify(appContext)
    }

    private fun readIndexLocked(): HazardCacheIndex {
        migrateLegacyLocked()
        val input = try {
            indexFile.openRead()
        } catch (_: FileNotFoundException) {
            return recoverIndexLocked()
        }
        return try {
            input.use { decodeHazardCacheIndex(it.readUtf8Bounded()) }
        } catch (unsupported: UnsupportedHazardCacheSchema) {
            throw unsupported
        } catch (_: Exception) {
            recoverIndexLocked()
        }
    }

    private fun migrateLegacyLocked() {
        if (
            !shouldMigrateLegacyHazardCache(
                indexExists = indexFile.baseFile.exists(),
                legacyExists = legacyFile.baseFile.exists(),
            )
        ) {
            return
        }
        val input = try {
            legacyFile.openRead()
        } catch (_: FileNotFoundException) {
            return
        }
        val serialized = runCatching { input.use { it.readUtf8Bounded() } }.getOrNull()
            ?: return
        val snapshot = runCatching { parseRegionalHazardSnapshot(serialized) }.getOrNull()
            ?: return
        writeAtomic(snapshotFile(snapshot.regionId), serialized)
        writeIndexLocked(hazardCacheIndexForLegacySnapshot(snapshot))
        legacyFile.delete()
    }

    private fun recoverIndexLocked(): HazardCacheIndex {
        val snapshots = directory.listFiles()
            ?.filter {
                it.name.startsWith(HAZARD_CACHE_FILE_PREFIX) &&
                    it.name.endsWith(".json")
            }
            ?.mapNotNull { candidate ->
                runCatching {
                    val snapshot = AtomicFile(candidate).openRead().use {
                        parseRegionalHazardSnapshot(it.readUtf8Bounded())
                    }
                    candidate.lastModified() to snapshot.regionId
                }.getOrNull()
            }
            ?.sortedBy { it.first }
            ?.map { it.second }
            ?.distinct()
            ?.takeLast(MAX_CACHED_HAZARD_REGIONS)
            .orEmpty()
        val recovered = HazardCacheIndex(snapshots.lastOrNull(), snapshots)
        if (recovered.regions.isNotEmpty() && acceptingSnapshots) writeIndexLocked(recovered)
        return recovered
    }

    private fun readSnapshotLocked(regionId: String): RegionalHazardSnapshot? =
        runCatching {
            AtomicFile(snapshotFile(regionId)).openRead().use {
                parseRegionalHazardSnapshot(it.readUtf8Bounded())
            }.also { require(it.regionId == regionId) }
        }.getOrNull()

    private fun snapshotFile(regionId: String): File =
        directory.resolve("hazard-snapshot-${regionId.replace(':', '-')}.json")

    private fun writeIndexLocked(index: HazardCacheIndex) =
        writeAtomic(indexFile.baseFile, encodeHazardCacheIndex(index))

    private fun writeAtomic(destination: File, serialized: String) {
        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()
        try {
            output.write(serialized.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = ReentrantLock()

        @Volatile
        private var acceptingSnapshots = true

        fun resumeAcceptingSnapshots() = lock.withLock {
            acceptingSnapshots = true
        }
    }
}

internal class UnsupportedHazardCacheSchema(version: Int) :
    IllegalArgumentException("Unsupported hazard cache index schema $version")

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

    fun refresh(
        latitude: Double,
        longitude: Double,
        refreshGate: RegionalSnapshotRefreshGate? = null,
    ): HazardRefreshResult {
        val regionId = regionalHazardId(latitude, longitude)
        val existing = cache.read(regionId)
        return try {
            val result = when (
                val result = client.download(
                    regionId,
                    existing?.version,
                )
            ) {
                HazardSnapshotDownload.NotModified -> {
                    if (refreshGate == null || refreshGate.isCurrent(regionId)) {
                        cache.markCurrent(regionId)
                    }
                    HazardRefreshResult(existing, changed = false, error = false)
                }

                is HazardSnapshotDownload.Updated -> {
                    val stored = refreshGate?.storeIfCurrent(regionId) {
                        cache.replace(result.serialized)
                    } ?: if (refreshGate == null) {
                        cache.replace(result.serialized)
                    } else {
                        null
                    }
                    HazardRefreshResult(
                        snapshot = stored ?: cache.read(),
                        changed = stored != null,
                        error = false,
                    )
                }
            }
            if (
                result.snapshot != null &&
                (refreshGate == null || refreshGate.isCurrent(regionId))
            ) {
                prefetchAdjacent(regionId)
            }
            result
        } catch (_: Exception) {
            HazardRefreshResult(cache.read(regionId), changed = false, error = true)
        }
    }

    fun clear() = cache.clear()

    private fun prefetchAdjacent(regionId: String) {
        adjacentRegionalHazardIds(regionId).drop(1).forEach { adjacent ->
            runCatching {
                when (
                    val result = client.download(
                        adjacent,
                        cache.read(adjacent)?.version,
                    )
                ) {
                    HazardSnapshotDownload.NotModified -> Unit
                    is HazardSnapshotDownload.Updated ->
                        cache.replace(result.serialized, makeCurrent = false)
                }
            }
        }
    }
}

internal class HazardSnapshotUnavailable(val statusCode: Int) :
    Exception("Hazard snapshot request failed")

private fun InputStream.readUtf8Bounded(): String {
    return readUtf8AtMost(MAX_HAZARD_SNAPSHOT_BYTES)
}
