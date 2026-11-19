package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveNavigationGateTest {
    @Test public void requiresSelectionPresentationAndANewUserTurnBeforeNavigation() {
        LiveNavigationGate gate = new LiveNavigationGate();
        long searchTurn = gate.beginUserTurn();
        assertTrue(gate.register("c1", "이종호 피부과", "부산 금정구 서동 1"));
        assertTrue(gate.select("c1"));
        assertEquals(LiveNavigationGate.Decision.NOT_PRESENTED,
                gate.consumeForNavigation("c1"));

        assertTrue(gate.markPresented("c1", searchTurn));
        assertEquals(LiveNavigationGate.Decision.NEEDS_NEW_USER_TURN,
                gate.consumeForNavigation("c1"));

        gate.beginUserTurn();
        assertEquals(LiveNavigationGate.Decision.ALLOWED,
                gate.consumeForNavigation("c1"));
        assertEquals(LiveNavigationGate.Decision.ALREADY_USED,
                gate.consumeForNavigation("c1"));
    }

    @Test public void rejectionPreventsTheSamePlaceFromBeingOfferedAgain() {
        LiveNavigationGate gate = new LiveNavigationGate();
        gate.beginUserTurn();
        assertTrue(gate.register("c1", "<b>이종호 피부과</b>", "부산 금정구 서동 1"));
        assertTrue(gate.select("c1"));
        assertTrue(gate.reject("c1"));
        assertFalse(gate.register("c2", "이종호 피부과", "부산 금정구 서동 1"));
        assertEquals(LiveNavigationGate.Decision.UNKNOWN_CANDIDATE,
                gate.consumeForNavigation("c1"));
    }

    @Test public void unselectedSearchResultCannotStartNavigation() {
        LiveNavigationGate gate = new LiveNavigationGate();
        long turn = gate.beginUserTurn();
        assertTrue(gate.register("c1", "이종호 피부과", "부산 금정구 서동 1"));
        assertFalse(gate.markPresented("c1", turn));
        gate.beginUserTurn();
        assertEquals(LiveNavigationGate.Decision.NOT_SELECTED,
                gate.consumeForNavigation("c1"));
    }

    @Test public void rejectedPlaceDoesNotStayBlockedAcrossNewConversation() {
        LiveNavigationGate gate = new LiveNavigationGate();
        gate.beginUserTurn();
        assertTrue(gate.register("clinic-1", "서동피부과", "부산 금정구 서동 123"));
        assertTrue(gate.reject("clinic-1"));
        assertFalse(gate.register("clinic-2", "서동피부과", "부산 금정구 서동 123"));

        gate.reset();
        assertTrue(gate.register("clinic-3", "서동피부과", "부산 금정구 서동 123"));
        assertEquals(0L, gate.currentUserTurn());
    }
}
