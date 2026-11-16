package com.siny.mobilebasic;

import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps the original request when a later clarification turn must fall back to local geocoding. */
final class DestinationFallbackQuery {
    private DestinationFallbackQuery() {}

    static String combine(String clarificationContext, String latestPlacePhrase) {
        Set<String> places = new LinkedHashSet<>();
        addParsedPlaces(places, clarificationContext);
        addParsedPlaces(places, latestPlacePhrase);
        return String.join(" ", places);
    }

    private static void addParsedPlaces(Set<String> places, String value) {
        if (value == null) return;
        for (String line : value.split("[\\r\\n]+")) {
            String phrase = line.replaceAll("\\s+", " ").trim();
            if (phrase.isEmpty()) continue;
            DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(phrase);
            if (parsed.isUsable()) places.add(parsed.destination);
        }
    }
}
