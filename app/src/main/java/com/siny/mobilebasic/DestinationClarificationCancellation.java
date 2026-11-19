package com.siny.mobilebasic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Recognizes short, explicit voice cancellations while a destination detail is being clarified. */
final class DestinationClarificationCancellation {
    private static final Set<String> CANCELLATIONS = new HashSet<>(Arrays.asList(
            "그만", "그만해", "그만해요", "그만하자", "취소", "취소해", "취소할래", "취소할래요", "안갈래", "안가",
            "찾지마", "찾지말아", "괜찮아", "됐어", "됐어요", "이제됐어", "다음에", "나중에"
    ));

    private DestinationClarificationCancellation() {}

    static boolean isCancellation(String utterance) {
        String compact = utterance == null ? "" : utterance.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{Punct}\\s]", "");
        return CANCELLATIONS.contains(compact)
                || compact.matches("^(?:아니|아니요|아뇨)(?:됐어|됐어요|그만|취소|안갈래|안가)$");
    }
}
