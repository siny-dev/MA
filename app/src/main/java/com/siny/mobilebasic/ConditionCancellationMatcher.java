package com.siny.mobilebasic;

import java.util.Locale;

/** Pure, locally testable cancellation matching for spoken condition updates. */
final class ConditionCancellationMatcher {
    private static final String[] TOPICS = {
            "parking", "specialist", "stairs", "ramp", "slope", "wheelchair",
            "elevator", "accessible_facility", "accessibility", "mobility", "hours", "distance"
    };

    private ConditionCancellationMatcher() {}

    enum AnswerPolarity { YES, NO, UNCLEAR }

    static boolean isKnownTopic(String topic) {
        if (topic == null) return false;
        for (String candidate : TOPICS) if (candidate.equals(topic)) return true;
        return false;
    }

    static boolean hasTopicCancellation(String clause, String topic) {
        return hasTopicCancellation(clause, topic, false);
    }

    static boolean hasTopicCancellation(String clause, String topic,
                                        boolean yesNoQuestionWasAskedForThisTopic) {
        String compact = compact(clause);
        if (!hasCancellationText(compact)
                && !(yesNoQuestionWasAskedForThisTopic
                && (classifyConditionAnswer(clause) == AnswerPolarity.YES
                || isOkayAnswerForTopic(clause, topic)))) return false;
        int topicStart = findTopic(compact, topic, 0);
        if (topicStart < 0) return false;

        int nextTopic = compact.length();
        for (String other : TOPICS) {
            if (topic.equals(other)) continue;
            int index = findTopic(compact, other, topicStart + 1);
            if (index >= 0 && index < nextTopic) nextTopic = index;
        }
        String ownSpan = compact.substring(topicStart, nextTopic);
        if (hasCancellationText(ownSpan)
                || (yesNoQuestionWasAskedForThisTopic
                && (classifyConditionAnswer(ownSpan) == AnswerPolarity.YES
                || isOkayAnswerForTopic(ownSpan, topic)))) return true;
        if (nextTopic >= compact.length()) return false;

        // A cancellation after a coordinated list applies to the preceding item only
        // when it belongs to the immediately following item's span.
        int nextTopicEnd = compact.length();
        for (String other : TOPICS) {
            if (topic.equals(other)) continue;
            int index = findTopic(compact, other, nextTopic + 1);
            if (index >= 0 && index < nextTopicEnd) nextTopicEnd = index;
        }
        if (!hasCancellationText(compact.substring(nextTopic, nextTopicEnd))) return false;

        String coordinatedPrefix = compact.substring(topicStart, nextTopic);
        return isCoordinatedTopicPrefix(coordinatedPrefix, topic);
    }

    private static boolean isCoordinatedTopicPrefix(String prefix, String topic) {
        String[] markers = {"하고", "랑", "와", "과", "및", "도"};
        for (String marker : markers) {
            if (!prefix.endsWith(marker)) continue;
            String subject = prefix.substring(0, prefix.length() - marker.length());
            for (String noun : topicNouns(topic)) {
                if (subject.endsWith(noun)) return true;
            }
        }
        return false;
    }

    private static String[] topicNouns(String topic) {
        switch (topic) {
            case "parking": return new String[]{"주차장", "주차", "발렛", "차댈", "세워둘"};
            case "specialist": return new String[]{"전문의"};
            case "stairs": return new String[]{"계단", "층계"};
            case "ramp": return new String[]{"경사로"};
            case "slope": return new String[]{"오르막", "경사"};
            case "wheelchair": return new String[]{"휠체어"};
            case "elevator": return new String[]{"엘리베이터", "승강기"};
            case "accessible_facility":
                return new String[]{"장애인화장실", "장애인전용화장실"};
            case "accessibility": return new String[]{"장애인편의", "이동편의", "접근성"};
            case "mobility": return new String[]{"보행", "걷기", "무릎", "다리"};
            case "hours": return new String[]{"진료시간", "영업시간", "진료중"};
            case "distance": return new String[]{"가까운", "가까", "멀리", "거리"};
            default: return new String[0];
        }
    }

