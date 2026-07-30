package io.github.priencelucifer.michisonae

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialLifecycleTest {
    private val now = Instant.parse("2026-07-30T12:00:00Z")

    @Test
    fun registersWithoutUsableCredentials() {
        assertEquals(CredentialAction.REGISTER, credentialAction(null, now))
        assertEquals(
            CredentialAction.REGISTER,
            credentialAction(credentials(refreshExpiry = "2026-07-30T12:00:00Z"), now),
        )
        assertEquals(
            CredentialAction.REGISTER,
            credentialAction(credentials(accessExpiry = "invalid"), now),
        )
    }

    @Test
    fun refreshesBeforeAccessExpiryAndOtherwiseUsesCurrentCredentials() {
        assertEquals(
            CredentialAction.REFRESH,
            credentialAction(
                credentials(accessExpiry = "2026-07-30T12:00:30Z"),
                now,
                Duration.ofMinutes(1),
            ),
        )
        assertEquals(
            CredentialAction.USE_CURRENT,
            credentialAction(
                credentials(accessExpiry = "2026-07-30T12:02:00Z"),
                now,
                Duration.ofMinutes(1),
            ),
        )
    }

    @Test
    fun revocationStatusIsDeterministic() {
        assertEquals(RevocationOutcome.REVOKED, classifyRevocation(204))
        assertEquals(RevocationOutcome.ALREADY_INVALID, classifyRevocation(401))
        assertEquals(RevocationOutcome.RETRY, classifyRevocation(503))
        assertEquals(RevocationOutcome.REJECTED, classifyRevocation(403))
    }

    private fun credentials(
        accessExpiry: String = "2026-07-30T12:02:00Z",
        refreshExpiry: String = "2026-08-30T12:00:00Z",
    ) = AnonymousCredentials(
        installationId = "ins_1234567890123456",
        accessToken = "access-token",
        accessExpiresAt = accessExpiry,
        refreshToken = "r".repeat(40),
        refreshExpiresAt = refreshExpiry,
    )
}
