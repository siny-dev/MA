package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NaverGuideArmLeaseTest {
    @Test public void clickGateAllowsExactlyFiveSecondsRemaining() {
        long now = 10_000L;

        assertTrue(NaverGuideArmLease.isUsable(12L, 12L,
                now + NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS, now,
                NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS));
    }

    @Test public void clickGateRejectsFourThousandNineHundredNinetyNineMsRemaining() {
        long now = 10_000L;

        assertFalse(NaverGuideArmLease.isUsable(12L, 12L,
                now + NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS - 1L, now,
                NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS));
    }

    @Test public void clickGateRejectsLeaseThatExpiresDuringTreeProcessing() {
        long clickAt = 10_003L;
        long expiry = 15_001L;

        assertFalse(NaverGuideArmLease.isUsable(12L, 12L, expiry, clickAt,
                NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS));
    }

    @Test public void clickGateRejectsExpiredLeaseAndBootMismatch() {
        long now = 20_000L;

        assertFalse(NaverGuideArmLease.isUsable(12L, 12L, now, now, 0L));
        assertFalse(NaverGuideArmLease.isUsable(12L, 13L, now + 30_000L, now, 1L));
    }

    @Test public void armExpiresAtNinetySecondsAndCannotBeRefreshedByLateEvents() {
        long armedAt = 2_000L;
        long expiresAt = armedAt + NaverGuideArmLease.ARM_WINDOW_MS;

        assertTrue(NaverGuideArmLease.isUsable(7L, 7L, expiresAt,
                armedAt + NaverGuideArmLease.ARM_WINDOW_MS - 1L, 1L));
        assertFalse(NaverGuideArmLease.isUsable(7L, 7L, expiresAt,
                armedAt + NaverGuideArmLease.ARM_WINDOW_MS, 0L));
        assertTrue(NaverGuideArmLease.isUsable(7L, 7L, expiresAt,
                armedAt + NaverGuideArmLease.ARM_WINDOW_MS
                        - NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS,
                NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS));
        assertFalse(NaverGuideArmLease.isUsable(7L, 7L, expiresAt,
                armedAt + NaverGuideArmLease.ARM_WINDOW_MS
                        - NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS + 1L,
                NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS));
    }

    @Test public void clickGateRejectsUnknownBootCountOrElapsedClock() {
        long now = 30_000L;
        long expiry = now + 30_000L;

        assertFalse(NaverGuideArmLease.isUsable(-1L, 12L, expiry, now, 1L));
        assertFalse(NaverGuideArmLease.isUsable(12L, -1L, expiry, now, 1L));
        assertFalse(NaverGuideArmLease.isUsable(12L, 12L, expiry, -1L, 1L));
    }
}
