package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Offline scenario for app-owned guards; it does not claim to simulate a real person or Gemini. */
public class LiveDestinationDialogueSafetyScenarioTest {
    @Test public void whenModelOnlyReturnsNeighborhoodAppAsksForPlaceInsteadOfOfferingTheNeighborhood() {
        // Simulate the observed bad interpretation: the city and neighborhood were heard,
        // but the requested clinic type was lost. This must be a clarification, not a route.
        LiveDestinationQuery.Result weakInterpretation = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "", "서동");
        assertEquals(LiveDestinationQuery.Error.MISSING_PLACE, weakInterpretation.error);
        assertFalse(weakInterpretation.isValid());

        // Only after the user supplies the missing meaning may the app form a clinic query.
        LiveDestinationQuery.Result afterClarification = LiveDestinationQuery.fromSlots(
                weakInterpretation.region, "피부과", "");
        assertTrue(afterClarification.isValid());
        assertEquals("부산 금정구 서동 피부과", afterClarification.query);
    }

    @Test public void neighborhoodMisclassifiedIntoCategorySlotIsAlsoClarified() {
        LiveDestinationQuery.Result weakInterpretation = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "서동", "");

        assertEquals(LiveDestinationQuery.Error.MISSING_PLACE, weakInterpretation.error);
        assertFalse(weakInterpretation.isValid());
        assertEquals("부산 금정구 서동", weakInterpretation.region);

        LiveDestinationQuery.Result afterCorrection = LiveDestinationQuery.fromSlots(
                weakInterpretation.region, "피부과", "");
        assertTrue(afterCorrection.isValid());
        assertEquals("부산 금정구 서동 피부과", afterCorrection.query);
    }

    @Test public void 서동피부과정정거절반복차단후새후보만동의로통과() {
        LiveDestinationQuery.Result query = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "피부과", "");
        assertTrue(query.isValid());
        assertEquals("부산 금정구 서동 피부과", query.query);
        assertFalse(LiveDestinationQuery.fromSlots("서동", "피부과", "").isValid());

        LiveNavigationGate gate = new LiveNavigationGate();
        long requestTurn = gate.beginUserTurn();
        assertTrue(gate.register("candidate-1", "이종호 피부과", "부산 금정구 서동 123"));
        assertTrue(gate.select("candidate-1"));

        // A model-generated spoken summary cannot satisfy the factual presentation gate.
        assertEquals(LiveNavigationGate.Decision.NOT_PRESENTED,
                gate.consumeForNavigation("candidate-1"));

        String exactAppSpeech = LiveCandidateSpeechBuilder.confirmation(
                "이종호 피부과", "부산 금정구 서동 123", false);
        assertTrue(exactAppSpeech.contains("이종호 피부과"));
        assertTrue(exactAppSpeech.contains("부산 금정구 서동 123"));
        // The runtime calls this only when Android TextToSpeech reports onDone.
        assertTrue(gate.markPresented("candidate-1", requestTurn));
        long rejectionTurn = gate.beginUserTurn(); // User says only “아니”.
        assertTrue(gate.reject("candidate-1"));
        assertFalse(gate.register("candidate-1-repeat", "이종호 피부과", "부산 금정구 서동 123"));

        assertTrue(gate.register("candidate-2", "서동중앙피부과", "부산 금정구 서동 456"));
        assertTrue(gate.select("candidate-2"));
        assertTrue(gate.markPresented("candidate-2", rejectionTurn));
        assertEquals(LiveNavigationGate.Decision.NEEDS_NEW_USER_TURN,
                gate.consumeForNavigation("candidate-2"));
        gate.beginUserTurn(); // User gives a new affirmative answer after hearing the second candidate.
        assertEquals(LiveNavigationGate.Decision.ALLOWED,
                gate.consumeForNavigation("candidate-2"));
    }

    @Test public void 사용자가피부과말고약국이라고하면지역은유지하고장소종류만바꾼다() {
        LiveNavigationGate gate = new LiveNavigationGate();
        gate.beginUserTurn();
        assertTrue(gate.register("clinic", "이종호 피부과", "부산 금정구 서동 123"));
        assertTrue(gate.select("clinic"));
        assertTrue(gate.markPresented("clinic", gate.currentUserTurn()));

        long correctionTurn = gate.beginUserTurn(); // “아니, 피부과 말고 약국.”
        assertTrue(gate.reject("clinic"));
        LiveDestinationQuery.Result corrected = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "약국", "");

        assertTrue(corrected.isValid());
        assertEquals("부산 금정구 서동 약국", corrected.query);
        assertFalse(gate.register("clinic-repeat", "이종호 피부과", "부산 금정구 서동 123"));
        assertTrue(gate.register("pharmacy", "서동약국", "부산 금정구 서동 456"));
        assertTrue(gate.select("pharmacy"));
        assertTrue(gate.markPresented("pharmacy", correctionTurn));
        assertEquals(LiveNavigationGate.Decision.NEEDS_NEW_USER_TURN,
                gate.consumeForNavigation("pharmacy"));
    }

    @Test public void incompleteNewDestinationQuestionCannotLeaveOldRouteArmed() {
        LiveNavigationGate gate = new LiveNavigationGate();
        long firstTurn = gate.beginUserTurn();
        assertTrue(gate.register("old-clinic", "이종호 피부과", "부산 금정구 서동 123"));
        assertTrue(gate.select("old-clinic"));
        assertTrue(gate.markPresented("old-clinic", firstTurn));

        gate.beginUserTurn(); // “부산 금정구 서동의 피부과?” starts a new destination request.
        gate.clearActive(); // The app invalidates the old target before validating new slots.
        assertEquals(LiveNavigationGate.Decision.UNKNOWN_CANDIDATE,
                gate.consumeForNavigation("old-clinic"));
    }
}
