package io.github.priencelucifer.michisonae

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock
import org.json.JSONObject

internal enum class CredentialAction {
    REGISTER,
    REFRESH,
    USE_CURRENT,
}

internal enum class RevocationAccessAction {
    USE_CURRENT,
    REFRESH,
}

internal object CredentialOperationCoordinator {
    // ponytail: process-wide lock; use a file lock only if sync moves to another process.
    private val lock = ReentrantLock()

    fun <T> runExclusive(operation: () -> T): T = lock.withLock(operation)
}

internal class CredentialCreationDisabled : Exception("Backend credentials are disabled locally")

internal fun credentialAction(
    credentials: AnonymousCredentials?,
    now: Instant,
    refreshBefore: Duration = Duration.ofMinutes(1),
): CredentialAction {
    if (credentials == null) return CredentialAction.REGISTER
    val accessExpiry = runCatching { Instant.parse(credentials.accessExpiresAt) }.getOrNull()
        ?: return CredentialAction.REGISTER
    val refreshExpiry = runCatching { Instant.parse(credentials.refreshExpiresAt) }.getOrNull()
        ?: return CredentialAction.REGISTER
    return when {
        !refreshExpiry.isAfter(now) -> CredentialAction.REGISTER
        !accessExpiry.isAfter(now.plus(refreshBefore)) -> CredentialAction.REFRESH
        else -> CredentialAction.USE_CURRENT
    }
}

internal fun revocationAccessAction(
    credentials: AnonymousCredentials,
    now: Instant,
): RevocationAccessAction =
    if (credentialAction(credentials, now) == CredentialAction.REFRESH) {
        RevocationAccessAction.REFRESH
    } else {
        RevocationAccessAction.USE_CURRENT
    }

