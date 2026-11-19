package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DestinationQueryGuardTest {
    @Test public void repairsTheReportedSeodongDermatologyCollapse() {
        String query = DestinationQueryGuard.preserveExplicitIntent("",
                "부산 금정구 서동에 있는 피부과", "서동");
        assertEquals("부산 금정구 서동 피부과", query);
    }

    @Test public void carriesAreaAcrossAClarifyingReply() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구에 있는 병원",
                "서동의 피부과", "서동");
        assertEquals("부산 금정구 서동 피부과", query);
    }

    @Test public void anExplicitNewPlaceTypeOverridesTheCategoryFromAnOlderClarification() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 피부과",
                "서울 종로구 약국", "서울 종로구 약국");
        assertEquals("서울 종로구 약국", query);
    }

    @Test public void aNewPlaceTypeCanReuseTheStillValidAreaWithoutKeepingTheOldType() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 피부과",
                "서동 치과", "서동 치과");
        assertEquals("부산 금정구 서동 치과", query);
    }

    @Test public void aNewExplicitRegionReplacesConflictingOldRegionAndItsChildren() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "서울 종로구 약국", "서울 종로구 약국");
        assertEquals("서울 종로구 약국", query);
    }

    @Test public void aNewNeighborhoodReplacesOnlyTheOldNeighborhood() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 장전동 피부과",
                "서동 치과", "서동 치과");
        assertEquals("부산 금정구 서동 치과", query);
    }

    @Test public void newNamedDestinationDoesNotInheritAnOldCityButBareCategoryCan() {
        assertEquals("강남 치과", DestinationQueryGuard.preserveExplicitIntent(
                "부산 금정구 피부과", "강남 치과", "강남 치과"));
        assertEquals("부산 금정구 치과", DestinationQueryGuard.preserveExplicitIntent(
                "부산 금정구 피부과", "치과", "치과"));
    }

    @Test public void stationNameIsNotSplitIntoAFalseNeighborhoodConstraint() {
        assertEquals("서동역 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "", "서동역 피부과", "서동역 피부과"));
        assertEquals("부산 서동역 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "", "부산 서동역 피부과", "부산 서동역 피부과"));
    }

    @Test public void stationAnswerToAnActiveClinicClarificationKeepsTheClinicIntent() {
        assertEquals("서동역 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "부산 금정구 피부과", "서동역", "서동역"));
    }

    @Test public void negatedOldTypeDoesNotOverrideTheNewlyRequestedType() {
        assertEquals("서동역 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "부산 금정구 치과", "치과 말고 서동역 피부과", "서동역 피부과"));
    }

    @Test public void trailingPlaceTypeCorrectionOverridesTheEarlierTypeInTheSameUtterance() {
        assertEquals("서동역 치과", DestinationQueryGuard.preserveExplicitIntent(
                "", "서동역 피부과 말고 치과", "서동역 피부과"));
    }

    @Test public void correctionKeepsTheNamedLocationEvenWhenAiReturnsOnlyTheNewCategory() {
        assertEquals("서동역 치과", DestinationQueryGuard.preserveExplicitIntent(
                "", "서동역 피부과 말고 치과", "치과"));
    }

    @Test public void correctedNamedLocationReplacesTheEarlierNamedLocation() {
        assertEquals("강남역 치과", DestinationQueryGuard.preserveExplicitIntent(
                "", "서울역 피부과 말고 강남역 치과", "치과"));
        assertEquals("서동역 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "", "서동역 치과 말고 피부과", "피부과"));
        for (String correction : new String[]{"빼고", "말구", "대신"}) {
            assertEquals(correction, "서울역 치과", DestinationQueryGuard.preserveExplicitIntent(
                    "", "서울역 피부과 " + correction + " 치과", "치과"));
        }
    }

    @Test public void replacesCorrectedAreaInsteadOfSearchingBothAreas() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "부산 말고 서울 종로구 피부과", "부산 금정구 서동 피부과");
        assertEquals("서울 종로구 피부과", query);
    }

    @Test public void correctingOnlyTheRegionKeepsTheActivePlaceType() {
        assertEquals("부산 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "서울 종로구 피부과", "서울 말고 부산", "서울 종로구 피부과"));
    }

    @Test public void replacesCorrectedPlaceTypeInsteadOfKeepingTheOldCategory() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "피부과 말고 약국", "부산 금정구 서동 피부과");
        assertEquals("부산 금정구 서동 약국", query);
    }

    @Test public void recognizesAPlainSpokenNoFollowedByANewDestination() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "아니요, 서울 종로구 약국으로 가자", "부산 금정구 서동 피부과");
        assertEquals("서울 종로구 약국", query);
    }

    @Test public void acceptsAiQueryWhenItAlreadyContainsAllSpokenConstraints() {
        String query = DestinationQueryGuard.preserveExplicitIntent("",
                "부산 금정구 서동에 있는 피부과", "부산 금정구 서동 이종호 피부과");
        assertEquals("부산 금정구 서동 이종호 피부과", query);
    }

    @Test public void neverCreatesAQueryWhenAiDidNotReturnOne() {
        assertEquals("", DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "부산 금정구 서동 피부과", ""));
    }

    @Test public void everydayFillerThatEndsInDoIsNotMistakenForAProvince() {
        String query = DestinationQueryGuard.preserveExplicitIntent("",
                "피부과 가고 싶어도 어디인지 잘 모르겠어요", "피부과");
        assertEquals("피부과", query);
    }

    @Test public void competingPlaceCategoriesAreNotSilentlyTreatedAsOneCorrection() {
        assertEquals(true, DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(
                "", "부산 금정구 서동 치과 피부과"));
        assertEquals(true, DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(
                "", "부산 금정구 서동 피부과나 치과"));
    }

    @Test public void explicitCategoryCorrectionLeavesOneSearchablePlaceType() {
        for (String marker : new String[]{"말고", "말구", "빼고", "빼구", "대신"}) {
            assertEquals(marker, false, DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(
                    "", "부산 금정구 서동 피부과 " + marker + " 치과"));
        }
    }

    @Test public void newCategoryInClarificationReplacesOldCategoryWithoutConflict() {
        assertEquals(false, DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(
                "부산 금정구 서동 피부과", "치과"));
    }

    @Test public void incompleteCorrectionDoesNotBecomeASearchForTheExcludedCategory() {
        for (String marker : new String[]{"말고", "말구", "빼고", "빼구", "대신"}) {
            assertEquals(marker, true,
                    DestinationQueryGuard.hasIncompleteCorrection("부산 금정구 서동 치과 " + marker));
        }
        assertEquals(true, DestinationQueryGuard.hasIncompleteCorrection(
                "부산 금정구 서동 치과 말고 부산"));
        assertEquals(false, DestinationQueryGuard.hasIncompleteCorrection("부산 금정구 서동 치과"));
        assertEquals(false, DestinationQueryGuard.hasIncompleteCorrection("부산 금정구 서동 치과 빼고 피부과"));
        assertEquals(false, DestinationQueryGuard.hasIncompleteCorrection(
                "부산 금정구 서동 피부과 서울 말고 부산"));
    }

    @Test public void preservesAnExplicitHospitalQualifierAlongsideItsSpecialty() {
        assertEquals("부산 금정구 서동 병원 피부과", DestinationQueryGuard.preserveExplicitIntent(
                "", "부산 금정구 서동 병원 피부과", "부산 금정구 서동 병원 피부과"));
        assertEquals(false, DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(
                "", "부산 금정구 서동 병원 피부과"));
    }
}
