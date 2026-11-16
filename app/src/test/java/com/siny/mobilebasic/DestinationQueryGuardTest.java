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

    @Test public void replacesCorrectedAreaInsteadOfSearchingBothAreas() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                "부산 말고 서울 종로구 피부과", "부산 금정구 서동 피부과");
        assertEquals("서울 종로구 피부과", query);
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
}
