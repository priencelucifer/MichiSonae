package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Elm327Test {
    @Test
    fun commandSetContainsNoEcuWriteOrClearMode() {
        Elm327Command.entries.forEach { command ->
            assertFalse(command.wireValue.startsWith("04"))
            assertFalse(command.wireValue.startsWith("05"))
            assertFalse(command.wireValue.startsWith("06"))
            assertFalse(command.wireValue.startsWith("08"))
        }
    }

    @Test
    fun parsesStandardLiveValues() {
        assertEquals(
            1726.0,
            Elm327Parser.reading(Elm327Command.ENGINE_RPM, "41 0C 1A F8\r>")?.value,
            0.0,
        )
        assertEquals(
            40.0,
            Elm327Parser.reading(Elm327Command.VEHICLE_SPEED, "41 0D 28\r>")?.value,
            0.0,
        )
    }

    @Test
    fun parsesDiagnosticTroubleCodesWithoutClearingThem() {
        assertEquals(
            listOf("P0133"),
            Elm327Parser.troubleCodes("43 01 33 00 00\r>"),
        )
    }
}
