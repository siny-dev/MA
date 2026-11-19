package com.siny.mobilebasic;

/** Fixed-lifetime lease for one Naver result set and every candidate derived from it. */
final class DestinationCandidateContext {
    static final long MAX_AGE_MS = 5 * 60 * 1000L;
    static final long MIN_CONFIRMATION_REMAINING_MS = 30_000L;
    static final long MIN_INITIAL_PRESENTATION_REMAINING_MS = 35_000L;
    static final long MAX_CANDIDATE_LIST_SPEECH_MS = 60_000L;
    // Budget: capped 60s spoken list + 30s selection + 35s confirmation + 25s slack.
    static final long MIN_CANDIDATE_LIST_REMAINING_MS = 150_000L;
    static final long MIN_HANDOFF_REMAINING_MS = 5_000L;

    private long expiresAtElapsedRealtime;
    private long generation;

    long begin(long nowElapsedRealtime) {
        generation++;
        expiresAtElapsedRealtime = nowElapsedRealtime + MAX_AGE_MS;
        return generation;
    }

    boolean isCurrent(long nowElapsedRealtime) {
        return expiresAtElapsedRealtime > 0L
                && nowElapsedRealtime < expiresAtElapsedRealtime;
    }

    boolean isCurrent(long expectedGeneration, long nowElapsedRealtime) {
        return generation == expectedGeneration && isCurrent(nowElapsedRealtime);
    }

    boolean hasMinimumRemaining(long nowElapsedRealtime, long minimumRemainingMs) {
        return isCurrent(nowElapsedRealtime)
                && expiresAtElapsedRealtime - nowElapsedRealtime >= Math.max(0L, minimumRemainingMs);
    }

    boolean hasMinimumRemaining(long expectedGeneration, long nowElapsedRealtime,
                                long minimumRemainingMs) {
        return generation == expectedGeneration
                && hasMinimumRemaining(nowElapsedRealtime, minimumRemainingMs);
    }

    boolean hasExpired(long nowElapsedRealtime) {
        return expiresAtElapsedRealtime > 0L
                && nowElapsedRealtime >= expiresAtElapsedRealtime;
    }

    boolean hasLease() {
        return expiresAtElapsedRealtime > 0L;
    }

    long generation() {
        return generation;
    }

    long expiresAtElapsedRealtime() {
        return expiresAtElapsedRealtime;
    }

    void clear() {
        generation++;
        expiresAtElapsedRealtime = 0L;
    }
}
