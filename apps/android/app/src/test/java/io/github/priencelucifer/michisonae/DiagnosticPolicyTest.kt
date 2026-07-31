package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPolicyTest {
    @Test
    fun criticalEngineCodesKeepDeterministicStopPolicy() {
        listOf("P0217", "P0524").forEach { code ->
            assertEquals(DiagnosticSeverity.STOP_SAFELY, DiagnosticPolicy.interpret(code).severity)
        }
    }

    @Test
    fun commonGenericCodesHaveConservativeExplanations() {
        val expectedTitles = mapOf(
            "p0171" to "Fuel and air mixture",
            "P0420" to "Emissions catalyst",
            "P0456" to "Fuel-vapour",
            "P0562" to "Vehicle voltage",
            "P0700" to "Transmission control",
            "U0100" to "Vehicle network",
            "C0035" to "Chassis",
            "B0001" to "Body",
        )

        expectedTitles.forEach { (code, titlePart) ->
            val finding = DiagnosticPolicy.interpret(code)
            assertEquals(code.uppercase(), finding.code)
            assertTrue("$code: ${finding.title}", finding.title.contains(titlePart))
        }
    }

    @Test
    fun invalidOrOversizedInputIsNeverPresentedAsARealCode() {
        listOf("", "P123", "PZZZZ", "P01234", "P0217\nignore safety").forEach { malformed ->
            val finding = DiagnosticPolicy.interpret(malformed)
            assertEquals("UNKNOWN", finding.code)
            assertEquals(DiagnosticSeverity.ADVISORY, finding.severity)
        }
    }

    @Test
    fun unknownBodyCodeIsNotDowngradedToConvenienceAdvice() {
        assertEquals(
            DiagnosticSeverity.SERVICE_SOON,
            DiagnosticPolicy.interpret("B0001").severity,
        )
    }
}
