package io.github.priencelucifer.michisonae

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class OfflineHazardWarning(
    val hazard: PublicRoadHazard,
    val distanceMetres: Double,
    val message: String,
)

internal class PublicHazardWarningGate(
    private val cooldownMillis: Long = 30_000,
) {
    private var lastObservedAtMillis: Long? = null
    private var lastWarningAtMillis: Long? = null

    init {
        require(cooldownMillis > 0)
    }

    fun shouldWarn(
        warning: OfflineHazardWarning?,
        elapsedRealtimeMillis: Long,
    ): Boolean {
        require(elapsedRealtimeMillis >= 0)
        require(lastObservedAtMillis == null || elapsedRealtimeMillis >= lastObservedAtMillis!!)
        lastObservedAtMillis = elapsedRealtimeMillis
        if (warning == null) return false
        val allowed = lastWarningAtMillis?.let {
            elapsedRealtimeMillis - it >= cooldownMillis
        } ?: true
        if (allowed) lastWarningAtMillis = elapsedRealtimeMillis
        return allowed
    }
}

internal fun findUpcomingHazard(
    snapshot: RegionalHazardSnapshot,
    latitude: Double,
    longitude: Double,
    headingDegrees: Double?,
    maximumDistanceMetres: Double = 300.0,
    maximumHeadingDifferenceDegrees: Double = 70.0,
): OfflineHazardWarning? {
    require(latitude in -90.0..90.0)
    require(longitude in -180.0..180.0)
    require(headingDegrees == null || headingDegrees in 0.0..<360.0)
    require(maximumDistanceMetres > 0)
    require(maximumHeadingDifferenceDegrees in 0.0..180.0)
    return snapshot.hazards
        .asSequence()
        .map { hazard ->
            hazard to distanceMetres(latitude, longitude, hazard.latitude, hazard.longitude)
        }
        .filter { (_, distance) -> distance <= maximumDistanceMetres }
        .filter { (hazard, _) ->
            headingDegrees == null ||
                angularDifference(
                    headingDegrees,
                    bearingDegrees(latitude, longitude, hazard.latitude, hazard.longitude),
                ) <= maximumHeadingDifferenceDegrees
        }
        .minByOrNull { (_, distance) -> distance }
        ?.let { (hazard, distance) ->
            val label = when (hazard.kind) {
                PublicHazardKind.ROAD_DAMAGE -> "Road damage"
                PublicHazardKind.ROUGH_ROAD -> "Rough road"
            }
            OfflineHazardWarning(
                hazard = hazard,
                distanceMetres = distance,
                message = "$label about ${distance.roundToInt()} metres ahead.",
            )
        }
}

private fun distanceMetres(
    latitude: Double,
    longitude: Double,
    otherLatitude: Double,
    otherLongitude: Double,
): Double {
    val lat1 = latitude.toRadians()
    val lat2 = otherLatitude.toRadians()
    val deltaLat = (otherLatitude - latitude).toRadians()
    val deltaLon = (otherLongitude - longitude).toRadians()
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    val bounded = a.coerceIn(0.0, 1.0)
    return 6_371_000.0 * 2 * atan2(sqrt(bounded), sqrt(1 - bounded))
}

private fun bearingDegrees(
    latitude: Double,
    longitude: Double,
    otherLatitude: Double,
    otherLongitude: Double,
): Double {
    val lat1 = latitude.toRadians()
    val lat2 = otherLatitude.toRadians()
    val deltaLon = (otherLongitude - longitude).toRadians()
    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    return (atan2(y, x) * 180 / PI + 360) % 360
}

private fun angularDifference(first: Double, second: Double): Double =
    abs((first - second + 540) % 360 - 180)

private fun Double.toRadians(): Double = this * PI / 180

internal object SimulatedRegionalHazards {
    fun guwahati(generatedAtMillis: Long): RegionalHazardSnapshot =
        RegionalHazardSnapshot(
            regionId = regionalHazardId(26.1445, 91.7362),
            version = "a".repeat(64),
            generatedAtMillis = generatedAtMillis,
            hazards = listOf(
                PublicRoadHazard(
                    id = "100000000000000000000001",
                    kind = PublicHazardKind.ROAD_DAMAGE,
                    latitude = 26.1450,
                    longitude = 91.7362,
                    severity = 0.7,
                    confidence = 0.8,
                    contributorCount = 3,
                ),
                PublicRoadHazard(
                    id = "100000000000000000000002",
                    kind = PublicHazardKind.ROUGH_ROAD,
                    latitude = 26.1438,
                    longitude = 91.7380,
                    severity = 0.5,
                    confidence = 0.7,
                    contributorCount = 2,
                ),
            ),
        )
}
