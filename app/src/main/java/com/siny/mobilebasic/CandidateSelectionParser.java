package com.siny.mobilebasic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a spoken choice without silently taking the first option in a correction. */
final class CandidateSelectionParser {
    static final int NO_ORDINAL = -1;
    static final int AMBIGUOUS = -2;
    private static final String[] CORRECTIONS = {
            "아닙니다", "아니에요", "아니요", "아뇨", "아니고", "아니라",
            "아닌", "말고", "다른", "아니"
    };
    private static final String[][] KOREAN_ORDINAL_ALIASES = {
            {"첫번째", "첫째"},
            {"두번째", "둘째"},
            {"세번째", "셋째"},
            {"네번째", "넷째"},
            {"다섯번째", "다섯째"}
    };
    private static final Pattern NUMERIC_ORDINAL = Pattern.compile("(?<!\\d)(\\d+)(번|번째)(?!\\d)");
    private static final String[] PLACE_DESCRIPTORS = {
            "출구", "번지", "호선", "도로", "거리", "층"
    };

    private CandidateSelectionParser() {}

    static String relevantPhrase(String utterance) {
        String value = normalize(utterance);
        int correctionStart = -1;
        int correctionEnd = -1;
        for (String correction : CORRECTIONS) {
            int position = value.lastIndexOf(correction);
            if (position > correctionStart || position == correctionStart
                    && position >= 0 && correction.length() > correctionEnd - correctionStart) {
                correctionStart = position;
                correctionEnd = position < 0 ? -1 : position + correction.length();
            }
        }
        return correctionStart < 0 ? value : value.substring(correctionEnd);
    }

    static int findExplicitOrdinal(String value, int choiceCount) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || choiceCount <= 0) return NO_ORDINAL;
        if (normalized.matches("^[1-9]$")) {
            int onlyChoice = Integer.parseInt(normalized) - 1;
            return onlyChoice < choiceCount ? onlyChoice : AMBIGUOUS;
        }

        int found = NO_ORDINAL;
        Matcher numeric = NUMERIC_ORDINAL.matcher(normalized);
        while (numeric.find()) {
            String numericText = numeric.group(1);
            if (numericText.length() > 1 && numericText.charAt(0) == '0') return AMBIGUOUS;
            int index;
            try {
                index = Integer.parseInt(numericText) - 1;
            } catch (NumberFormatException ignored) {
                return AMBIGUOUS;
            }
            if (index < 0 || index >= choiceCount) return AMBIGUOUS;
            if (isPlaceDescriptorAfter(normalized, numeric.end())
                    || "번".equals(numeric.group(2))
                    && isNumericAddressSuffix(normalized, numeric.end())) return AMBIGUOUS;
            if (found != NO_ORDINAL && found != index) return AMBIGUOUS;
            found = index;
        }

        for (int i = 0; i < KOREAN_ORDINAL_ALIASES.length; i++) {
            for (String token : KOREAN_ORDINAL_ALIASES[i]) {
                int position = normalized.indexOf(token);
                if (position < 0) continue;
                if (i >= choiceCount) return AMBIGUOUS;
                if (isPlaceDescriptorAfter(normalized, position + token.length())) return AMBIGUOUS;
                if (found != NO_ORDINAL && found != i) return AMBIGUOUS;
                found = i;
            }
        }
        return found;
    }

    private static boolean isPlaceDescriptorAfter(String value, int offset) {
        String remainder = value.substring(Math.min(offset, value.length()));
        for (String descriptor : PLACE_DESCRIPTORS) {
            if (remainder.startsWith(descriptor)) return true;
        }
        return false;
    }

    private static boolean isNumericAddressSuffix(String value, int offset) {
        String remainder = value.substring(Math.min(offset, value.length()));
        return hasSuffix(remainder, "지", "에", "에서", "로", "의", "를", "가", "만", "는", "도")
                || hasSuffix(remainder, "길", "에", "에서", "로", "의", "을", "은", "는", "앞", "뒤", "쪽")
                || hasSuffix(remainder, "가", "로", "에", "에서", "근처", "앞", "뒤", "쪽");
    }

    private static boolean hasSuffix(String value, String base, String... endings) {
        if (!value.startsWith(base)) return false;
        String remainder = value.substring(base.length());
        if (remainder.isEmpty()) return true;
        for (String ending : endings) {
            if (remainder.startsWith(ending)) return true;
        }
        return false;
    }
    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
