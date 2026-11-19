package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationClarificationLeaseTest {
    @Test public void followUpKeepsContextUntilTwoMinutesThenExpiresClosed() {
        DestinationClarificationLease lease = new DestinationClarificationLease();
        lease.remember("피부과", 100L);
        lease.setAwaitingReply(true, 100L);
        assertEquals("피부과", lease.phrase(100L));
        assertTrue(lease.isAwaitingReply(100L + DestinationClarificationLease.TTL_MS - 1));
        assertTrue(lease.isActive(100L + DestinationClarificationLease.TTL_MS - 1));
        assertEquals("", lease.phrase(100L + DestinationClarificationLease.TTL_MS));
        assertFalse(lease.isAwaitingReply(100L + DestinationClarificationLease.TTL_MS));
        assertFalse(lease.isActive(100L + DestinationClarificationLease.TTL_MS));
    }

    @Test public void explicitRefreshExtendsContextAndClearRemovesIt() {
        DestinationClarificationLease lease = new DestinationClarificationLease();
        lease.remember("피부과", 0L);
        lease.setAwaitingReply(true, 60_000L);
        assertEquals("피부과", lease.phrase(60_000L + DestinationClarificationLease.TTL_MS - 1));
        lease.clear();
        assertEquals("", lease.phrase(60_000L));
        assertFalse(lease.isAwaitingReply(60_000L));
    }

    @Test public void repeatedPromptRefreshCannotKeepContextBeyondFourMinutes() {
        DestinationClarificationLease lease = new DestinationClarificationLease();
        long start = 100L;
        lease.remember("피부과", start);
        lease.setAwaitingReply(true, start);
        lease.remember("피부과", start + 60_000L);
        lease.setAwaitingReply(true, start + 120_000L);
        lease.remember("피부과", start + 180_000L);

        long cap = start + DestinationClarificationLease.MAX_LIFETIME_MS;
        assertTrue(lease.isActive(cap - 1L));
        assertEquals("", lease.phrase(cap));
        assertFalse(lease.isActive(cap));
    }
}
