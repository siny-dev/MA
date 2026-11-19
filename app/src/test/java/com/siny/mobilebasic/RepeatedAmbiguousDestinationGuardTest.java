package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RepeatedAmbiguousDestinationGuardTest {
    private static final String QUERY = "부산 서동 피부과";
    private static final String AREAS = "부산 서동";
    private static final java.util.List<String> ADDRESSES = Arrays.asList(
            "부산광역시 금정구 서동 123-4",
            "부산광역시 동래구 서동 234-5");

    @Test public void unknownAnswerReoffersOnceOnReentryThenShowsFixedBlockNotice() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();

        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery(QUERY, AREAS));
        guard.noteMeaningfulUnresolvedDistrictAnswer(); // "몰라요".
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery("서동 피부과", "서동"));
        guard.noteMeaningfulUnresolvedDistrictAnswer(); // second meaningful refusal.

        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.STOP,
                guard.onDestinationQuery(QUERY, AREAS));
        assertTrue(guard.shouldAnnounceFixedNotice());
        assertFalse(guard.shouldAnnounceFixedNotice());
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.STOP,
                guard.onDestinationQuery("피부과 부산 서동", "서동"));
    }

    @Test public void semanticTargetMatchesCityOmissionWordOrderAndAddedCondition() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();

        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery("피부과 서동", "서동"));
        guard.noteMeaningfulUnresolvedDistrictAnswer();
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery("피부과 주차 부산 서동", "부산 서동"));
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.NEW_QUERY,
                guard.onDestinationQuery("부산 명륜동 피부과", "부산 명륜동"));
    }

    @Test public void correctionWithObservedDistrictStaysBlockedAndNarrowsLocally() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();
        guard.onDestinationQuery(QUERY, AREAS);

        String district = guard.districtFromAnswer("서동 피부과 말고 금정구요");
        assertEquals("금정구", district);
        assertTrue(guard.isObservedDistrict(district));
        assertTrue(guard.isSearchBlocked());
    }

    @Test public void observedDistrictUsesPreviousResultsAndUnknownExplicitDistrictCanSearchOnce() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();

        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.USE_OBSERVED_DISTRICT,
                guard.onDestinationQuery("금정구 서동 피부과", "부산 금정구 서동"));
        assertEquals("금정구", guard.selectedDistrict());
        assertTrue(DestinationDistrictClarification.addressMatchesDistrict(ADDRESSES.get(0), "금정구"));
        assertFalse(DestinationDistrictClarification.addressMatchesDistrict(ADDRESSES.get(1), "금정구"));

        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.SEARCH_EXPLICIT_DISTRICT,
                guard.onDestinationQuery("해운대구 서동 피부과", "부산 해운대구 서동"));
        assertEquals("해운대구", guard.selectedDistrict());
        assertEquals("부산 서동 피부과 해운대구", guard.queryWithDistrict("해운대구"));
        assertEquals("부산 서동 해운대구", guard.areaQueryWithDistrict("해운대구"));
    }

    @Test public void blankAndMisheardDistrictDoNotConsumeMeaningfulRefusalBudget() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();
        guard.onDestinationQuery(QUERY, AREAS);

        assertEquals("", guard.districtFromAnswer(""));
        assertFalse(guard.isMeaningfulUnresolvedDistrictAnswer(""));
        assertTrue(guard.noteUnrecognizedDistrictAnswer());
        assertEquals(0, guard.unresolvedDistrictAnswerCount());
        assertEquals("", guard.districtFromAnswer("그 정거"));
        assertFalse(guard.isMeaningfulUnresolvedDistrictAnswer("그 정거"));
        assertFalse(guard.noteUnrecognizedDistrictAnswer());
        assertEquals(0, guard.unresolvedDistrictAnswerCount());
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery(QUERY, AREAS));

        assertTrue(guard.isMeaningfulUnresolvedDistrictAnswer("몰라요"));
        guard.noteMeaningfulUnresolvedDistrictAnswer();
        assertFalse(guard.isClarificationExhausted());
        assertTrue(guard.isMeaningfulUnresolvedDistrictAnswer("다른 데요"));
        guard.noteMeaningfulUnresolvedDistrictAnswer();
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.STOP,
                guard.onDestinationQuery(QUERY, AREAS));
        assertTrue(guard.isSearchBlocked());
    }

    @Test public void textInputUsesTheSameGuardAndExplicitDistrictIsAValidAnswer() {
        RepeatedAmbiguousDestinationGuard guard = rememberedGuard();

        assertTrue(guard.shouldHandleTextAsDistrictAnswer("금정구"));
        // Text and speech feed the same normalized query/area pair into this state guard.
        assertEquals(RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS,
                guard.onDestinationQuery("서동 피부과", "서동"));
        assertTrue(guard.shouldHandleTextAsDistrictAnswer("해운대구요"));
        assertEquals("해운대구", guard.districtFromAnswer("해운대구요"));
    }

    private static RepeatedAmbiguousDestinationGuard rememberedGuard() {
        RepeatedAmbiguousDestinationGuard guard = new RepeatedAmbiguousDestinationGuard();
        assertTrue(guard.remember(QUERY, AREAS, ADDRESSES));
        return guard;
    }
}
