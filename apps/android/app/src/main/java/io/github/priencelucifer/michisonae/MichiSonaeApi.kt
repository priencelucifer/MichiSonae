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

internal enum class RevocationOutcome {
    REVOKED,
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
        submittedCount in 1..100 &&
        receivedCount != null &&
        storedCount != null &&
        duplicateCount != null &&
        receivedCount == submittedCount &&
        receivedCount in 0..submittedCount &&
        storedCount in 0..submittedCount &&
        duplicateCount in 0..submittedCount &&
        storedCount + duplicateCount == receivedCount -> UploadOutcome.ACCEPTED

    statusCode == 401 -> UploadOutcome.AUTH_EXPIRED
    statusCode == 408 || statusCode == 429 || statusCode >= 500 -> UploadOutcome.RETRY
    else -> UploadOutcome.REJECTED
}

internal fun bearerAuthorization(accessToken: String): String {
    require(accessToken.length in 1..4_096)
    require(accessToken.isNotBlank() && accessToken.none(Char::isISOControl))
    return "Bearer $accessToken"
}

internal fun classifyRevocation(statusCode: Int): RevocationOutcome = when {
    statusCode == 204 -> RevocationOutcome.REVOKED
    statusCode == 408 || statusCode == 429 || statusCode >= 500 -> RevocationOutcome.RETRY
    else -> RevocationOutcome.REJECTED
}

internal fun validatedApiBaseUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrElse {
        throw IllegalArgumentException("The API URL is invalid", it)
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    require(
        host != null &&
            (scheme == "https" ||
                (scheme == "http" && host in setOf("localhost", "127.0.0.1"))) &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            (uri.port == -1 || uri.port in 1..65_535),
    ) {
        "The API URL must be a clean HTTPS origin outside local development"
    }
    return URI(scheme, null, host, uri.port, null, null, null).toASCIIString()
}

internal class MichiSonaeApi(baseUrl: String) {
    private val baseUrl = validatedApiBaseUrl(baseUrl)

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
            setRequestProperty("Authorization", bearerAuthorization(credentials.accessToken))
        }
        return try {
            val status = connection.send(
                RoadObservationDraft.batchJson(credentials.installationId, observations),
            )
            val response = if (status == 202) {
                runCatching {
                    connection.inputStream.use {
                        JSONObject(it.readUtf8AtMost(MAX_API_RESPONSE_BYTES))
                    }
                }.getOrNull()
            } else {
                null
            }
            classifyUpload(
                statusCode = status,
                submittedCount = observations.size,
                receivedCount = response.strictInt("received_count"),
                storedCount = response.strictInt("stored_count"),
                duplicateCount = response.strictInt("duplicate_count"),
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

    fun revoke(accessToken: String): RevocationOutcome {
        require(accessToken.isNotBlank())
        val connection = open(
            path = "/v1/installations/current",
            method = "DELETE",
        ).apply {
            setRequestProperty("Authorization", bearerAuthorization(accessToken))
        }
        return try {
            classifyRevocation(connection.responseCode)
        } finally {
            connection.disconnect()
        }
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
            val response = connection.inputStream.use {
                JSONObject(it.readUtf8AtMost(MAX_API_RESPONSE_BYTES))
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

    private fun open(
        path: String,
        method: String = "POST",
    ): HttpURLConnection =
        (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = method == "POST"
            setRequestProperty("Accept", "application/json")
            if (doOutput) setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }

    private fun HttpURLConnection.send(body: String): Int {
        outputStream.bufferedWriter().use { it.write(body) }
        return responseCode
    }

    private fun JSONObject?.strictInt(name: String): Int? = this?.opt(name) as? Int
}

internal class ApiUnavailable(val statusCode: Int) :
    Exception("MichiSonae API request failed")

private const val MAX_API_RESPONSE_BYTES = 16 * 1_024
