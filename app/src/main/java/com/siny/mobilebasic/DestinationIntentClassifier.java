package com.siny.mobilebasic;

import java.util.Locale;
import java.util.regex.Pattern;

/** Distinguishes a replacement destination request from an answer to a clarification prompt. */
final class DestinationIntentClassifier {
    private static final Pattern ADMIN_AREA = Pattern.compile(
            "(?:^|\\s)[가-힣0-9]+(?:특별시|광역시|시|군|구|읍|면|동|리)(?:에|의|에서|으로|까지|$|\\s)");
    private static final String[] PLACE_MARKERS = {
            "역", "병원", "의원", "피부과", "내과", "안과", "치과", "정형외과",
            "신경외과", "이비인후과", "소아과", "산부인과", "비뇨기과", "한의원", "약국",
            "시장", "마트", "학교", "공원", "터미널", "주민센터", "복지관",
            "공항", "은행", "도서관", "식당", "카페", "보건소", "센터"
    };
    private static final String[] REQUEST_MARKERS = {
            "찾아줘", "찾아 주세요", "찾아주세요", "안내해", "데려다", "가고 싶",
            "가고싶", "가자", "가려", "가 주세요", "가주세요", "목적지"
    };

    private DestinationIntentClassifier() {}

    static boolean isNewDestination(String utterance) {
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(utterance);
        if (!parsed.isUsable()) return false;
        String normalized = normalize(utterance);
        for (String marker : REQUEST_MARKERS) {
            if (normalized.contains(normalize(marker))) return true;
        }
        if (ADMIN_AREA.matcher(normalized).find()) return true;
        for (String marker : PLACE_MARKERS) {
            if (normalized.contains(normalize(marker))) return true;
        }
        return false;
    }

    /** True when a correction or clearly named destination replaces a candidate prompt. */
    static boolean isExplicitReplacementDuringConfirmation(String utterance) {
        String normalized = normalize(utterance);
        boolean correction = normalized.matches(".*(?:^|\\s|[,，、])(?:아니|아니요|아뇨|아니고|아니고요|아니라|아닌데|말고|말고요|대신)(?:\\s|[,，、]|$).*");
        if (!isNewDestination(utterance)) return false;
        DestinationConfirmationParser.Decision answer =
                DestinationConfirmationParser.classifyActiveYesNoAnswer(utterance);
        return correction || answer == DestinationConfirmationParser.Decision.UNCLEAR;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN).replaceAll("\\s+", " ").trim();
    }
}
