package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves a spoken topic selection only against the topics in the current question. */
final class ConditionTopicResolver {
    enum Kind { SELECTED, MULTIPLE, UNCLEAR }

    static final class Resolution {
        final Kind kind;
        final List<String> topicKeys;

        private Resolution(Kind kind, List<String> topicKeys) {
            this.kind = kind;
            this.topicKeys = Collections.unmodifiableList(new ArrayList<>(topicKeys));
        }
    }

    private ConditionTopicResolver() {}

    static Resolution resolve(String utterance, Set<String> allowedTopicKeys) {
        String compact = compact(utterance);
        if (compact.isEmpty() || allowedTopicKeys == null || allowedTopicKeys.isEmpty()) {
            return new Resolution(Kind.UNCLEAR, Collections.emptyList());
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String topic : allowedTopicKeys) {
            if (!ConditionCancellationMatcher.isKnownTopic(topic)) continue;
            for (String alias : aliases(topic)) {
                if (compact.contains(compact(alias))) {
                    selected.add(topic);
                    break;
                }
            }
        }

        // "경사로" names a ramp; its substring must not also select the broader slope topic.
        if (compact.contains("경사로") && allowedTopicKeys.contains("ramp")) {
            selected.remove("slope");
        }

        if (isStandaloneAllSelection(compact)) {
            selected.clear();
            for (String topic : allowedTopicKeys) {
                if (ConditionCancellationMatcher.isKnownTopic(topic)) selected.add(topic);
            }
        }
        if (selected.size() > 1 && (compact.contains("중") || compact.contains("말고")
                || compact.contains("아니고") || compact.contains("대신"))) {
            return new Resolution(Kind.UNCLEAR, Collections.emptyList());
        }
        if (selected.size() > 1) {
            return new Resolution(Kind.MULTIPLE, new ArrayList<>(selected));
        }
        if (selected.size() == 1) {
            return new Resolution(Kind.SELECTED, new ArrayList<>(selected));
        }
        return new Resolution(Kind.UNCLEAR, Collections.emptyList());
    }

    private static boolean isStandaloneAllSelection(String compact) {
        return compact.equals("둘다") || compact.equals("전부") || compact.equals("모두")
                || compact.equals("둘다빼") || compact.equals("둘다없애")
                || compact.equals("전부빼") || compact.equals("전부없애")
                || compact.equals("모두빼") || compact.equals("모두없애");
    }

    private static String[] aliases(String topic) {
        switch (topic) {
            case "parking": return new String[]{"주차장", "주차", "발렛", "차댈", "차댈곳", "세워둘"};
            case "specialist": return new String[]{"전문의", "전문 의사"};
            case "stairs": return new String[]{"계단", "층계"};
            case "ramp": return new String[]{"경사로", "램프"};
            case "slope": return new String[]{"오르막", "경사"};
            case "wheelchair": return new String[]{"휠체어"};
            case "elevator": return new String[]{"엘리베이터", "승강기"};
            case "accessible_facility":
                return new String[]{"장애인전용화장실", "장애인화장실", "장애인 화장실"};
            case "accessibility": return new String[]{"장애인편의", "이동편의", "접근성"};
            case "mobility": return new String[]{"보행", "걷기", "무릎", "다리"};
            case "hours": return new String[]{"진료시간", "영업시간", "진료 시간"};
            case "distance": return new String[]{"거리", "가까운", "가까이", "멀리"};
            default: return new String[0];
        }
    }

    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }
}
