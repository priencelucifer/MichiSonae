package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistanceTest {
    @Test
    fun mapSearchUsesAnExternalGeoIntent() {
        assertEquals(
            "geo:0,0?q=car%20service%20center%20open%20now",
            mapSearchUri("car service center open now"),
        )
    }

    @Test
    fun explanationCannotReplaceDeterministicSafetyPolicy() {
        val finding = DiagnosticPolicy.interpret("P0524")
        val explained = attachLocalExplanation(finding, "A simple local explanation.")

        assertSame(finding, explained.finding)
        assertEquals(DiagnosticSeverity.STOP_SAFELY, explained.finding.severity)
        assertTrue(localGemmaPrompt(finding).contains(finding.safeAction))
    }

    @Test
    fun voiceCommandRequiresTheWakeWord() {
        assertEquals(
            LocalVoiceCommand.NONE,
            parseLocalVoiceTranscript("Find fuel"),
        )
        assertEquals(
            LocalVoiceCommand.FIND_FUEL,
            parseLocalVoiceTranscript("MichiSonae, find fuel"),
        )
        assertEquals(
            LocalVoiceCommand.FIND_SERVICE,
            parseLocalVoiceTranscript("michisonae find a mechanic"),
        )
    }
}
