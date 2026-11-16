package com.siny.mobilebasic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches a visible address without accepting a longer building number as a prefix match. */
final class DestinationAddressMatcher {
    private static final Pattern ROAD_AND_NUMBER = Pattern.compile(
            "([가-힣A-Za-z0-9·-]+(?:대로|로|길))\\s*(\\d+(?:-\\d+)?)");

    private DestinationAddressMatcher() {}

    static boolean isUsableAddress(String address) {
        String normalized = normalize(address);
        return normalized.length() >= 6 && !normalized.equals(normalize("주소 정보 없음"));
    }

    static boolean hasStreetNumber(String address) {
        return address != null && ROAD_AND_NUMBER.matcher(address).find();
    }

    static String voiceRoadAndNumber(String address) {
        Matcher matcher = ROAD_AND_NUMBER.matcher(address == null ? "" : address);
        String result = "";
        while (matcher.find()) result = matcher.group(1) + " " + matcher.group(2);
        return result;
    }

    static boolean matchesFallbackAddress(String visibleText, String expectedAddress) {
        String screen = normalize(visibleText);
        String expected = normalize(expectedAddress);
        if (!isUsableAddress(expectedAddress)) return false;
        if (containsWithNumberBoundary(screen, expected)) return true;
        String district = "";
        String neighborhood = "";
        if (expectedAddress != null) {
            for (String part : expectedAddress.trim().split("\\s+")) {
                if (part.endsWith("구") || part.endsWith("군")) district = normalize(part);
                if (part.endsWith("동") || part.endsWith("읍")
                        || part.endsWith("면") || part.endsWith("리")) neighborhood = normalize(part);
            }
        }
        String roadAndNumber = normalize(voiceRoadAndNumber(expectedAddress));
        return !district.isEmpty() && screen.contains(district)
                && !neighborhood.isEmpty() && screen.contains(neighborhood)
                && !roadAndNumber.isEmpty()
                && containsWithNumberBoundary(screen, roadAndNumber);
    }

    static boolean matches(String visibleText, String expectedAddress) {
        String screen = normalize(visibleText);
        String address = normalize(expectedAddress);
        if (!isUsableAddress(expectedAddress)) return false;
        if (containsWithNumberBoundary(screen, address)) return true;

        Matcher road = ROAD_AND_NUMBER.matcher(expectedAddress == null ? "" : expectedAddress);
        String roadAndNumber = "";
        while (road.find()) roadAndNumber = normalize(road.group(1) + road.group(2));
        if (roadAndNumber.length() >= 6 && containsWithNumberBoundary(screen, roadAndNumber)) return true;

        for (int length = Math.min(14, address.length()); length >= 8; length--) {
            if (containsWithNumberBoundary(screen, address.substring(address.length() - length))) return true;
        }
        return false;
    }

    static boolean matchesComplete(String visibleText, String expectedAddress) {
        String screen = normalize(visibleText);
        String address = normalize(expectedAddress);
        if (!isUsableAddress(expectedAddress)) return false;
        if (containsWithNumberBoundary(screen, address)) return true;

        Matcher road = ROAD_AND_NUMBER.matcher(expectedAddress == null ? "" : expectedAddress);
        String roadAndNumber = "";
        while (road.find()) roadAndNumber = normalize(road.group(1) + road.group(2));
        return roadAndNumber.length() >= 6
                && containsWithNumberBoundary(screen, roadAndNumber);
    }

    private static boolean containsWithNumberBoundary(String screen, String expected) {
        if (expected.isEmpty()) return false;
        int from = 0;
        while (from <= screen.length() - expected.length()) {
            int position = screen.indexOf(expected, from);
            if (position < 0) return false;
            int end = position + expected.length();
            boolean expectedEndsInNumber = Character.isDigit(expected.charAt(expected.length() - 1));
            boolean numberContinues = expectedEndsInNumber && end < screen.length()
                    && (Character.isDigit(screen.charAt(end))
                    || screen.charAt(end) == '길' || screen.charAt(end) == '번');
            if (!numberContinues) return true;
            from = position + 1;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
