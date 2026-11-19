package com.siny.mobilebasic;

/** Gates voice approval on spoken completion and typed approval on visible confirmation. */
final class ConfirmationPromptGate {
    static final long VOICE_ECHO_GUARD_MS = 600L;
    private String activeUtteranceId = "";
    private boolean heardPrompt;
    private boolean confirmationDisplayed;
    private long voiceApprovalReadyAtElapsedRealtime;

    void start(String utteranceId) {
        activeUtteranceId = utteranceId == null ? "" : utteranceId;
        heardPrompt = false;
        voiceApprovalReadyAtElapsedRealtime = 0L;
    }

    boolean isSpeaking() {
        return !activeUtteranceId.isEmpty();
    }

    boolean isReadyForVoiceApproval(long nowElapsedRealtime) {
        return heardPrompt && nowElapsedRealtime >= voiceApprovalReadyAtElapsedRealtime;
    }

    boolean isWaitingForVoiceApproval(long nowElapsedRealtime) {
        return heardPrompt && nowElapsedRealtime < voiceApprovalReadyAtElapsedRealtime;
    }

    long voiceApprovalDelayRemaining(long nowElapsedRealtime) {
        return Math.max(0L, voiceApprovalReadyAtElapsedRealtime - nowElapsedRealtime);
    }

    void markConfirmationDisplayed() {
        confirmationDisplayed = true;
    }

    void openForDisplay() {
        reset();
        markConfirmationDisplayed();
    }

    boolean isReadyForTextApproval() {
        return confirmationDisplayed;
    }

    boolean finish(String utteranceId, boolean completed, long finishedAtElapsedRealtime) {
        if (activeUtteranceId.isEmpty() || !activeUtteranceId.equals(utteranceId)) return false;
        activeUtteranceId = "";
        heardPrompt = completed;
        voiceApprovalReadyAtElapsedRealtime = completed
                ? finishedAtElapsedRealtime + VOICE_ECHO_GUARD_MS : 0L;
        return true;
    }

    void cancelActive() {
        activeUtteranceId = "";
    }

    void reset() {
        activeUtteranceId = "";
        heardPrompt = false;
        confirmationDisplayed = false;
        voiceApprovalReadyAtElapsedRealtime = 0L;
    }
}