    static boolean hasCancellationText(String compact) {
        return compact.contains("상관없") || compact.contains("필요없")
                || compact.contains("필요하지않") || compact.contains("안해도")
                || compact.contains("안봐도") || compact.contains("빼줘")
                || compact.contains("빼도돼") || compact.contains("안중요해")
                || compact.contains("없어도돼") || compact.contains("안불편")
                || compact.contains("는됐어")
                || compact.contains("은됐어") || compact.contains("는됐다")
                || compact.contains("은됐다") || compact.contains("는됐고")
                || compact.contains("은됐고") || compact.contains("됐고")
                || compact.contains("있어도돼") || compact.contains("않아도돼");
    }

    static boolean isNeutralOkayExpression(String clause, String topic) {
        String compact = compact(clause);
        int start = findTopic(compact, topic, 0);
        if (start < 0) return false;
        int end = compact.length();
        for (String other : TOPICS) {
            if (topic.equals(other)) continue;
            int index = findTopic(compact, other, start + 1);
            if (index >= 0 && index < end) end = index;
        }
        return isNeutralOkayEvidence(compact.substring(start, end));
    }

    static boolean isStandaloneNeutralOkayExpression(String utterance, String topic) {
        String raw = utterance == null ? "" : utterance.trim();
        if (raw.endsWith("?") || raw.endsWith("？")) return false;
        String compact = compact(raw);
        int start = findTopic(compact, topic, 0);
        if (start != 0) return false;
        for (String other : TOPICS) {
            if (!topic.equals(other) && findTopic(compact, other, 1) >= 0) return false;
        }
        return isNeutralOkayEvidence(compact);
    }

    static boolean isStandaloneNeutralOkayReply(String utterance) {
        String raw = utterance == null ? "" : utterance.trim();
        if (raw.endsWith("?") || raw.endsWith("？")) return false;
        String compact = compact(raw);
        if (compact.isEmpty() || !isNeutralOkayEvidence(compact)) return false;
        for (String topic : TOPICS) {
            if (findTopic(compact, topic, 0) >= 0) return false;
        }
        return true;
    }

    static boolean isNeutralOkayEvidence(String text) {
        String compact = compact(text);
        if (compact.contains("괜찮은데")) return true;
        if (isPositiveOkayQuality(compact)) return false;
        return hasOkayCancellation(compact);
    }

    static boolean isOkayAnswer(String text) {
        return classifyConditionAnswer(text) == AnswerPolarity.YES
                && hasOkayCancellation(compact(text));
    }

