package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class CandidateVoiceLabelBuilderTest {
    @Test public void addsRoadDetailOnlyWhenSameNameAndAreaCollide() {
        List<String> labels = CandidateVoiceLabelBuilder.build(Arrays.asList(
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "테헤란로 12",
                        "서울 강남구 테헤란로 12", false),
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "역삼로 25",
                        "서울 강남구 역삼로 25", false)));

        assertNotEquals(labels.get(0), labels.get(1));
        assertTrue(labels.get(0).contains("테헤란로 12"));
        assertTrue(labels.get(1).contains("역삼로 25"));
    }

    @Test public void sameNeighborhoodNameInDifferentDistrictsKeepsDistrictInVoiceLabels() {
        List<String> labels = CandidateVoiceLabelBuilder.build(Arrays.asList(
                new CandidateVoiceLabelBuilder.Place("서동피부과", "금정구 서동", "서동로 12",
                        "부산 금정구 서동로 12", false),
                new CandidateVoiceLabelBuilder.Place("서동피부과", "동래구 서동", "서동로 25",
                        "부산 동래구 서동로 25", false)));

        assertTrue(labels.get(0).contains("금정구 서동"));
        assertTrue(labels.get(1).contains("동래구 서동"));
    }

    @Test public void fallsBackToFullAddressWhenRoadDetailStillCollides() {
        List<String> labels = CandidateVoiceLabelBuilder.build(Arrays.asList(
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "테헤란로",
                        "서울 강남구 테헤란로 12 1층", false),
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "테헤란로",
                        "서울 강남구 테헤란로 34 2층", false)));

        assertNotEquals(labels.get(0), labels.get(1));
        assertTrue(labels.get(0).contains("12"));
        assertTrue(labels.get(1).contains("34"));
    }

    @Test public void identicalFullAddressesStayIdenticalForSafeScreenFallback() {
        List<String> labels = CandidateVoiceLabelBuilder.build(Arrays.asList(
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "테헤란로 12",
                        "서울 강남구 테헤란로 12", false),
                new CandidateVoiceLabelBuilder.Place("한빛병원", "서울 강남구", "테헤란로 12",
                        "서울 강남구 테헤란로 12", false)));

        assertEquals(labels.get(0), labels.get(1));
    }
}