internal class EncryptedCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AnonymousCredentials? = synchronized(lock) {
        val envelope = preferences.getString(ENVELOPE, null) ?: return@synchronized null
        runCatching {
            val encrypted = JSONObject(envelope)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(
                        TAG_BITS,
                        Base64.decode(encrypted.getString("iv"), Base64.NO_WRAP),
                    ),
                )
                updateAAD(appContext.packageName.toByteArray(Charsets.UTF_8))
            }
            credentialsFromJson(
                JSONObject(
                    cipher.doFinal(
                        Base64.decode(encrypted.getString("ciphertext"), Base64.NO_WRAP),
                    ).toString(Charsets.UTF_8),
                ),
            )
        }.getOrElse {
            preferences.edit().remove(ENVELOPE).commit()
            null
        }
    }

    fun save(credentials: AnonymousCredentials) = synchronized(lock) {
        validate(credentials)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(appContext.packageName.toByteArray(Charsets.UTF_8))
        }
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put(
                "ciphertext",
                Base64.encodeToString(
                    cipher.doFinal(credentialsToJson(credentials).toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                ),
            )
            .toString()
        check(preferences.edit().putString(ENVELOPE, envelope).commit()) {
            "Encrypted credentials could not be stored"
        }
    }

    fun clear() = synchronized(lock) {
        check(preferences.edit().remove(ENVELOPE).commit()) {
            "Encrypted credentials could not be deleted"
        }
        keyStore().apply {
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val existing = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun credentialsToJson(credentials: AnonymousCredentials): String = JSONObject()
        .put("installation_id", credentials.installationId)
        .put("access_token", credentials.accessToken)
        .put("access_expires_at", credentials.accessExpiresAt)
        .put("refresh_token", credentials.refreshToken)
        .put("refresh_expires_at", credentials.refreshExpiresAt)
        .toString()

    private fun credentialsFromJson(json: JSONObject): AnonymousCredentials =
        AnonymousCredentials(
            installationId = json.getString("installation_id"),
            accessToken = json.getString("access_token"),
            accessExpiresAt = json.getString("access_expires_at"),
            refreshToken = json.getString("refresh_token"),
            refreshExpiresAt = json.getString("refresh_expires_at"),
        ).also(::validate)

    private fun validate(credentials: AnonymousCredentials) {
        require(credentials.installationId.length in 16..128)
        require(credentials.accessToken.isNotBlank())
        require(credentials.refreshToken.length in 40..256)
        Instant.parse(credentials.accessExpiresAt)
        Instant.parse(credentials.refreshExpiresAt)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "michisonae.backend.credentials.v1"
        const val PREFERENCES = "michisonae-encrypted-backend-credentials"
        const val ENVELOPE = "aes-gcm-envelope"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val lock = Any()
    }
}

internal enum class LocalRevocationResult {
    CONFIRMED,
    LOCAL_ONLY,
    NOTHING_TO_REVOKE,
}

internal class AnonymousCredentialManager(
    private val api: MichiSonaeApi,
    private val store: EncryptedCredentialStore,
    private val credentialCreationAllowed: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun credentials(): AnonymousCredentials = CredentialOperationCoordinator.runExclusive {
        credentialsLocked()
    }

    fun uploadPending(
        queue: OfflineObservationQueue,
    ): UploadOutcome? = CredentialOperationCoordinator.runExclusive {
        if (queue.pending().isEmpty()) return@runExclusive null
        var current = credentialsLocked()
        var outcome = api.uploadPending(queue, current)
        if (outcome == UploadOutcome.AUTH_EXPIRED) {
            current = refreshOrRegister(current)
            outcome = api.uploadPending(queue, current)
        }
        outcome
    }

    fun revokeAndDeleteLocally(): LocalRevocationResult =
        CredentialOperationCoordinator.runExclusive {
            val current = store.load()
                ?: return@runExclusive LocalRevocationResult.NOTHING_TO_REVOKE
            val usable = if (
                revocationAccessAction(current, clock.instant()) == RevocationAccessAction.REFRESH
            ) {
                try {
                    api.refresh(current.refreshToken)
                } catch (_: Exception) {
                    current
                }
            } else {
                current
            }
            val remote = try {
                api.revoke(usable.accessToken)
            } catch (_: Exception) {
                null
            }
            store.clear()
            if (remote == RevocationOutcome.REVOKED) {
                LocalRevocationResult.CONFIRMED
            } else {
                LocalRevocationResult.LOCAL_ONLY
            }
        }

    private fun credentialsLocked(): AnonymousCredentials {
        ensureCredentialCreationAllowed()
        val stored = store.load()
        return when (credentialAction(stored, clock.instant())) {
            CredentialAction.USE_CURRENT -> checkNotNull(stored)
            CredentialAction.REFRESH -> refreshOrRegister(checkNotNull(stored))
            CredentialAction.REGISTER -> registerReplacingLocalState()
        }
    }

    private fun refreshOrRegister(current: AnonymousCredentials): AnonymousCredentials =
        try {
            persist(api.refresh(current.refreshToken))
        } catch (error: ApiUnavailable) {
            if (error.statusCode != 401) throw error
            registerReplacingLocalState()
        }

    private fun registerReplacingLocalState(): AnonymousCredentials {
        ensureCredentialCreationAllowed()
        store.clear()
        return persist(api.register())
    }

    private fun persist(credentials: AnonymousCredentials): AnonymousCredentials {
        if (!credentialCreationAllowed()) {
            try {
                api.revoke(credentials.accessToken)
            } catch (_: Exception) {
                // Local deletion still wins if the network disappears during deletion.
            }
            store.clear()
            throw CredentialCreationDisabled()
        }
        try {
            store.save(credentials)
        } catch (error: Exception) {
            try {
                api.revoke(credentials.accessToken)
            } catch (_: Exception) {
                // The issued credentials expire server-side and never reach local storage.
            }
            store.clear()
            throw error
        }
        return credentials
    }

    private fun ensureCredentialCreationAllowed() {
        if (!credentialCreationAllowed()) throw CredentialCreationDisabled()
    }
}
