package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DestinationIntentClassifierTest {
    @Test public void newDestinationCanInterruptAConditionTopicSelection() {
        assertTrue(DestinationIntentClassifier.isNewDestination("부산 금정구 서동 피부과"));
        assertTrue(DestinationIntentClassifier.isNewDestination("서동 피부과 찾아줘"));
        assertTrue(DestinationIntentClassifier.isNewDestination("서울역으로 가자"));
    }

    @Test public void conditionAnswersDoNotLookLikeReplacementDestinations() {
        assertFalse(DestinationIntentClassifier.isNewDestination("주차"));
        assertFalse(DestinationIntentClassifier.isNewDestination("계단"));
        assertFalse(DestinationIntentClassifier.isNewDestination("둘 다"));
        assertFalse(DestinationIntentClassifier.isNewDestination("괜찮아"));
    }

    @Test public void explicitDestinationCorrectionCanInterruptCandidateConfirmation() {
        assertTrue(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation(
                "아니, 장전동 내과로 가자"));
        assertTrue(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation(
                "부산 금정구 서동 피부과 말고 내과"));
        assertTrue(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation(
                "서울역 말고 강남역으로 가주세요"));
        assertTrue(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation("서동 피부과"));
        assertTrue(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation(
                "네, 장전동 내과"));
        assertTrue(DestinationConfirmationParser.classifyActiveYesNoAnswer("네, 장전동 내과")
                == DestinationConfirmationParser.Decision.UNCLEAR);
    }

    @Test public void unclearOrBareConfirmationDoesNotStartAReplacementSearch() {
        assertFalse(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation("네"));
        assertFalse(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation("아니, 다른 데"));
        assertFalse(DestinationIntentClassifier.isExplicitReplacementDuringConfirmation("거기 있는 피부과"));
    }
}
