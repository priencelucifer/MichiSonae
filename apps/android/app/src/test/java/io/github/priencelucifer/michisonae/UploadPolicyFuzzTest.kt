package io.github.priencelucifer.michisonae

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadPolicyFuzzTest {
    @Test
    fun malformedAcceptanceCountsNeverDeleteQueuedEvents() {
        val random = Random(0x4D53)
        repeat(10_000) {
            val submitted = random.nextInt(-3, 104)
            val received = random.nextInt(-3, 104)
            val stored = random.nextInt(-3, 104)
            val duplicate = random.nextInt(-3, 104)
            val outcome = classifyUpload(202, submitted, received, stored, duplicate)
            val valid = submitted in 1..100 &&
                received == submitted &&
                received in 0..submitted &&
                stored in 0..submitted &&
                duplicate in 0..submitted &&
                stored + duplicate == received
            assertEquals(if (valid) UploadOutcome.ACCEPTED else UploadOutcome.REJECTED, outcome)
            assertEquals(
                if (valid) setOf("queued") else emptySet(),
                acknowledgedEventIds(outcome, setOf("queued")),
            )
        }
    }
}
