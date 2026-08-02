package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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

    @Test
    fun typedSharedAndFavoriteDestinationsAreNormalizedAndBounded() {
        val typed = checkNotNull(
            normalizeDestination(
                "  North   Guwahati\u0000  ",
                DestinationSource.TYPED,
            ),
        )
        assertEquals("North Guwahati", typed.label)
        assertEquals("geo:0,0?q=North%20Guwahati", destinationMapUri(typed))

        val shared = checkNotNull(
            normalizeDestination(
                "Workshop",
                DestinationSource.SHARED,
                latitude = 26.1445,
                longitude = 91.7362,
            ),
        )
        assertTrue(destinationShareText(shared).contains("geo:26.1445,91.7362"))
        assertNull(
            normalizeDestination(
                "Invalid",
                DestinationSource.SHARED,
                latitude = 100.0,
                longitude = 91.0,
            ),
        )

        assertEquals(
            1,
            favoriteDestinations(
                listOf(typed, typed.copy(source = DestinationSource.SHARED)),
            ).size,
        )
    }

    @Test
    fun providerIndependentPlacesStayFilteredAndOfferAllServiceChoices() {
        val origin = GeoPoint(26.1445, 91.7362)
        val fuel = NearbyPlacesTestData.provider.search(
            NearbyPlaceSearch(origin, NearbyPlaceKind.FUEL_STATION),
        )
        val services = NearbyPlacesTestData.provider.search(
            NearbyPlaceSearch(origin, NearbyPlaceKind.SERVICE_CENTER),
        )
        val choices = serviceCenterChoices(services)

        assertEquals(1, fuel.size)
        assertEquals(2, choices.size)
        assertEquals(PlaceAvailability.OPEN, choices.first().availability)
        assertEquals(PlaceAvailability.CLOSED, choices.last().availability)
        assertTrue(choices.all { it.mapQuery.contains(it.name) })
    }
}
