package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationSpeechHypothesisPolicyTest {
    @Test public void rejectsAreaOnlyTopResultWhenAlternativesRetainPlaceCategory() {
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서동", "서동 피부과", "부산 금정구 서동 피부과")));
    }

    @Test public void rejectsAlternativesThatDisagreeOnAreaOrPlaceType() {
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 금정구 서동 피부과", "부산 금정구 장전동 피부과")));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 금정구 서동 피부과", "부산 금정구 서동 안과")));
    }

    @Test public void ignoresParticleAndLightConnectiveDifferencesWithSameIntent() {
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 금정구 서동에 있는 피부과", "부산 금정구 서동의 피부과")));
    }

    @Test public void doesNotUseAnAlternateWhenTopResultIsUnusable() {
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "", "부산 금정구 서동 피부과")));
    }

    @Test public void oneOrNoHypothesisDoesNotAddAnExtraQuestion() {
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Collections.singletonList(
                "부산 금정구 서동 피부과")));
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(null));
    }

    @Test public void anActivePlaceCategoryMakesAShortAreaAnswerEquivalentToItsLongerHypothesis() {
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서동", "서동 피부과"), "부산 금정구 피부과"));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서동 치과", "서동 피부과"), "부산 금정구 피부과"));
    }

    @Test public void differentExplicitParentRegionsDoNotCollapseSameNamedNeighborhoods() {
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서울 서동 치과", "부산 서동 치과"), "부산 금정구 피부과"));
    }

    @Test public void missingParentRegionIsNotAssumedToMatchAnExplicitRegion() {
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서울 서동 치과", "서동 치과")));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서동 치과", "서울 서동 치과")));
    }

    @Test public void hypothesisOrderDoesNotChangeWhetherMeaningIsAmbiguous() {
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "서울 서동 치과", "부산 서동 치과")));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 서동 치과", "서울 서동 치과")));
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 금정구 서동의 피부과", "부산 금정구 서동 피부과")));
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "부산 금정구 서동 피부과", "부산 금정구 서동의 피부과")));
    }

    @Test public void matchingCancellationHypothesesAgreeButCancellationAndDestinationConflict() {
        assertFalse(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList("취소", "취소해")));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(Arrays.asList(
                "취소", "부산 금정구 서동 피부과")));
    }

    @Test public void neighborhoodAloneNeedsPlaceIntentButEstablishedClarificationsCanSupplyIt() {
        assertTrue(DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination("부산 금정구 서동"));
        assertFalse(DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination("부산 금정구 서동 피부과"));
        assertFalse(DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination("이종호 피부과"));
        assertFalse(DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination("부산"));
        assertFalse(DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination("서동역 피부과"));
        assertTrue(DestinationSpeechHypothesisPolicy.requiresRepeat(java.util.Arrays.asList(
                "서동역 피부과", "서동 피부과")));
    }
}
