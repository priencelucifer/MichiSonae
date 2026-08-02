package io.github.priencelucifer.michisonae

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBackendContractGoldenTest {
    @Test
    fun androidBatchEncodingMatchesSharedBackendVectors() {
        val golden = goldenVectors()
        for (name in listOf("initial", "overlap")) {
            val expected = golden.getJSONObject(name).getJSONObject("request")
            val observations = expected.getJSONArray("observations")
            val drafts = (0 until observations.length()).map { index ->
                observations.getJSONObject(index).toDraft()
            }
            val actual = JSONObject(
                RoadObservationDraft.batchJson(
                    golden.getString("installation_id"),
                    drafts,
                ),
            )

            assertJsonEquals(expected, actual)
        }
    }

    @Test
    fun sharedDurableAcceptancesAreRecognizedExactly() {
        val golden = goldenVectors()
        val acceptances = listOf(
            "initial" to "acceptance",
            "overlap" to "acceptance",
            "overlap" to "identical_retry_acceptance",
        )

        acceptances.forEach { (requestName, responseName) ->
            val vector = golden.getJSONObject(requestName)
            val response = vector.getJSONObject(responseName)
            assertEquals(
                UploadOutcome.ACCEPTED,
                classifyUpload(
                    statusCode = 202,
                    submittedCount = vector.getJSONObject("request")
                        .getJSONArray("observations")
                        .length(),
                    schemaVersion = response.getString("schema_version"),
                    receivedCount = response.getInt("received_count"),
                    storedCount = response.getInt("stored_count"),
                    duplicateCount = response.getInt("duplicate_count"),
                ),
            )
        }
    }

    private fun goldenVectors(): JSONObject {
        val resource = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("observation-ingestion.v1.json"),
        ) { "Shared observation-ingestion vectors are missing" }
        return resource.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun JSONObject.toDraft(): RoadObservationDraft = RoadObservationDraft(
        eventId = getString("event_id"),
        detectedAtMillis = Instant.parse(getString("detected_at")).toEpochMilli(),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        locationAccuracyMetres = getDouble("location_accuracy_m"),
        speedMetresPerSecond = getDouble("speed_mps"),
        kind = ObservationKind.fromContractName(getString("kind")),
        severity = getDouble("severity"),
        confidence = getDouble("confidence"),
        detectorVersion = getString("detector_version"),
    )

    private fun assertJsonEquals(expected: Any, actual: Any) {
        when (expected) {
            is JSONObject -> {
                assertEquals(expected.keySet(), (actual as JSONObject).keySet())
                expected.keySet().forEach { key ->
                    assertJsonEquals(expected.get(key), actual.get(key))
                }
            }

            is JSONArray -> {
                actual as JSONArray
                assertEquals(expected.length(), actual.length())
                repeat(expected.length()) { index ->
                    assertJsonEquals(expected.get(index), actual.get(index))
                }
            }

            is Number -> assertEquals(expected.toDouble(), (actual as Number).toDouble(), 0.0)
            else -> assertTrue("Expected $expected, received $actual", expected == actual)
        }
    }
}