    static AnswerPolarity classifyConditionAnswer(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.endsWith("?") || raw.endsWith("？")) return AnswerPolarity.UNCLEAR;
        String compact = compact(text);
        if (compact.isEmpty() || compact.contains("괜찮은데") || compact.contains("괜찮고")) {
            return AnswerPolarity.UNCLEAR;
        }
        if (compact.contains("빼지마") || compact.contains("빼지말")
                || compact.contains("빼주지마") || compact.contains("빼주지말")
                || compact.contains("없애지마") || compact.contains("지우지마")
                || compact.contains("그대로두") || compact.contains("그대로놔")) {
            return AnswerPolarity.NO;
        }
        boolean directRemoval = compact.equals("빼") || compact.equals("없애")
                || compact.equals("지워") || compact.endsWith("빼줘")
                || compact.endsWith("빼주세요") || compact.endsWith("빼줄래")
                || compact.endsWith("빼줄래요") || compact.endsWith("없애줘")
                || compact.endsWith("없애주세요") || compact.endsWith("지워줘")
                || compact.endsWith("지워주세요");
        if (directRemoval && (compact.startsWith("아니") || compact.startsWith("아냐"))) {
            return AnswerPolarity.UNCLEAR;
        }
        if (directRemoval) {
            return AnswerPolarity.YES;
        }
        switch (compact) {
            case "네": case "예": case "응": case "어": case "그래":
            case "맞아": case "맞아요": case "맞습니다":
            case "괜찮아": case "괜찮아요": case "괜찮습니다":
            case "네괜찮아": case "네괜찮아요": case "예괜찮아요":
            case "빼줘": case "빼주세요": case "그거빼줘": case "그것빼줘":
            case "빼도돼": case "빼도돼요": case "빼도됩니다":
                return AnswerPolarity.YES;
            case "아니": case "아니요": case "아냐": case "아니야":
            case "싫어": case "싫어요": case "안돼": case "안돼요":
            case "그대로둬": case "그대로둬요": case "그대로두세요":
            case "빼지마": case "빼지마요": case "빼지마세요":
                return AnswerPolarity.NO;
            default:
                return AnswerPolarity.UNCLEAR;
        }
    }

    private static boolean isPositiveOkayQuality(String compact) {
        int okay = compact.indexOf("괜찮은");
        if (okay < 0) return false;
        String tail = compact.substring(okay + "괜찮은".length());
        return tail.contains("곳") || tail.contains("병원") || tail.contains("의원")
                || tail.contains("피부과") || tail.contains("약국") || tail.contains("시장")
                || tail.contains("가게");
    }

    private static boolean isOkayAnswerForTopic(String clauseOrSpan, String topic) {
        String raw = clauseOrSpan == null ? "" : clauseOrSpan.trim();
        if (raw.endsWith("?") || raw.endsWith("？")) return false;
        String compact = compact(raw);
        int start = findTopic(compact, topic, 0);
        if (start < 0) return false;
        int end = compact.length();
        for (String other : TOPICS) {
            if (topic.equals(other)) continue;
            int index = findTopic(compact, other, start + 1);
            if (index >= 0 && index < end) end = index;
        }
        String ownSpan = compact.substring(start, end);
        return ownSpan.matches(".*(?:괜찮아|괜찮아요|괜찮습니다|괜찮다)$");
    }

    private static boolean hasOkayCancellation(String compact) {
        return compact.endsWith("괜찮") || compact.contains("괜찮아")
                || compact.contains("괜찮고") || compact.contains("괜찮아요")
                || compact.contains("괜찮습니다") || compact.contains("괜찮다")
                || compact.contains("괜찮네요") || compact.contains("괜찮죠")
                || compact.contains("괜찮아도");
    }

    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }

    private static int findTopic(String compact, String topic, int from) {
        String[] tokens;
        switch (topic) {
            case "parking": tokens = new String[]{"주차", "발렛", "차댈", "세워둘"}; break;
            case "specialist": tokens = new String[]{"전문의"}; break;
            case "stairs": tokens = new String[]{"계단", "층계"}; break;
            case "ramp": tokens = new String[]{"경사로"}; break;
            case "slope": tokens = new String[]{"오르막", "경사"}; break;
            case "wheelchair": tokens = new String[]{"휠체어"}; break;
            case "elevator": tokens = new String[]{"엘리베이터", "승강기"}; break;
            case "accessible_facility":
                tokens = new String[]{"장애인화장실", "장애인전용화장실"}; break;
            case "accessibility": tokens = new String[]{"장애인편의", "이동편의", "접근성"}; break;
            case "mobility": tokens = new String[]{"보행", "걷기", "무릎", "다리"}; break;
            case "hours": tokens = new String[]{"진료시간", "영업시간", "진료중"}; break;
            case "distance": tokens = new String[]{"가까운", "가까", "멀리", "거리"}; break;
            default: tokens = new String[0];
        }
        int earliest = -1;
        for (String token : tokens) {
            int index = compact.indexOf(token, Math.max(0, from));
            while ("slope".equals(topic) && "경사".equals(token)
                    && index >= 0 && compact.startsWith("경사로", index)) {
                index = compact.indexOf(token, index + token.length());
            }
            if (index >= 0 && (earliest < 0 || index < earliest)) earliest = index;
        }
        return earliest;
    }
}
