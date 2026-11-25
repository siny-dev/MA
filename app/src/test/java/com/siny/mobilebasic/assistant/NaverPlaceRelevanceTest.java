package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class NaverPlaceRelevanceTest {
    private PlaceCandidate candidate(String name, String category, String address, String roadAddress) {
        return new PlaceCandidate(name, category, address, roadAddress, 35.2134, 129.1056);
    }

    @Test public void requiresBothRequestedDongAndMedicalCategoryBeforeModelCanConfirm() {
        PlaceCandidate correct = candidate("이종호피부과", "병원 > 피부과", "부산광역시 금정구 서동 123", "부산광역시 금정구 서동로 10");
        PlaceCandidate wrongCategory = candidate("서동내과", "병원 > 내과", "부산광역시 금정구 서동 456", "부산광역시 금정구 서동로 20");
        PlaceCandidate wrongArea = candidate("다른동피부과", "병원 > 피부과", "부산광역시 금정구 장전동 123", "부산광역시 금정구 장전로 10");

        NaverPlaceRelevance.Result result = NaverPlaceRelevance.filter(
                "부산 금정구 서동에 있는 피부과", "부산 금정구 서동 피부과",
                Arrays.asList(wrongCategory, wrongArea, correct));

        assertTrue(result.accepted());
        assertEquals(Collections.singletonList(2), result.indexes);
    }

    @Test public void cannotConfirmDoctorCredentialsFromPlaceCategoryAlone() {
        PlaceCandidate clinic = candidate("이종호피부과", "병원 > 피부과",
                "부산광역시 금정구 서동 123", "부산광역시 금정구 서동로 10");
        NaverPlaceRelevance.Result result = NaverPlaceRelevance.filter(
                "부산 금정구 서동의 피부과 전문의", "부산 금정구 서동 피부과 전문의", Collections.singletonList(clinic));
        assertFalse(result.accepted());
        assertEquals("qualification-unverified", result.rejectionReason);
        assertEquals("qualification-unverified", NaverPlaceRelevance.preflight(
                "서동 피부과 전문의", "서동 피부과 전문의"));
    }

    @Test public void asksForARealAreaBeforeSearchingGenericOrNearbyMedicalDestinations() {
        assertEquals("area-required", NaverPlaceRelevance.preflight("피부과", "피부과"));
        assertEquals("area-required", NaverPlaceRelevance.preflight("근처 피부과", "주변 피부과"));
        assertEquals("", NaverPlaceRelevance.preflight("부산 금정구 서동 피부과", "부산 금정구 서동 피부과"));
    }

    @Test public void neighborhoodMayMatchVerifiedRoadAddressSuffix() {
        PlaceCandidate clinic = candidate("이종호피부과", "병원 > 피부과",
                "부산광역시 금정구", "부산광역시 금정구 서동로 10");
        List<PlaceCandidate> places = Collections.singletonList(clinic);
        assertTrue(NaverPlaceRelevance.filter("서동 피부과", "서동 피부과", places).accepted());
    }

    @Test public void emptyOrMismatchedResultsCannotBecomeModelCandidates() {
        PlaceCandidate clinic = candidate("이종호피부과", "병원 > 피부과",
                "부산광역시 금정구 서동 123", "부산광역시 금정구 서동로 10");
        NaverPlaceRelevance.Result result = NaverPlaceRelevance.filter(
                "부산 금정구 장전동 피부과", "부산 금정구 장전동 피부과", Collections.singletonList(clinic));
        assertFalse(result.accepted());
        assertEquals("area-not-matched", result.rejectionReason);
    }
}
