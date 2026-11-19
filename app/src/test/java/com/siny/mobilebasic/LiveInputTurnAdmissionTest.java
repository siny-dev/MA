package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LiveInputTurnAdmissionTest {
    @Test public void doesNotAdmitAudioWhileGeminiWaitsForToolResponse() {
        assertEquals(LiveInputTurnAdmission.Decision.TOOL_CALL_PENDING,
                LiveInputTurnAdmission.decide(false, true, false, false));
    }

    @Test public void allowsNextNaturalTurnAfterToolResponseIsComplete() {
        assertEquals(LiveInputTurnAdmission.Decision.ACCEPTED,
                LiveInputTurnAdmission.decide(false, false, false, false));
    }

    @Test public void preventsSeveralTurnsAccumulatingBeforeInitialSetupCompletes() {
        assertEquals(LiveInputTurnAdmission.Decision.SETUP_TURN_PENDING,
                LiveInputTurnAdmission.decide(false, false, true, true));
    }

    @Test public void allowsTheFirstTurnWhileInitialSetupIsStillConnecting() {
        assertEquals(LiveInputTurnAdmission.Decision.ACCEPTED,
                LiveInputTurnAdmission.decide(false, false, true, false));
    }

    @Test public void closedSessionNeverAdmitsInput() {
        assertEquals(LiveInputTurnAdmission.Decision.CLOSED,
                LiveInputTurnAdmission.decide(true, false, false, false));
    }
}
