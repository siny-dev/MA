package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rejects destination speech results whose ranked alternatives materially disagree.
 * SpeechRecognizer ranking is not proof that the first phrase is the user's intent.
 */
final class DestinationSpeechHypothesisPolicy {
    private static final Set<String> FILLER_WORDS = new HashSet<>(Arrays.asList(
            "에", "에서", "의", "으로", "로", "까지", "근처", "인근", "주변", "부근", "쪽",
            "일대", "있는", "있어요", "있습니다", "계신", "계시는", "찾아", "찾아줘", "찾아요",
            "가자", "가요", "가고", "싶어", "싶어요", "주세요", "해주세요", "말이야", "말이에요",
            "다른", "새로운", "가까운", "가까이", "근처", "인근", "주변", "부근", "동네",
            "전문의", "전문", "데", "곳", "장소", "어디"
    ));

    private DestinationSpeechHypothesisPolicy() {}

    /** True when a single repeat is safer than silently searching the top ranked phrase. */
    static boolean requiresRepeat(List<String> hypotheses) {
        return requiresRepeat(hypotheses, "");
    }

    /** Compares candidates after carrying only the still-active clarification context. */
    static boolean requiresRepeat(List<String> hypotheses, String clarificationContext) {
        if (hypotheses == null || hypotheses.size() < 2) return false;
        Signature first = signature(hypotheses.get(0), clarificationContext);
        if (first == null) return false; // The normal parser will request a new phrase.
        for (int i = 1; i < hypotheses.size(); i++) {
            Signature alternate = signature(hypotheses.get(i), clarificationContext);
            if (alternate != null && !first.equals(alternate)) return true;
        }
        return false;
    }

    /** A neighborhood alone is not a precise POI request unless prior clarification supplies intent. */
    static boolean isNeighborhoodOnlyDestination(String phrase) {
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(phrase);
        if (!parsed.isUsable() || !DestinationQueryGuard.findTypes(parsed.destination).isEmpty()) {
            return false;
        }
        boolean hasNeighborhood = false;
        for (String area : DestinationQueryGuard.findAreas(parsed.destination)) {
            if (DestinationQueryGuard.areaLevel(area) >= 3) hasNeighborhood = true;
        }
        if (!hasNeighborhood) return false;
        String remainder = DestinationQueryGuard.removeAreaTokens(parsed.destination,
                DestinationQueryGuard.findAreas(parsed.destination));
        return meaningfulTerms(remainder).isEmpty();
    }

    static boolean hasIndependentNamedTarget(String phrase) {
        Signature signature = signature(phrase);
        return signature != null && !signature.terms.isEmpty();
    }

    private static Signature signature(String phrase) {
        return signature(phrase, "");
    }

    private static Signature signature(String phrase, String clarificationContext) {
        if (DestinationClarificationCancellation.isCancellation(phrase)) {
            return new Signature(Collections.emptySet(), Collections.emptySet(),
                    Collections.singleton("__cancel__"));
        }
        String context = clarificationContext == null ? "" : clarificationContext.trim();
        String effectivePhrase = context.isEmpty() ? phrase
                : DestinationQueryGuard.preserveExplicitIntent(context, phrase, phrase);
        if (DestinationClarificationCancellation.isCancellation(effectivePhrase)) {
            return new Signature(Collections.emptySet(), Collections.emptySet(),
                    Collections.singleton("__cancel__"));
        }
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(effectivePhrase);
        if (!parsed.isUsable()) return null;

        Set<String> areas = new TreeSet<>();
        for (String area : DestinationQueryGuard.findAreas(parsed.destination)) {
            areas.add(DestinationQueryGuard.areaLevel(area) + ":"
                    + DestinationQueryGuard.canonicalAreaToken(area));
        }

        Set<String> types = new TreeSet<>(DestinationQueryGuard
                .distinctSpecificPlaceTypes(parsed.destination));
        String remainder = DestinationQueryGuard.removeAreaTokens(parsed.destination,
                DestinationQueryGuard.findAreas(parsed.destination));
        for (String type : types) remainder = remainder.replaceAll(java.util.regex.Pattern.quote(type), " ");
        Set<String> terms = meaningfulTerms(remainder);
        return new Signature(areas, types, terms);
    }

    private static Set<String> meaningfulTerms(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{Punct}\\s·]+", " ").trim();
        if (normalized.isEmpty()) return Collections.emptySet();
        Set<String> terms = new TreeSet<>();
        for (String raw : normalized.split("\\s+")) {
            String term = raw.replaceAll("(?:에서|으로|까지|에게|에|의|은|는|이|가|을|를|요)$", "");
            if (!term.isEmpty() && !FILLER_WORDS.contains(raw) && !FILLER_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static final class Signature {
        private final List<String> areas;
        private final List<String> types;
        private final List<String> terms;

        Signature(Set<String> areas, Set<String> types, Set<String> terms) {
            this.areas = new ArrayList<>(areas);
            this.types = new ArrayList<>(types);
            this.terms = new ArrayList<>(terms);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Signature)) return false;
            Signature that = (Signature) other;
            return areas.equals(that.areas) && types.equals(that.types) && terms.equals(that.terms);
        }

        @Override public int hashCode() {
            int result = areas.hashCode();
            result = 31 * result + types.hashCode();
            return 31 * result + terms.hashCode();
        }

    }
}
