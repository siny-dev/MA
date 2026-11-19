package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConfirmationPromptGateTest {
    @Test public void approvalIsBlockedUntilTheActivePromptCompletes() {
        ConfirmationPromptGate gate = new ConfirmationPromptGate();
        gate.start("candidate-1");

        assertTrue(gate.isSpeaking());
        assertFalse(gate.isReadyForVoiceApproval(1000L));
        assertTrue(gate.finish("candidate-1", true, 1000L));
        assertFalse(gate.isSpeaking());
        assertTrue(gate.isWaitingForVoiceApproval(1000L));
        assertEquals(ConfirmationPromptGate.VOICE_ECHO_GUARD_MS,
                gate.voiceApprovalDelayRemaining(1000L));
        assertFalse(gate.isReadyForVoiceApproval(1599L));
        assertTrue(gate.isReadyForVoiceApproval(1600L));
    }

    @Test public void stalePromptCompletionCannotUnlockANewerCandidatePrompt() {
        ConfirmationPromptGate gate = new ConfirmationPromptGate();
        gate.start("candidate-1");
        gate.start("candidate-2");

        assertFalse(gate.finish("candidate-1", true, 1000L));
        assertFalse(gate.isReadyForVoiceApproval(2000L));
        assertTrue(gate.isSpeaking());
        assertTrue(gate.finish("candidate-2", true, 1000L));
        assertTrue(gate.isReadyForVoiceApproval(1600L));
    }

    @Test public void interruptedPromptDoesNotAuthorizeVoiceApproval() {
        ConfirmationPromptGate gate = new ConfirmationPromptGate();
        gate.start("candidate-1");

        assertTrue(gate.finish("candidate-1", false, 1000L));
        assertFalse(gate.isReadyForVoiceApproval(2000L));
    }

    @Test public void cancellingAnAlreadyCompletedPromptKeepsItsApprovalWindow() {
        ConfirmationPromptGate gate = new ConfirmationPromptGate();
        gate.start("candidate-1");
        assertTrue(gate.finish("candidate-1", true, 1000L));
        assertTrue(gate.isReadyForVoiceApproval(1600L));

        gate.cancelActive();

        assertTrue(gate.isReadyForVoiceApproval(1600L));
    }

    @Test public void textApprovalBeforeConfirmationIsDisplayedIsIgnored() {
        ConfirmationPromptGate gate = new ConfirmationPromptGate();
        gate.start("candidate-1");

        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classify("네, 맞아요"));
        assertFalse(gate.isReadyForTextApproval());
        gate.openForDisplay();
        assertTrue(gate.isReadyForTextApproval());
        assertFalse(gate.isReadyForVoiceApproval(1000L));

        gate.reset();
        assertFalse(gate.isReadyForTextApproval());
    }
}
