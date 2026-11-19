package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationSpeechClarificationGateTest {
    @Test public void allowsOneAutomaticRepeatThenRequiresManualActionUntilResolved() {
        DestinationSpeechClarificationGate gate = new DestinationSpeechClarificationGate();
        assertFalse(gate.hasUsedAutomaticRepeat());
        assertTrue(gate.mayRepeatAutomatically());
        assertTrue(gate.hasUsedAutomaticRepeat());
        assertFalse(gate.mayRepeatAutomatically());
        gate.reset();
        assertTrue(gate.mayRepeatAutomatically());
    }

    @Test public void independentClarificationPurposesDoNotConsumeEachOthersRepeat() {
        DestinationSpeechClarificationGate areaQuestion = new DestinationSpeechClarificationGate();
        DestinationSpeechClarificationGate hypothesisConflict = new DestinationSpeechClarificationGate();
        assertTrue(areaQuestion.mayRepeatAutomatically());
        assertTrue(hypothesisConflict.mayRepeatAutomatically());
        assertFalse(hypothesisConflict.mayRepeatAutomatically());
        assertFalse(areaQuestion.mayRepeatAutomatically());
    }
}
