package io.github.priencelucifer.michisonae

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class PlaceAvailability(val displayName: String) {
    OPEN("Reported open"),
    CLOSED("Reported closed"),
    UNKNOWN("Hours uncertain"),
}

internal data class ServiceCenterOption(
    val name: String,
    val availability: PlaceAvailability,
    val mapQuery: String,
    val distanceKm: Double? = null,
)

internal fun mapSearchUri(query: String): String {
    val normalized = normalizeDestinationLabel(query)
    require(normalized.isNotEmpty())
    val encoded = encodeMapQuery(normalized)
    return "geo:0,0?q=$encoded"
}

internal enum class DestinationSource {
    TYPED,
    FAVORITE,
    SHARED,
}

internal data class Destination(
    val label: String,
    val source: DestinationSource,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

internal fun normalizeDestination(
    text: String,
    source: DestinationSource,
    latitude: Double? = null,
    longitude: Double? = null,
): Destination? {
    if ((latitude == null) != (longitude == null)) return null
    if (latitude != null) {
        val longitudeValue = longitude ?: return null
        if (
            !latitude.isFinite() ||
            latitude !in -90.0..90.0 ||
            !longitudeValue.isFinite() ||
            longitudeValue !in -180.0..180.0
        ) {
            return null
        }
    }
    val label = normalizeDestinationLabel(text)
    if (label.isEmpty()) return null
    return Destination(label, source, latitude, longitude)
}

internal fun destinationMapUri(destination: Destination): String {
    val normalized = requireNotNull(
        normalizeDestination(
            destination.label,
            destination.source,
            destination.latitude,
            destination.longitude,
        ),
    )
    val latitude = normalized.latitude
    val longitude = normalized.longitude
    return if (latitude == null || longitude == null) {
        mapSearchUri(normalized.label)
    } else {
        "geo:$latitude,$longitude?q=${encodeMapQuery(normalized.label)}"
    }
}

internal fun destinationShareText(destination: Destination): String =
    "${destination.label}\n${destinationMapUri(destination)}"

internal fun favoriteDestinations(
    destinations: Iterable<Destination>,
    limit: Int = MAX_FAVORITE_DESTINATIONS,
): List<Destination> {
    require(limit in 1..MAX_FAVORITE_DESTINATIONS)
    return destinations
        .asSequence()
        .mapNotNull {
            normalizeDestination(
                text = it.label,
                source = DestinationSource.FAVORITE,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
        .distinctBy { destination ->
            Triple(
                destination.label.lowercase(),
                destination.latitude,
                destination.longitude,
            )
        }
        .take(limit)
        .toList()
}

internal fun Context.openMapSearch(query: String): Boolean {
    val uri = Uri.parse(mapSearchUri(query))
    val googleMaps = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(googleMaps) }.isSuccess) return true
    return runCatching {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, uri),
                "Open map",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

internal enum class NearbyPlaceKind {
    FUEL_STATION,
    SERVICE_CENTER,
}

internal data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

internal data class NearbyPlace(
    val id: String,
    val name: String,
    val kind: NearbyPlaceKind,
    val location: GeoPoint,
    val availability: PlaceAvailability = PlaceAvailability.UNKNOWN,
    val hoursCheckedAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_PLACE_ID_CHARS)
        require(name.isNotBlank() && name.length <= MAX_DESTINATION_CHARS)
        require(id.none(Char::isISOControl) && name.none(Char::isISOControl))
        require(hoursCheckedAtEpochMillis == null || hoursCheckedAtEpochMillis >= 0)
    }
}

internal data class NearbyPlaceSearch(
    val origin: GeoPoint,
    val kind: NearbyPlaceKind,
    val radiusKm: Double = 50.0,
    val limit: Int = 20,
) {
    init {
        require(radiusKm.isFinite() && radiusKm in 0.1..200.0)
        require(limit in 1..50)
    }
}

internal data class NearbyPlaceResult(
    val place: NearbyPlace,
    val distanceKm: Double,
)

internal interface NearbyPlacesProvider {
    fun search(request: NearbyPlaceSearch): List<NearbyPlaceResult>
}

