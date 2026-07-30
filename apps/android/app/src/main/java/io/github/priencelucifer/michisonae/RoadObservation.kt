package io.github.priencelucifer.michisonae

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class RoadObservationDraft(
    val eventId: String = UUID.randomUUID().toString(),
    val detectedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val locationAccuracyMetres: Double,
    val speedMetresPerSecond: Double,
    val kind: ObservationKind,
    val severity: Double,
    val confidence: Double,
    val detectorVersion: String,
) {
    init {
        require(runCatching { UUID.fromString(eventId) }.isSuccess)
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        require(locationAccuracyMetres > 0.0 && locationAccuracyMetres <= 500.0)
        require(speedMetresPerSecond in 0.0..100.0)
        require(severity in 0.0..1.0)
        require(confidence in 0.0..1.0)
        require(detectorVersion.isNotBlank() && detectorVersion.length <= 64)
    }

    fun toJson(installationId: String): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("installation_id", installationId)
        .put("detected_at", Instant.ofEpochMilli(detectedAtMillis).toString())
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("location_accuracy_m", locationAccuracyMetres)
        .put("speed_mps", speedMetresPerSecond)
        .put("kind", kind.contractName)
        .put("severity", severity)
        .put("confidence", confidence)
        .put("source", "phone")
        .put("detector_version", detectorVersion)

    fun toStoredJson(): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("detected_at_millis", detectedAtMillis)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("location_accuracy_metres", locationAccuracyMetres)
        .put("speed_metres_per_second", speedMetresPerSecond)
        .put("kind", kind.contractName)
        .put("severity", severity)
        .put("confidence", confidence)
        .put("detector_version", detectorVersion)

    companion object {
        fun fromStoredJson(json: JSONObject): RoadObservationDraft = RoadObservationDraft(
            eventId = json.getString("event_id"),
            detectedAtMillis = json.getLong("detected_at_millis"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            locationAccuracyMetres = json.getDouble("location_accuracy_metres"),
            speedMetresPerSecond = json.getDouble("speed_metres_per_second"),
            kind = ObservationKind.fromContractName(json.getString("kind")),
            severity = json.getDouble("severity"),
            confidence = json.getDouble("confidence"),
            detectorVersion = json.getString("detector_version"),
        )

        fun batchJson(
            installationId: String,
            observations: List<RoadObservationDraft>,
        ): String {
            require(installationId.length in 16..128)
            require(observations.size in 1..100)
            return JSONObject()
                .put("schema_version", "1.0")
                .put(
                    "observations",
                    JSONArray().also { array ->
                        observations.forEach { array.put(it.toJson(installationId)) }
                    },
                )
                .toString()
        }
    }
}

internal enum class ObservationKind(val contractName: String) {
    ROAD_DAMAGE("road_damage"),
    ROUGH_ROAD("rough_road");

    companion object {
        fun fromContractName(value: String): ObservationKind =
            entries.firstOrNull { it.contractName == value }
                ?: throw IllegalArgumentException("Unknown road observation kind")
    }
}
