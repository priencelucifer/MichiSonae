package io.github.priencelucifer.michisonae

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

internal data class AnonymousCredentials(
    val installationId: String,
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

internal enum class UploadOutcome {
    ACCEPTED,
    AUTH_EXPIRED,
    RETRY,
    REJECTED,
}

internal fun acknowledgedEventIds(
    outcome: UploadOutcome,
    submittedEventIds: Set<String>,
): Set<String> = if (outcome == UploadOutcome.ACCEPTED) submittedEventIds else emptySet()

internal fun classifyUpload(
    statusCode: Int,
    submittedCount: Int,
    receivedCount: Int? = null,
    storedCount: Int? = null,
    duplicateCount: Int? = null,
): UploadOutcome = when {
    statusCode == 202 &&
        receivedCount == submittedCount &&
        storedCount != null &&
        duplicateCount != null &&
        storedCount + duplicateCount == receivedCount -> UploadOutcome.ACCEPTED

    statusCode == 401 -> UploadOutcome.AUTH_EXPIRED
    statusCode == 408 || statusCode == 429 || statusCode >= 500 -> UploadOutcome.RETRY
    else -> UploadOutcome.REJECTED
}

internal class MichiSonaeApi(baseUrl: String) {
    private val baseUrl = baseUrl.trimEnd('/').also {
        val uri = URI(it)
        require(
            uri.scheme == "https" ||
                (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")),
        ) {
            "The API must use HTTPS outside local development"
        }
    }

    fun register(): AnonymousCredentials = credentialsRequest(
        path = "/v1/installations:register",
        body = """{"schema_version":"1.0"}""",
        expectedStatus = 201,
    )

    @Synchronized
    fun refresh(refreshToken: String): AnonymousCredentials = credentialsRequest(
        path = "/v1/auth:refresh",
        body = JSONObject()
            .put("schema_version", "1.0")
            .put("refresh_token", refreshToken)
            .toString(),
        expectedStatus = 200,
    )

    fun upload(
        credentials: AnonymousCredentials,
        observations: List<RoadObservationDraft>,
    ): UploadOutcome {
        require(observations.size in 1..100)
        val connection = open("/v1/observations:batch").apply {
            setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
        }
        return try {
            val status = connection.send(
                RoadObservationDraft.batchJson(credentials.installationId, observations),
            )
            val response = if (status == 202) {
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } else {
                null
            }
            classifyUpload(
                statusCode = status,
                submittedCount = observations.size,
                receivedCount = response?.optInt("received_count"),
                storedCount = response?.optInt("stored_count"),
                duplicateCount = response?.optInt("duplicate_count"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun uploadPending(
        queue: OfflineObservationQueue,
        credentials: AnonymousCredentials,
    ): UploadOutcome? {
        val pending = queue.pending()
        if (pending.isEmpty()) return null
        val outcome = upload(credentials, pending)
        queue.acknowledgeAfterDurableAcceptance(
            eventIds = pending.mapTo(mutableSetOf()) { it.eventId },
            outcome = outcome,
        )
        return outcome
    }

    private fun credentialsRequest(
        path: String,
        body: String,
        expectedStatus: Int,
    ): AnonymousCredentials {
        val connection = open(path)
        return try {
            val status = connection.send(body)
            if (status != expectedStatus) throw ApiUnavailable(status)
            val response = connection.inputStream.bufferedReader().use {
                JSONObject(it.readText())
            }
            AnonymousCredentials(
                installationId = response.getString("installation_id"),
                accessToken = response.getString("access_token"),
                accessExpiresAt = response.getString("access_expires_at"),
                refreshToken = response.getString("refresh_token"),
                refreshExpiresAt = response.getString("refresh_expires_at"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }

    private fun HttpURLConnection.send(body: String): Int {
        outputStream.bufferedWriter().use { it.write(body) }
        return responseCode
    }
}

internal class ApiUnavailable(val statusCode: Int) :
    Exception("MichiSonae API request failed")
