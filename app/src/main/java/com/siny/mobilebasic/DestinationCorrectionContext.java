package com.siny.mobilebasic;

/** Short-lived explicit destination context used only for an immediate spoken correction. */
final class DestinationCorrectionContext {
    static final long MAX_AGE_MS = 5 * 60 * 1000L;

    private String destination = "";
    private long expiresAtElapsedRealtime;

    void remember(String explicitDestination, long nowElapsedRealtime) {
        String value = explicitDestination == null ? "" : explicitDestination.trim();
        if (value.isEmpty()) {
            clear();
            return;
        }
        destination = value;
        expiresAtElapsedRealtime = nowElapsedRealtime + MAX_AGE_MS;
    }

    String current(long nowElapsedRealtime) {
        if (destination.isEmpty() || nowElapsedRealtime >= expiresAtElapsedRealtime) {
            clear();
            return "";
        }
        return destination;
    }

    void clear() {
        destination = "";
        expiresAtElapsedRealtime = 0L;
    }
}
