package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DestinationFallbackQueryTest {
    @Test public void keepsTheOriginalPlaceWhenAnAiClarificationFallsBackToLocalSearch() {
        assertEquals("큰 병원 수원역 근처", DestinationFallbackQuery.combine(
                "큰 병원", "수원역 근처"));
    }

    @Test public void normalizesSpokenTurnBreaksAndHandlesMissingContext() {
        assertEquals("아주대학교 병원 수원", DestinationFallbackQuery.combine(
                "아주대학교 병원\n", " 수원\t"));
        assertEquals("서울역", DestinationFallbackQuery.combine(null, "서울역"));
    }

    @Test public void keepsClarificationPlaceNamesButDropsCommandFillersAndDuplicates() {
        assertEquals("서울역 수원역", DestinationFallbackQuery.combine(
                "서울역에 가고 싶어요\n네\n수원역으로", "수원역"));
    }

    @Test public void localFallbackUsesTheCorrectedDestinationInsteadOfBothPlaces() {
        assertEquals("강남역", DestinationFallbackQuery.combine(
                null, "서울역 말고 강남역으로 가주세요"));
        assertEquals("강남역", DestinationFallbackQuery.combine(
                null, "아니고요, 강남역으로 가주세요"));
    }
}
