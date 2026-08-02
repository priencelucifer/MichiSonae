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
    fun keepsRepliesFromTwoEcusIndependent() {
        assertEquals(
            listOf("P0133", "P0210"),
            Elm327Parser.troubleCodes(
                "7E8 03 43 01 33\r7E9 03 43 02 10\r>",
            ),
        )
    }

    @Test
    fun reassemblesInterleavedIsoTpFramesByCanSource() {
        assertEquals(
            listOf("P0133", "P0210", "P0300", "P0420"),
            Elm327Parser.troubleCodes(
                "7E8 10 07 43 01 33 02 10 03\r" +
                    "7E9 10 07 43 04 20 00 00 00\r" +
                    "7E8 21 00\r" +
                    "7E9 21 00\r>",
            ),
        )
    }

    @Test
    fun truncatedOutOfOrderOrCrossEcuFramesNeverBecomeTroubleCodes() {
        listOf(
            "7E8 10 06 43 01 33 02\r>",
            "7E8 10 06 43 01 33 02\r7E8 22 10 00 00\r>",
            "7E8 10 06 43 01 33 02\r7E9 21 10 00 00\r>",
        ).forEach { corrupted ->
            assertEquals(emptyList<String>(), Elm327Parser.troubleCodes(corrupted))
        }
    }

    @Test
    fun unrelatedAndMismatchedPidFramesCannotSpoofAReading() {
        listOf(
            "41 0D 7F\r>",
            "DE AD BE EF\r>",
            "41 0C 1A\r>",
            "NO DATA\r41 0C 1A F8\r>",
        ).forEach { response ->
            assertNull(Elm327Parser.reading(Elm327Command.ENGINE_RPM, response))
        }
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
    fun acceptsCloneLineCountersAndColonHeaders() {
        assertEquals(
            1726.0,
            checkNotNull(
                Elm327Parser.reading(
                    Elm327Command.ENGINE_RPM,
                    "0: 7E8 04 41 0C 1A F8\r>",
                ),
            ).value,
            0.0,
        )
        assertEquals(
            40.0,
            checkNotNull(
                Elm327Parser.reading(
                    Elm327Command.VEHICLE_SPEED,
                    "7E8: 03 41 0D 28\r>",
                ),
            ).value,
            0.0,
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
        listOf("CAN ERROR", "BUFFER FULL", "?", "BUS INIT: ERROR").forEach {
            assertEquals(
                Elm327ResponseStatus.ADAPTER_ERROR,
                Elm327Parser.response("$it\r>").status,
            )
        }
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

    @Test
    fun readOnlyAllowlistIsExactlyInitializationModeOneAndModeThree() {
        Elm327Command.entries.forEach { command ->
            assertTrue(command.isAllowedReadOnlyCommand())
            assertTrue(
                command.wireValue in setOf("ATE0", "ATSP0", "03") ||
                    command.wireValue.matches(Regex("01[0-9A-F]{2}")),
            )
        }
    }
}
