package io.github.priencelucifer.michisonae

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ParserFuzzTest {
    @Test
    fun arbitraryAdapterTextNeverEscapesTheBoundedParser() {
        val random = Random(0x454C4D)
        val alphabet = "0123456789ABCDEFabcdef:> \r\n?NO DATASTOPPEDxyz\u0000"

        repeat(2_000) {
            val input = buildString {
                repeat(random.nextInt(0, 513)) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
            assertTrue(runCatching {
                Elm327Parser.response(input)
                Elm327Parser.troubleCodes(input)
                Elm327Command.entries.forEach { command ->
                    Elm327Parser.reading(command, input)
                    Elm327Parser.supportedPids(command, input)
                }
            }.isSuccess)
        }
    }

    @Test
    fun oversizedAdapterTextIsRejectedWithoutParsingPayloads() {
        val response = Elm327Parser.response("41 0C 1A F8 ".repeat(1_000))

        assertTrue(response.status == Elm327ResponseStatus.INVALID)
        assertTrue(response.payloads.isEmpty())
    }

    @Test
    fun corruptedIsoTpSequencesNeverCreatePartialTroubleCodes() {
        val random = Random(0x49534F)

        repeat(2_000) {
            val sequence = random.nextInt(2, 16)
            val source = if (random.nextBoolean()) "7E8" else "7E9"
            val corrupted =
                "7E8 10 06 43 01 33 02\r$source 2${sequence.toString(16)} 10 00 00\r>"

            assertEquals(emptyList<String>(), Elm327Parser.troubleCodes(corrupted))
        }
    }

    @Test
    fun textNoiseCannotBeStrippedIntoPlausibleVehicleData() {
        val random = Random(0x53414645)

        repeat(2_000) {
            val plausibleHex = buildString {
                repeat(random.nextInt(2, 40)) {
                    append("0123456789ABCDEF"[random.nextInt(16)])
                    if (random.nextBoolean()) append(' ')
                }
            }
            val corrupted = "junk $plausibleHex xyz>"

            assertTrue(Elm327Parser.response(corrupted).payloads.isEmpty())
            assertEquals(emptyList<String>(), Elm327Parser.troubleCodes(corrupted))
            Elm327Command.entries.forEach { command ->
                assertNull(Elm327Parser.reading(command, corrupted))
            }
        }
    }
}
