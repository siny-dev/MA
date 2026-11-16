package com.siny.mobilebasic;

import java.util.Locale;
import java.util.List;

/** Classifies short spoken answers to MA's explicit destination confirmation. */
public final class DestinationConfirmationParser {
    public enum Decision { YES, NO, UNCLEAR }

    private DestinationConfirmationParser() {}

    public static Decision classify(String utterance) {
        if (utterance == null) return Decision.UNCLEAR;
        String value = utterance.trim().toLowerCase(Locale.KOREAN)
                .replaceAll("[.!?。！？,，~]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return Decision.UNCLEAR;

        String compact = value.replaceAll("\\s+", "");
        // "아니면" is usually a choice/question connector ("이곳일까요, 아니면 저곳일까요?"),
        // not a refusal. Treat it as unresolved before the broader "아니" rejection match.
        if (compact.contains("아니면") || compact.contains("아닐까요") || compact.contains("아니겠어요")) {
            return Decision.UNCLEAR;
        }
        if (compact.matches(".*(?:아니|아뇨|아닙니다|아니에요|틀렸|틀린|틀리|다른(?:후보|병원|의원|약국|의료기관|장소|곳|데|목적지)|말고|취소|그만|잘못|안맞).*")) {
            return Decision.NO;
        }
        if (compact.matches(".*(?:모르겠|글쎄|확실하지|긴가민가|것같|아마|잠깐|잠시|기다리|나중|다음에|아직|생각|고민|안돼|못하|어렵|힘들|바쁘).*")) {
            return Decision.UNCLEAR;
        }
        // A leading "네/예" is not enough: speech recognition can return a question or request
        // to repeat immediately after it. Never start navigation or a taxi handoff for those.
        if (utterance.matches("(?s).*[?？].*")
                || compact.matches(".*(?:어디|어느|뭐라고|뭐|무엇|무슨|어떤|누구|왜|어떻게|몇|맞나요|맞죠|맞습니까|인가요|이죠|할까요|나요|습니까|정말|진짜|다시|못들|잘들|확인해|말씀해).*")) {
            return Decision.UNCLEAR;
        }
        if (isExplicitAffirmative(compact)) {
            return Decision.YES;
        }
        return Decision.UNCLEAR;
    }

    /** Resolve Android's N-best speech results without letting a conflicting transcript approve a route. */
    public static Decision classifyHypotheses(List<String> hypotheses) {
        if (hypotheses == null || hypotheses.isEmpty()) return Decision.UNCLEAR;
        Decision primary = Decision.UNCLEAR;
        int yesCount = 0;
        boolean hasNo = false;
        boolean hasYes = false;
        for (int i = 0; i < hypotheses.size(); i++) {
            Decision decision = classify(hypotheses.get(i));
            if (i == 0) primary = decision;
            if (decision == Decision.YES) {
                yesCount++;
                hasYes = true;
            } else if (decision == Decision.NO) {
                hasNo = true;
            }
        }
        if (hasYes && hasNo) return Decision.UNCLEAR;
        if (hasNo) return Decision.NO;
        if (primary == Decision.YES || yesCount >= 2) return Decision.YES;
        return Decision.UNCLEAR;
    }

    public static String representativeHypothesis(List<String> hypotheses, Decision decision) {
        if (hypotheses == null || decision == null || decision == Decision.UNCLEAR) return "";
        for (String hypothesis : hypotheses) {
            if (classify(hypothesis) == decision) return hypothesis;
        }
        return "";
    }

    private static boolean isExplicitAffirmative(String compact) {
        String acknowledgement = "(?:네|예|응|어)";
        String confirmation = "(?:맞아|맞아요|맞습니다|그래|그래요|그렇지요|그렇죠|그렇습니다|좋아|좋아요|좋습니다)";
        String action = "(?:(?:그렇게)?(?:해(?:줘|주세요|요|주십시오)?|하세요|시작해(?:줘|주세요|요)?|출발해(?:줘|주세요|요)?))";
        String destination = "(?:거기|거기로|그곳|그곳으로|이곳|이곳으로|그쪽|그쪽으로|"
                + "이주소|이주소로|그주소|그주소로|이장소|이장소로|그장소|그장소로)";
        String routeAction = "(?:안내(?:를)?(?:시작)?(?:해(?:줘|주세요|요)?|해주세요)?|"
                + "길안내(?:를)?(?:시작)?(?:해(?:줘|주세요|요)?|해주세요)?|"
                + "가(?:요|주세요|줘|자)?|출발(?:해(?:요|줘|주세요)?)?|"
                + "택시(?:를)?불러(?:줘|주세요|요)?)";
        boolean explicitNavigationCommand = compact.matches("^(?:" + acknowledgement + "|" + confirmation + ")"
                + destination + routeAction + "$");
        boolean explicitRouteAction = compact.matches("^" + acknowledgement + routeAction + "$")
                || compact.matches("^" + confirmation + routeAction + "$");
        return explicitNavigationCommand || explicitRouteAction
                || compact.matches("^" + acknowledgement + "(?:" + confirmation + "|" + action + ")$")
                || compact.matches("^" + confirmation + action + "?$");
    }
}
