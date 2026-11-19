package com.siny.mobilebasic;

/** Limits automatic destination re-prompts so uncertain recognition cannot loop indefinitely. */
final class DestinationSpeechClarificationGate {
    private boolean automaticRepeatUsed;

    boolean mayRepeatAutomatically() {
        if (automaticRepeatUsed) return false;
        automaticRepeatUsed = true;
        return true;
    }

    boolean hasUsedAutomaticRepeat() {
        return automaticRepeatUsed;
    }

    void reset() {
        automaticRepeatUsed = false;
    }
}
