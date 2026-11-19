package com.siny.mobilebasic;

/** Pure time and boot identity checks for the one-shot Naver guide-start arm. */
final class NaverGuideArmLease {
    static final long ARM_WINDOW_MS = 90_000L;
    static final long MINIMUM_CLICK_REMAINING_MS = 5_000L;

    private NaverGuideArmLease() {}

    static boolean isUsable(long expectedBootCount, long actualBootCount,
                            long expiresAtElapsedRealtime, long nowElapsedRealtime,
                            long minimumRemainingMs) {
        if (expectedBootCount < 0L || actualBootCount < 0L
                || expiresAtElapsedRealtime < 0L || nowElapsedRealtime < 0L
                || expectedBootCount != actualBootCount
                || expiresAtElapsedRealtime <= nowElapsedRealtime) return false;
        return expiresAtElapsedRealtime - nowElapsedRealtime
                >= Math.max(0L, minimumRemainingMs);
    }
}
