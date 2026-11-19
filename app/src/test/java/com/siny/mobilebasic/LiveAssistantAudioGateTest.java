package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveAssistantAudioGateTest {
    @Test public void dropsAudioThroughoutCaptureAndOldResponseTailAfterRelease() {
        LiveAssistantAudioGate gate = new LiveAssistantAudioGate();
        gate.beginCapture(true);
        assertTrue(gate.shouldDropAudio());
        gate.endCapture();
        assertTrue(gate.shouldDropAudio());

        gate.onAssistantResponseBoundary();
        assertFalse(gate.shouldDropAudio());
    }

    @Test public void boundaryDuringCaptureDoesNotLeakAudioUntilRelease() {
        LiveAssistantAudioGate gate = new LiveAssistantAudioGate();
        gate.beginCapture(true);
        gate.onAssistantResponseBoundary();
        assertTrue(gate.shouldDropAudio());
        gate.endCapture();
        assertFalse(gate.shouldDropAudio());
    }

    @Test public void ordinaryTurnDropsOnlyAudioThatArrivesWhileFingerIsDown() {
        LiveAssistantAudioGate gate = new LiveAssistantAudioGate();
        gate.beginCapture(false);
        assertTrue(gate.shouldDropAudio());
        assertTrue(gate.shouldRejectToolCall());
        gate.endCapture();
        assertFalse(gate.shouldDropAudio());
        assertFalse(gate.shouldRejectToolCall());
    }

    @Test public void aNewPressDoesNotClearAnOlderUnresolvedResponseFence() {
        LiveAssistantAudioGate gate = new LiveAssistantAudioGate();
        gate.beginCapture(true);
        gate.endCapture();
        gate.beginCapture(false);
        gate.endCapture();
        assertTrue(gate.shouldDropAudio());
        assertTrue(gate.shouldRejectToolCall());
        gate.onAssistantResponseBoundary();
        assertFalse(gate.shouldDropAudio());
        assertFalse(gate.shouldRejectToolCall());
    }

    @Test public void resetAfterDisconnectClearsCaptureAndFence() {
        LiveAssistantAudioGate gate = new LiveAssistantAudioGate();
        gate.beginCapture(true);
        gate.reset();
        assertFalse(gate.shouldDropAudio());
    }
}
