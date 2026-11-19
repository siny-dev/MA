package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps an ambiguous-place search from being repeated until the user supplies a district. */
final class RepeatedAmbiguousDestinationGuard {
    enum QueryAction {
        NOT_TRACKED,
        NEW_QUERY,
        ASK_KNOWN_DISTRICTS,
        USE_OBSERVED_DISTRICT,
        SEARCH_EXPLICIT_DISTRICT,
        STOP
    }

    private static final int MAX_UNRESOLVED_DISTRICT_ANSWERS = 2;
    private static final int MAX_UNRECOGNIZED_DISTRICT_ANSWERS_PER_ENTRY = 2;
    private static final Set<String> NON_INTENT_WORDS = new LinkedHashSet<>();
    private static final Set<String> MEANINGFUL_UNRESOLVED_ANSWERS = new LinkedHashSet<>();

    static {
        Collections.addAll(NON_INTENT_WORDS,
                "찾아", "찾아줘", "찾아요", "찾습니다", "가자", "가고", "싶어", "싶어요",
                "안내", "안내해", "해주세요", "해줘", "해요", "주세요", "좀", "있는", "있어요",
                "있나요", "어디", "근처", "가까운", "주차", "주차장", "전문의", "전문", "계단",
                "엘리베이터", "당일", "진료", "영업", "열린", "열려", "가능", "가능한", "좋은",
                "곳", "으로", "에서", "이요", "요", "말고", "다른", "그냥", "빨리");
        Collections.addAll(MEANINGFUL_UNRESOLVED_ANSWERS,
                "몰라", "모르겠", "모르겠어요", "모르겠다", "모르겠는데", "잘몰라",
                "아니", "싫어", "그만", "취소", "다른곳", "다른데", "다른구");
    }

    private String intentKey = "";
    private String query = "";
    private String requestedAreaQuery = "";
    private final ArrayList<String> districts = new ArrayList<>();
    private int unresolvedDistrictAnswers;
    private int unrecognizedDistrictAnswers;
    private boolean fixedNoticeConsumed;
    private String selectedDistrict = "";

    boolean remember(String searchQuery, String areaQuery, List<String> candidateAddresses) {
        List<String> found = DestinationDistrictAmbiguity.districtsFromAddresses(candidateAddresses);
        if (found.size() < 2 || searchQuery == null || searchQuery.trim().isEmpty()) {
            clear();
            return false;
        }
        query = searchQuery.trim().replaceAll("\\s+", " ");
        requestedAreaQuery = areaQuery == null ? "" : areaQuery.trim();
        intentKey = intentKey(searchQuery);
        districts.clear();
        districts.addAll(found);
        unresolvedDistrictAnswers = 0;
        unrecognizedDistrictAnswers = 0;
        fixedNoticeConsumed = false;
        selectedDistrict = "";
        if (intentKey.isEmpty()) {
            clear();
            return false;
        }
        return true;
    }

    QueryAction onDestinationQuery(String searchQuery, String areaQuery) {
        selectedDistrict = "";
        if (intentKey.isEmpty()) return QueryAction.NOT_TRACKED;
        if (!sameIntent(searchQuery, areaQuery)) {
            clear();
            return QueryAction.NEW_QUERY;
        }

        String explicitDistrict = explicitDistrict(searchQuery, areaQuery);
        if (!explicitDistrict.isEmpty()) {
            selectedDistrict = explicitDistrict;
            return containsDistrict(explicitDistrict)
                    ? QueryAction.USE_OBSERVED_DISTRICT
                    : QueryAction.SEARCH_EXPLICIT_DISTRICT;
        }
        if (unresolvedDistrictAnswers >= MAX_UNRESOLVED_DISTRICT_ANSWERS) {
            return QueryAction.STOP;
        }
        // A new entry for the same blocked query gets one fresh recognition retry. Failed
        // recognition never spends the separate, meaningful-refusal budget.
        unrecognizedDistrictAnswers = 0;
        return QueryAction.ASK_KNOWN_DISTRICTS;
    }

    /** Compatibility overload for callers/tests that only have the interpreted query. */
    QueryAction onDestinationQuery(String searchQuery) {
        return onDestinationQuery(searchQuery, "");
    }

    String selectedDistrict() {
        return selectedDistrict;
    }

    List<String> districts() {
        return new ArrayList<>(districts);
    }

    String districtQuestion() {
        return DestinationDistrictClarification.question(requestedAreaQuery, districts);
    }

    String matchDistrict(String utterance) {
        String supplied = districtFromAnswer(utterance);
        return isObservedDistrict(supplied) ? supplied : "";
    }

    boolean hasExplicitDistrict(String utterance) {
        return !districtFromAnswer(utterance).isEmpty();
    }

    String districtFromAnswer(String utterance) {
        return explicitDistrict(utterance, utterance);
    }

    boolean isObservedDistrict(String district) {
        return containsDistrict(district);
    }

    boolean isClarificationExhausted() {
        return unresolvedDistrictAnswers >= MAX_UNRESOLVED_DISTRICT_ANSWERS;
    }

    boolean isDistrictOnlyAnswer(String utterance) {
        return hasExplicitDistrict(utterance) && terms(utterance).isEmpty();
    }

    boolean shouldHandleTextAsDistrictAnswer(String utterance) {
        return isSearchBlocked() && isDistrictOnlyAnswer(utterance);
    }

    String queryWithDistrict(String district) {
        return appendArea(query, district);
    }

    String areaQueryWithDistrict(String district) {
        return appendArea(requestedAreaQuery, district);
    }

