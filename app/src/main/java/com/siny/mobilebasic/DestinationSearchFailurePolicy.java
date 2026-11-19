package com.siny.mobilebasic;

/** Distinguishes an empty NAVER search from results rejected by MA's safety checks. */
final class DestinationSearchFailurePolicy {
    enum Kind { NO_MATCHES, CONSTRAINT_MISMATCH, UNUSABLE_RESULTS }

    private DestinationSearchFailurePolicy() {}

    static Kind classify(NaverLocalSearchClient.Result result) {
        if (result == null || result.rawResultCount == 0) return Kind.NO_MATCHES;
        if (result.rejectedByRequestedConstraints > 0) return Kind.CONSTRAINT_MISMATCH;
        return Kind.UNUSABLE_RESULTS;
    }

    static String messageFor(NaverLocalSearchClient.Result result) {
        switch (classify(result)) {
            case CONSTRAINT_MISMATCH:
                return "검색 결과는 있었지만 요청한 동네나 장소 종류를 확인할 수 없어 후보에서 제외했어요.";
            case UNUSABLE_RESULTS:
                return "검색 결과의 주소나 위치를 확인할 수 없어 안내할 후보가 없어요.";
            case NO_MATCHES:
            default:
                return "그 지역에서 해당 종류의 장소를 찾지 못했어요.";
        }
    }

    /** Voice search gets one automatic clarification; the next failure must stop. */
    static boolean shouldRetryVoice(int failureCount) {
        return failureCount < 2;
    }
}
