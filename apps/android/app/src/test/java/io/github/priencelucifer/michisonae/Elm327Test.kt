package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            checkNotNull(
                Elm327Parser.reading(Elm327Command.ENGINE_RPM, "41 0C 1A F8\r>"),
            ).value,
            0.0,
        )
        assertEquals(
            40.0,
            checkNotNull(
                Elm327Parser.reading(Elm327Command.VEHICLE_SPEED, "41 0D 28\r>"),
            ).value,
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

    @Test
    fun normalizesCheapCloneEchoHeadersLengthAndCompactFrames() {
        val response = "010C\rSEARCHING...\r7E8 04 41 0C 1A F8\r>"
        assertEquals(
            1726.0,
            checkNotNull(
                Elm327Parser.reading(Elm327Command.ENGINE_RPM, response),
            ).value,
            0.0,
        )
        assertEquals(
            40.0,
            checkNotNull(
                Elm327Parser.reading(
                    Elm327Command.VEHICLE_SPEED,
                    "7E803410D28>",
                ),
            ).value,
            0.0,
        )
        assertEquals(
            listOf("P0133", "P0210"),
            Elm327Parser.troubleCodes(
                "7E8 10 06 43 01 33 02\r7E8 21 10 00 00\r>",
            ),
        )
    }

    @Test
    fun reportsAdapterFailuresAndRejectsUnboundedInput() {
        assertEquals(
            Elm327ResponseStatus.NO_DATA,
            Elm327Parser.response("NO DATA\r>").status,
        )
        assertEquals(
            Elm327ResponseStatus.STOPPED,
            Elm327Parser.response("STOPPED\r>").status,
        )
        assertEquals(
            Elm327ResponseStatus.UNABLE_TO_CONNECT,
            Elm327Parser.response("UNABLE TO CONNECT\r>").status,
        )
        assertEquals(
            Elm327ResponseStatus.INVALID,
            Elm327Parser.response("A".repeat(8_193)).status,
        )
    }

    @Test
    fun discoversSupportedPidPagesAndSkipsUnsupportedReadings() {
        val supported = buildSet {
            addAll(
                Elm327Parser.supportedPids(
                    Elm327Command.SUPPORTED_PIDS,
                    Elm327Simulator.response(Elm327Command.SUPPORTED_PIDS),
                ),
            )
            addAll(
                Elm327Parser.supportedPids(
                    Elm327Command.SUPPORTED_PIDS_21_TO_40,
                    Elm327Simulator.response(Elm327Command.SUPPORTED_PIDS_21_TO_40),
                ),
            )
            addAll(
                Elm327Parser.supportedPids(
                    Elm327Command.SUPPORTED_PIDS_41_TO_60,
                    Elm327Simulator.response(Elm327Command.SUPPORTED_PIDS_41_TO_60),
                ),
            )
        }

        assertTrue(0x04 in supported)
        assertTrue(0x2F in supported)
        assertTrue(0x42 in supported)
        assertNull(
            Elm327Parser.reading(
                Elm327Command.FUEL_LEVEL,
                Elm327Simulator.response(Elm327Command.FUEL_LEVEL),
                emptySet(),
            ),
        )
    }
}
