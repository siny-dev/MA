package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationDistrictClarificationTest {
    @Test public void asksForDistrictOnceThenStopsInsteadOfRepeatingTheSameCandidateList() {
        DestinationDistrictClarification gate = new DestinationDistrictClarification();
        gate.begin(Arrays.asList("부산광역시 금정구 서동 123-4",
                "부산광역시 동래구 서동 234-5"));

        assertTrue(gate.isActive());
        assertEquals(DestinationDistrictClarification.UnmatchedAction.ASK_ONCE,
                gate.onUnmatchedAnswer());
        assertEquals(DestinationDistrictClarification.UnmatchedAction.STOP,
                gate.onUnmatchedAnswer());
    }

    @Test public void districtAnswerNarrowsCandidatesAndConflictingDistrictsStayUnresolved() {
        DestinationDistrictClarification gate = new DestinationDistrictClarification();
        gate.begin(Arrays.asList("부산광역시 금정구 서동 123-4",
                "부산광역시 동래구 서동 234-5"));

        assertEquals("금정구", gate.matchDistrict("금정구 서동이요"));
        assertFalse(DestinationDistrictClarification.addressMatchesDistrict(
                "부산광역시 동래구 서동 234-5", "금정구"));
        assertTrue(DestinationDistrictClarification.addressMatchesDistrict(
                "부산광역시 금정구 서동 123-4", "금정구"));
        assertEquals("", gate.matchDistrict("금정구 말고 동래구"));
    }

    @Test public void directQuestionNamesAvailableDistrictsAndTheSpokenNeighborhood() {
        assertEquals("서동 검색 결과가 여러 구에 있어요. 금정구 또는 동래구 중 어느 구인지 말씀해 주세요.",
                DestinationDistrictClarification.question("부산 서동 피부과",
                        Arrays.asList("금정구", "동래구")));
    }
}
