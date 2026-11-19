package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DestinationCorrectionContextTest {
    @Test
    public void explicitDestinationIsAvailableUntilExpiry() {
        DestinationCorrectionContext context = new DestinationCorrectionContext();
        long now = 10_000L;

        context.remember("부산 금정구 서동 피부과", now);

        assertEquals("부산 금정구 서동 피부과", context.current(now));
        assertEquals("부산 금정구 서동 피부과",
                context.current(now + DestinationCorrectionContext.MAX_AGE_MS - 1L));
        assertEquals("", context.current(now + DestinationCorrectionContext.MAX_AGE_MS));
    }

    @Test
    public void rememberingNewDestinationReplacesAndRefreshesContext() {
        DestinationCorrectionContext context = new DestinationCorrectionContext();
        long first = 1_000L;
        context.remember("부산 금정구 서동 피부과", first);

        long second = first + DestinationCorrectionContext.MAX_AGE_MS - 1L;
        context.remember("서울 종로구 약국", second);

        assertEquals("서울 종로구 약국", context.current(second));
        assertEquals("서울 종로구 약국",
                context.current(second + DestinationCorrectionContext.MAX_AGE_MS - 1L));
        assertEquals("", context.current(second + DestinationCorrectionContext.MAX_AGE_MS));
    }

    @Test
    public void emptyDestinationAndClearRemoveContext() {
        DestinationCorrectionContext context = new DestinationCorrectionContext();
        context.remember("부산 금정구 서동 피부과", 5L);
        context.remember("  ", 6L);
        assertTrue(context.current(6L).isEmpty());

        context.remember("서울 종로구 약국", 7L);
        context.clear();
        assertTrue(context.current(8L).isEmpty());
    }
}
