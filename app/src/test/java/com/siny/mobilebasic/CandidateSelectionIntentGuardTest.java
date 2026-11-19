package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CandidateSelectionIntentGuardTest {
    private static final String AREA = "부산 금정구 서동 피부과";

    @Test public void genericAcknowledgementCannotSelectMatchingBusinessName() {
        assertCannotSelect("네", AREA, "네네치킨", "네네치킨 서동", "부산 금정구 서동 1");
        assertCannotSelect("네네", AREA, "네네치킨", "네네치킨 서동", "부산 금정구 서동 1");
        assertCannotSelect("맞아요", AREA, "맞아요의원", "맞아요의원 서동", "부산 금정구 서동 1");
    }

    @Test public void genericPointingAndPlaceCategoryCannotSelectFirstMatchingCandidate() {
        assertCannotSelect("거기로 가자", AREA, "거기로가자의원", "거기로가자의원 서동", "부산 금정구 서동 1");
        assertCannotSelect("네, 그곳으로 안내해 주세요", AREA,
                "그곳으로안내해주세요의원", "그곳으로안내해주세요 서동", "부산 금정구 서동 1");
        assertCannotSelect("서동 피부과", AREA, "이종호 피부과", "이종호 피부과 서동", "부산 금정구 서동 1");
        assertCannotSelect("가까운 피부과", AREA, "가까운피부과", "가까운피부과 서동", "부산 금정구 서동 1");
    }

    @Test public void distinctivePlaceNameRemainsSelectableWithAreaAndCategory() {
        assertTrue(CandidateSelectionIntentGuard.isDiscriminative("이종호 피부과", AREA));
        assertTrue(CandidateSelectionIntentGuard.isDiscriminative("서동 이종호 피부과", AREA));
        String spoken = "부산 금정구 서동에 있는 이종호 피부과로 안내해 주세요";
        String key = CandidateSelectionIntentGuard.discriminativePhrase(spoken, AREA);
        assertEquals("이종호", key);
        assertTrue(CandidateSelectionMatcher.matches(key, "이종호 피부과", "이종호 피부과 서동",
                "부산 금정구 서동 123-4"));
    }

    private static void assertCannotSelect(String utterance, String area, String name,
                                          String label, String address) {
        String key = CandidateSelectionIntentGuard.discriminativePhrase(utterance, area);
        assertFalse(CandidateSelectionIntentGuard.isDiscriminative(utterance, area));
        assertFalse(CandidateSelectionMatcher.matches(key, name, label, address));
    }
}
