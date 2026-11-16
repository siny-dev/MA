package com.siny.mobilebasic;

import java.util.Locale;

/** Matches a confirmed place against visible text before an accessibility action is allowed. */
final class DestinationScreenMatcher {
    private DestinationScreenMatcher() {}

    static boolean matches(String visibleText, String expectedName, String expectedAddress) {
        String screen = normalize(visibleText);
        String name = normalize(expectedName);
        if (name.isEmpty()) {
            return DestinationAddressMatcher.matchesFallbackAddress(visibleText, expectedAddress);
        }
        return name.length() >= 2 && screen.contains(name)
                && DestinationAddressMatcher.matchesComplete(visibleText, expectedAddress);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
