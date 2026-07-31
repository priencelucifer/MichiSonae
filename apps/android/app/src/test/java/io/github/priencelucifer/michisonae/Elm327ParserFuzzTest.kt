package io.github.priencelucifer.michisonae

import kotlin.random.Random
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
}
