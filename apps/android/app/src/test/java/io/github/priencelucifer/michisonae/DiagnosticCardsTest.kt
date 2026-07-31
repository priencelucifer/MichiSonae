package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCardsTest {
    @Test
    fun cardsAreDeterministicBoundedAndRefreshedByCode() {
        val cards = refreshDiagnosticCards(
            existing = emptyList(),
            activeCodes = listOf("p0420", "P0524", "P0524", "malformed"),
            observedAtEpochMillis = 1_000,
            retentionMillis = 10_000,
        )

        assertEquals(listOf("P0524", "P0420"), cards.map { it.finding.code })
        assertEquals(DiagnosticSeverity.STOP_SAFELY, cards.first().finding.severity)

        val refreshed = refreshDiagnosticCards(
            existing = cards,
            activeCodes = listOf("P0524"),
            observedAtEpochMillis = 2_000,
            retentionMillis = 10_000,
        )
        val critical = refreshed.first { it.finding.code == "P0524" }
        assertEquals(1_000, critical.firstObservedAtEpochMillis)
        assertEquals(2_000, critical.lastObservedAtEpochMillis)
        assertEquals(12_000, critical.expiresAtEpochMillis)
    }

    @Test
    fun expiredAndDeletedCardsStayLocalAndDisappear() {
        val cards = refreshDiagnosticCards(
            existing = emptyList(),
            activeCodes = listOf("P0420", "P0562"),
            observedAtEpochMillis = 1_000,
            retentionMillis = 1_000,
        )

        assertEquals(
            listOf("P0562"),
            deleteDiagnosticCard(cards, "p0420").map { it.finding.code },
        )
        assertTrue(
            refreshDiagnosticCards(
                existing = cards,
                activeCodes = emptyList(),
                observedAtEpochMillis = 2_000,
                retentionMillis = 1_000,
            ).isEmpty(),
        )
    }

    @Test
    fun unknownFutureCardSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateDiagnosticCardSchema(2)
        }
    }
}
