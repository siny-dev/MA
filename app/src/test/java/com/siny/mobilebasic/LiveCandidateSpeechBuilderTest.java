package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LiveCandidateSpeechBuilderTest {
    @Test public void singleCandidateReadsGroundedNameAndAddressAndRequestsFreshConsent() {
        assertEquals("검색한 곳은 이종호 피부과, 부산 금정구 서동 123입니다. 이곳으로 대중교통 길 안내를 시작할까요?",
                LiveCandidateSpeechBuilder.confirmation("이종호 피부과", "부산 금정구 서동 123", false));
        assertTrue(LiveCandidateSpeechBuilder.confirmation("이종호 피부과", "부산 금정구 서동 123", true)
                .endsWith("이곳으로 택시를 부를까요?"));
    }

    @Test public void multipleCandidateSpeechIncludesEachOrdinalNameAndAddress() {
        String speech = LiveCandidateSpeechBuilder.options(Arrays.asList(
                "이종호 피부과, 부산 금정구 서동 123",
                "서동중앙피부과, 부산 금정구 서동 456"));

        assertTrue(speech.contains("1번은 이종호 피부과, 부산 금정구 서동 123"));
        assertTrue(speech.contains("2번은 서동중앙피부과, 부산 금정구 서동 456"));
        assertTrue(speech.endsWith("원하시는 곳의 번호나 이름을 말씀해 주세요."));
        assertEquals("", LiveCandidateSpeechBuilder.options(Collections.singletonList("한 곳")));
    }
}
