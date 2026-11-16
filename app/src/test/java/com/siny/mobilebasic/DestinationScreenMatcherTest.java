package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationScreenMatcherTest {
    @Test public void requiresBothTheConfirmedPlaceAndItsAddress() {
        assertTrue(DestinationScreenMatcher.matches(
                "서울역 서울 중구 세종대로 2 안내 시작", "서울역", "서울 중구 세종대로 2"));
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 안내 시작", "서울역", "서울 중구 세종대로 2"));
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 서울 강남구 테헤란로 1 안내 시작", "서울역", "서울 중구 세종대로 2"));
    }

    @Test public void acceptsLongAddressWhenTheScreenShowsItsDistinctiveTail() {
        assertTrue(DestinationScreenMatcher.matches(
                "서울역 중구세종대로110 안내 시작", "서울역", "대한민국 서울특별시 중구 세종대로 110"));
    }

    @Test public void doesNotTreatALongerBuildingNumberAsTheConfirmedAddress() {
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 중구세종대로1101 안내 시작", "서울역", "대한민국 서울특별시 중구 세종대로 110"));
    }

    @Test public void distinguishesARoadNumberFromANamedSideStreet() {
        String sideStreet = "대한민국 서울특별시 중구 세종대로110길 1";
        assertTrue(DestinationScreenMatcher.matches(
                "서울역 중구 세종대로110길1 안내 시작", "서울역", sideStreet));
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 중구 세종대로 110 안내 시작", "서울역", sideStreet));
    }

    @Test public void distinguishesDisplayableAddressFromAddressSpecificEnoughForTaxiAutomation() {
        assertFalse(DestinationAddressMatcher.isUsableAddress("주소 정보 없음"));
        assertTrue(DestinationAddressMatcher.isUsableAddress("서울특별시 중구"));
        assertFalse(DestinationAddressMatcher.hasStreetNumber("서울특별시 중구"));
        assertTrue(DestinationAddressMatcher.hasStreetNumber("서울특별시 중구 세종대로 110"));
        assertTrue(DestinationAddressMatcher.hasStreetNumber("서울특별시 중구 세종대로110길 1"));
    }

    @Test public void doesNotTreatAChildOrNamedSideStreetAsTheConfirmedMainRoadAddress() {
        String mainRoad = "대한민국 서울특별시 중구 세종대로 110";
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 중구 세종대로110-1 안내 시작", "서울역", mainRoad));
        assertFalse(DestinationScreenMatcher.matches(
                "서울역 중구 세종대로110길1 안내 시작", "서울역", mainRoad));
        assertTrue(DestinationScreenMatcher.matches(
                "서울역 중구 세종대로110동 안내 시작", "서울역", mainRoad));
    }
}
