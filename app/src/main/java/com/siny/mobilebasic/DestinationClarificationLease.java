package com.siny.mobilebasic;

/** Keeps follow-up destination context only while the app's clarification is still fresh. */
final class DestinationClarificationLease {
    static final long TTL_MS = 2 * 60 * 1000L;
    static final long MAX_LIFETIME_MS = 4 * 60 * 1000L;

    private String phrase = "";
    private boolean awaitingReply;
    private long expiresAt;
    private long startedAt = -1L;

    void remember(String value, long now) {
        phrase = value == null ? "" : value.trim();
        refreshExpiry(now);
    }

    void setAwaitingReply(boolean awaiting, long now) {
        awaitingReply = awaiting;
        if (awaiting) refreshExpiry(now);
        else if (phrase.isEmpty()) expiresAt = 0L;
    }

    String phrase(long now) {
        expireIfNeeded(now);
        return phrase;
    }

    boolean isAwaitingReply(long now) {
        expireIfNeeded(now);
        return awaitingReply;
    }

    boolean isActive(long now) {
        expireIfNeeded(now);
        return awaitingReply || !phrase.isEmpty();
    }

    void clear() {
        phrase = "";
        awaitingReply = false;
        expiresAt = 0L;
        startedAt = -1L;
    }

    private void refreshExpiry(long now) {
        if (phrase.isEmpty() && !awaitingReply) {
            expiresAt = 0L;
            startedAt = -1L;
            return;
        }
        if (startedAt < 0L) startedAt = now;
        expiresAt = Math.min(now + TTL_MS, startedAt + MAX_LIFETIME_MS);
    }

    private void expireIfNeeded(long now) {
        if (expiresAt > 0L && now >= expiresAt) clear();
    }
}
