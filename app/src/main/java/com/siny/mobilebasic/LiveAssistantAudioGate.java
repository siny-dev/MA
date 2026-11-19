package com.siny.mobilebasic;

/** Keeps assistant audio from an interrupted response out of the speaker during a PTT turn. */
final class LiveAssistantAudioGate {
    private boolean capturing;
    private boolean waitingForPriorResponseBoundary;

    synchronized void beginCapture(boolean assistantResponseInFlight) {
        capturing = true;
        waitingForPriorResponseBoundary |= assistantResponseInFlight;
    }

    synchronized void endCapture() {
        capturing = false;
    }

    synchronized void onAssistantResponseBoundary() {
        waitingForPriorResponseBoundary = false;
    }

    synchronized boolean shouldDropAudio() {
        return capturing || waitingForPriorResponseBoundary;
    }

    synchronized boolean shouldRejectToolCall() {
        return capturing || waitingForPriorResponseBoundary;
    }

    synchronized boolean isWaitingForPriorResponseBoundary() {
        return waitingForPriorResponseBoundary;
    }

    synchronized void reset() {
        capturing = false;
        waitingForPriorResponseBoundary = false;
    }
}
