package com.siny.mobilebasic;

import java.util.List;

/** Builds local speech from grounded NAVER results so generated audio cannot alter names or addresses. */
final class LiveCandidateSpeechBuilder {
    private LiveCandidateSpeechBuilder() {}

    static String confirmation(String name, String address, boolean taxiMode) {
        String destination = safe(name) + ", " + safe(address);
        return taxiMode
                ? "검색한 곳은 " + destination + "입니다. 이곳으로 택시를 부를까요?"
                : "검색한 곳은 " + destination + "입니다. 이곳으로 대중교통 길 안내를 시작할까요?";
    }

    static String options(List<String> labels) {
        if (labels == null || labels.size() < 2) return "";
        StringBuilder speech = new StringBuilder("네이버에서 찾은 곳이 ")
                .append(labels.size()).append("곳이에요. ");
        for (int i = 0; i < labels.size(); i++) {
            speech.append(i + 1).append("번은 ").append(safe(labels.get(i))).append(". ");
        }
        return speech.append("원하시는 곳의 번호나 이름을 말씀해 주세요.").toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
