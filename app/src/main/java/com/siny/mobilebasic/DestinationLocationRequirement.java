package com.siny.mobilebasic;

import java.util.Locale;

/** Detects requests whose meaning depends on a known neighborhood (for example "nearby"). */
final class DestinationLocationRequirement {
    private static final String[] NEARBY = {"근처", "인근", "부근", "주변", "가까운", "가장가까운", "제일가까운"};
    private static final String[] CORRECTIONS = {
            "말고", "말구", "빼고", "빼구", "아니고", "아니라", "대신"};

    private DestinationLocationRequirement() {}

    static boolean requiresNeighborhood(String previousContext, String utterance) {
        String current = normalize(utterance);
        int correction = lastCorrectionPosition(current);
        if (correction >= 0) {
            int correctionEnd = correctionEnd(current, correction);
            if (!containsNearby(current.substring(correctionEnd))) return false;
        }
        return containsNearby(current) || containsNearby(normalize(previousContext));
    }

    static boolean requiresAreaForGenericPlaceType(String query) {
        return DestinationQueryGuard.isGenericPlaceType(query);
    }

    private static boolean containsNearby(String text) {
        for (String marker : NEARBY) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static int lastCorrectionPosition(String text) {
        int result = -1;
        for (String marker : CORRECTIONS) result = Math.max(result, text.lastIndexOf(marker));
        return result;
    }

    private static int correctionEnd(String text, int position) {
        for (String marker : CORRECTIONS) {
            if (text.startsWith(marker, position)) return position + marker.length();
        }
        return position;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
