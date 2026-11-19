package com.siny.mobilebasic;

import java.util.LinkedHashSet;
import java.util.Locale;

/** Rejects generic acknowledgements, area names, and place categories as candidate identities. */
final class CandidateSelectionIntentGuard {
    private CandidateSelectionIntentGuard() {}

    /**
     * Candidate matching allows a distinctive name fragment, but not a generic answer that
     * happens to be contained in a business name (for example "네" matching "네네치킨").
     */
    static boolean isDiscriminative(String utterance, String requestedAreaContext) {
        return !discriminativePhrase(utterance, requestedAreaContext).isEmpty();
    }

    /** Returns only the place-identity words that can safely be matched to a candidate. */
    static String discriminativePhrase(String utterance, String requestedAreaContext) {
        String phrase = normalize(CandidateSelectionParser.relevantPhrase(utterance));
        if (phrase.isEmpty()) return "";
        LinkedHashSet<String> areas = DestinationQueryGuard.effectiveAreas(
                requestedAreaContext, utterance);
        for (String area : areas) phrase = phrase.replace(normalize(area), "");
        LinkedHashSet<String> types = DestinationQueryGuard.findTypes(utterance);
        for (String type : types) phrase = phrase.replace(normalize(type), "");

        phrase = phrase.replaceFirst("^(?:네네|네|예예|예|응|어|맞아요|맞아|맞습니다|"
                + "그래요|그래|좋아요|좋아)", "");
        phrase = phrase.replaceAll("(?:거기로|거기|그곳으로|그곳|이곳으로|이곳|"
                + "저기로|저기|여기로|여기|가까운곳|가까운|근처에|근처|주변에|주변)", "");
        phrase = phrase.replaceAll("(?:에있는곳|에있는|에서|근처의|주변의)", "");
        phrase = phrase.replaceAll("(?:로가고싶어요|으로가고싶어요|에가고싶어요|"
                + "로가고싶어|으로가고싶어|에가고싶어|로안내(?:시작)?(?:해줘|해주세요|해요)?|"
                + "으로안내(?:시작)?(?:해줘|해주세요|해요)?|에안내(?:시작)?(?:해줘|해주세요|해요)?|"
                + "로가자|으로가자|에가자|로가요|으로가요|에가요|가고싶어요|가고싶어|"
                + "가주세요|가자|가요|안내(?:시작)?(?:해줘|해주세요|해요)?|"
                + "출발(?:해요|해주세요)?|추천해줘|추천해주세요|추천해요)$", "");
        phrase = phrase.replaceAll("^(?:의|에|로|으로|에서)+|(?:의|에|로|으로|에서|요|이요)+$", "");
        phrase = phrase.replaceAll("[^가-힣a-z0-9]", "");
        if (phrase.isEmpty() || DestinationQueryGuard.isGenericPlaceType(phrase)) return "";
        return phrase;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
