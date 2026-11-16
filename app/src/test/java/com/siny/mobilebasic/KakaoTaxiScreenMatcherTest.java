package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class KakaoTaxiScreenMatcherTest {
    private static final String NAME = "서울역";
    private static final String ADDRESS = "대한민국 서울특별시 중구 세종대로 110";

    @Test public void onlyAcceptsTheConfirmedPlaceAndItsAddress() {
        assertTrue(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 중구 세종대로 110", NAME, ADDRESS));
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 강남구 테헤란로 1", NAME, ADDRESS));
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 중구 세종대로 1101", NAME, ADDRESS));
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "부산역 · 서울특별시 중구 세종대로 110", NAME, ADDRESS));
    }

    @Test public void acceptsOnlyAUniqueExactCallActionOnTheMatchingDestinationScreen() {
        String screen = "서울역 · 서울특별시 중구 세종대로 110 · 호출하기";
        assertTrue(KakaoTaxiScreenMatcher.shouldTapCall(screen, NAME, ADDRESS, 1));
        assertFalse(KakaoTaxiScreenMatcher.shouldTapCall(screen, NAME, ADDRESS, 2));
        assertFalse(KakaoTaxiScreenMatcher.shouldTapCall(
                "호출하기", NAME, ADDRESS, 1));
        assertTrue(KakaoTaxiScreenMatcher.isCallButtonLabel("택시 호출하기"));
        assertFalse(KakaoTaxiScreenMatcher.isCallButtonLabel("호출 취소"));
    }

    @Test public void taxiHandoffRejectsAParentBuildingNumberAndAContinuationRoad() {
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 중구 세종대로110-1", NAME, ADDRESS));
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 중구 세종대로110번길1", NAME, ADDRESS));
    }

    @Test public void doesNotSendTaxiToTheMainRoadWhenTheAddressNamesASideStreet() {
        String sideStreet = "대한민국 서울특별시 중구 세종대로110길 1";
        assertFalse(KakaoTaxiScreenMatcher.destinationMatches(
                "서울역 · 서울특별시 중구 세종대로 110", NAME, sideStreet));
    }
}
