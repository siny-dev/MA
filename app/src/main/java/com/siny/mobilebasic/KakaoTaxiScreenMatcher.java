package com.siny.mobilebasic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure safety gate for selecting a Kakao T result and tapping its single call action. */
final class KakaoTaxiScreenMatcher {
    private static final Set<String> CALL_LABELS = new HashSet<>(Arrays.asList(
            "호출하기", "택시호출하기", "택시부르기"));

    private KakaoTaxiScreenMatcher() {}

    static boolean destinationMatches(String visibleText, String expectedName, String expectedAddress) {
        String screen = normalize(visibleText);
        String name = normalize(expectedName);
        return name.length() >= 2 && screen.contains(name)
                && DestinationAddressMatcher.matches(visibleText, expectedAddress);
    }

    static boolean shouldTapCall(String visibleText, String expectedName, String expectedAddress,
                                 int matchingEnabledCallButtons) {
        return matchingEnabledCallButtons == 1
                && destinationMatches(visibleText, expectedName, expectedAddress);
    }

    static boolean isCallButtonLabel(String label) {
        return CALL_LABELS.contains(normalize(label));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