internal class StaticNearbyPlacesProvider(
    places: Iterable<NearbyPlace>,
) : NearbyPlacesProvider {
    private val places = places.toList()

    override fun search(request: NearbyPlaceSearch): List<NearbyPlaceResult> =
        places.asSequence()
            .filter { it.kind == request.kind }
            .map { NearbyPlaceResult(it, distanceKm(request.origin, it.location)) }
            .filter { it.distanceKm <= request.radiusKm }
            .sortedWith(
                compareBy<NearbyPlaceResult> { it.distanceKm }
                    .thenBy { it.place.name },
            )
            .take(request.limit)
            .toList()
}

internal fun serviceCenterChoices(
    results: Iterable<NearbyPlaceResult>,
): List<ServiceCenterOption> = results
    .filter { it.place.kind == NearbyPlaceKind.SERVICE_CENTER }
    .sortedWith(
        compareBy<NearbyPlaceResult> { availabilityOrder(it.place.availability) }
            .thenBy(NearbyPlaceResult::distanceKm),
    )
    .map {
        ServiceCenterOption(
            name = it.place.name,
            availability = it.place.availability,
            mapQuery = "${it.place.location.latitude}," +
                "${it.place.location.longitude} ${it.place.name}",
            distanceKm = it.distanceKm,
        )
    }

internal object NearbyPlacesTestData {
    val provider: NearbyPlacesProvider = StaticNearbyPlacesProvider(
        listOf(
            NearbyPlace(
                id = "sim-fuel-1",
                name = "Simulated fuel station",
                kind = NearbyPlaceKind.FUEL_STATION,
                location = GeoPoint(26.1445, 91.7362),
                availability = PlaceAvailability.UNKNOWN,
            ),
            NearbyPlace(
                id = "sim-service-1",
                name = "Simulated service center",
                kind = NearbyPlaceKind.SERVICE_CENTER,
                location = GeoPoint(26.1450, 91.7350),
                availability = PlaceAvailability.OPEN,
            ),
            NearbyPlace(
                id = "sim-service-closed",
                name = "Simulated closed workshop",
                kind = NearbyPlaceKind.SERVICE_CENTER,
                location = GeoPoint(26.1500, 91.7400),
                availability = PlaceAvailability.CLOSED,
            ),
        ),
    )
}

internal data class ExplainedDiagnostic(
    val finding: DiagnosticFinding,
    val explanation: String,
)

internal fun localGemmaPrompt(finding: DiagnosticFinding): String =
    """
    Explain this vehicle diagnostic in plain English using at most three sentences.
    Code: ${finding.code}
    Policy title: ${finding.title}
    Deterministic safe action: ${finding.safeAction}
    Do not change the severity or safe action. Do not invent a repair or certainty.
    """.trimIndent()

internal fun attachLocalExplanation(
    finding: DiagnosticFinding,
    modelExplanation: String,
): ExplainedDiagnostic {
    val explanation = modelExplanation.trim().take(600)
    require(explanation.isNotBlank())
    return ExplainedDiagnostic(finding, explanation)
}

internal fun mockLocalExplanation(finding: DiagnosticFinding): String =
    "The car reported ${finding.code}, which usually points to ${finding.title.lowercase()}. " +
        "The exact cause still needs a professional diagnosis."

internal enum class LocalVoiceCommand {
    FIND_FUEL,
    FIND_SERVICE,
    REPEAT_WARNING,
    NONE,
}

internal fun parseLocalVoiceTranscript(transcript: String): LocalVoiceCommand {
    val normalized = transcript.trim().lowercase()
    if (!normalized.startsWith("michisonae")) return LocalVoiceCommand.NONE
    return when {
        "fuel" in normalized -> LocalVoiceCommand.FIND_FUEL
        "service" in normalized || "mechanic" in normalized ->
            LocalVoiceCommand.FIND_SERVICE

        "repeat" in normalized -> LocalVoiceCommand.REPEAT_WARNING
        else -> LocalVoiceCommand.NONE
    }
}

private fun normalizeDestinationLabel(value: String): String =
    value
        .filterNot(Char::isISOControl)
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_DESTINATION_CHARS)

private fun encodeMapQuery(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun availabilityOrder(availability: PlaceAvailability): Int = when (availability) {
    PlaceAvailability.OPEN -> 0
    PlaceAvailability.UNKNOWN -> 1
    PlaceAvailability.CLOSED -> 2
}

private fun distanceKm(first: GeoPoint, second: GeoPoint): Double {
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(firstLatitude) * cos(secondLatitude) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return 12_742.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val MAX_DESTINATION_CHARS = 160
private const val MAX_FAVORITE_DESTINATIONS = 20
private const val MAX_PLACE_ID_CHARS = 100