    void noteMeaningfulUnresolvedDistrictAnswer() {
        if (!intentKey.isEmpty()) unresolvedDistrictAnswers++;
    }

    boolean isMeaningfulUnresolvedDistrictAnswer(String utterance) {
        String compact = utterance == null ? "" : utterance.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{Punct}\\s]", "");
        if (compact.isEmpty()) return false;
        for (String phrase : MEANINGFUL_UNRESOLVED_ANSWERS) {
            if (compact.contains(phrase)) return true;
        }
        return false;
    }

    /** Returns false after the second recognition failure in this entry, without blocking the intent. */
    boolean noteUnrecognizedDistrictAnswer() {
        if (!intentKey.isEmpty()) unrecognizedDistrictAnswers++;
        return unrecognizedDistrictAnswers < MAX_UNRECOGNIZED_DISTRICT_ANSWERS_PER_ENTRY;
    }

    int unresolvedDistrictAnswerCount() {
        return unresolvedDistrictAnswers;
    }

    boolean shouldAnnounceFixedNotice() {
        if (fixedNoticeConsumed) return false;
        fixedNoticeConsumed = true;
        return true;
    }

    boolean isSearchBlocked() {
        return !intentKey.isEmpty();
    }

    private boolean sameIntent(String candidateQuery, String candidateAreaQuery) {
        String candidateKey = intentKey(candidateQuery);
        if (candidateKey.isEmpty()) return false;
        Set<String> currentTypes = DestinationQueryGuard.findTypes(candidateQuery);
        Set<String> rememberedTypes = DestinationQueryGuard.findTypes(query);

        if (!currentTypes.isEmpty() && !rememberedTypes.isEmpty()
                && Collections.disjoint(currentTypes, rememberedTypes)) return false;
        Set<String> currentNamedTerms = termsExcludingPlaceTypes(candidateQuery, currentTypes);
        Set<String> rememberedNamedTerms = termsExcludingPlaceTypes(query, rememberedTypes);
        if (!rememberedNamedTerms.containsAll(currentNamedTerms)) return false;

        LinkedHashSet<String> oldAreas = areas(query, requestedAreaQuery);
        LinkedHashSet<String> newAreas = areas(candidateQuery, candidateAreaQuery);
        for (int level : new int[]{1, 3}) {
            Set<String> oldAtLevel = canonicalAreasAtLevel(oldAreas, level);
            Set<String> newAtLevel = canonicalAreasAtLevel(newAreas, level);
            if (!oldAtLevel.isEmpty() && !newAtLevel.isEmpty()
                    && Collections.disjoint(oldAtLevel, newAtLevel)) return false;
        }

        // A same place type with added/omitted conditions or a shorter locality phrase is
        // still the unresolved destination. A different locality/category starts a new search.
        return true;
    }

    private boolean containsDistrict(String district) {
        String canonical = DestinationQueryGuard.canonicalAreaToken(district);
        for (String observed : districts) {
            if (canonical.equals(DestinationQueryGuard.canonicalAreaToken(observed))) return true;
        }
        return false;
    }

    private static String explicitDistrict(String first, String second) {
        LinkedHashSet<String> areas = areas(first, second);
        String match = "";
        for (String area : areas) {
            if (DestinationQueryGuard.areaLevel(area) != 2) continue;
            if (!match.isEmpty()
                    && !DestinationQueryGuard.canonicalAreaToken(match)
                    .equals(DestinationQueryGuard.canonicalAreaToken(area))) return "";
            match = area;
        }
        return match;
    }

    private static LinkedHashSet<String> areas(String first, String second) {
        LinkedHashSet<String> result = DestinationQueryGuard.findAreas(first == null ? "" : first);
        result.addAll(DestinationQueryGuard.findAreas(second == null ? "" : second));
        return result;
    }

    private static Set<String> canonicalAreasAtLevel(Set<String> areas, int level) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String area : areas) {
            if (DestinationQueryGuard.areaLevel(area) == level) {
                result.add(DestinationQueryGuard.canonicalAreaToken(area));
            }
        }
        return result;
    }

    private static String intentKey(String value) {
        ArrayList<String> words = new ArrayList<>(terms(value));
        Collections.sort(words);
        return String.join("|", words);
    }

    private static Set<String> termsExcludingPlaceTypes(String value, Set<String> types) {
        LinkedHashSet<String> result = new LinkedHashSet<>(terms(value));
        result.removeAll(types);
        return result;
    }

    private static Set<String> terms(String value) {
        String text = value == null ? "" : value;
        LinkedHashSet<String> areaTokens = DestinationQueryGuard.findAreas(text);
        text = DestinationQueryGuard.removeAreaTokens(text, areaTokens);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.KOREAN)
                .replaceAll("[^가-힣a-z0-9]+", " ").trim().split("\\s+")) {
            if (!word.isEmpty() && !NON_INTENT_WORDS.contains(word)) found.add(word);
        }
        return found;
    }

    private static String appendArea(String original, String district) {
        String value = original == null ? "" : original.trim();
        if (district == null || district.trim().isEmpty()) return value;
        String canonical = DestinationQueryGuard.canonicalAreaToken(district);
        for (String existing : DestinationQueryGuard.findAreas(value)) {
            if (canonical.equals(DestinationQueryGuard.canonicalAreaToken(existing))) return value;
        }
        return (value.isEmpty() ? "" : value + " ") + district.trim();
    }

    void clear() {
        intentKey = "";
        query = "";
        requestedAreaQuery = "";
        districts.clear();
        unresolvedDistrictAnswers = 0;
        unrecognizedDistrictAnswers = 0;
        fixedNoticeConsumed = false;
        selectedDistrict = "";
    }
}
