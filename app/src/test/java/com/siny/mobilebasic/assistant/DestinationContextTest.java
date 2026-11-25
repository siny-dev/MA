package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DestinationContextTest {
    @Test public void followUpKeepsTheRegionAndAddsTheNewCondition() {
        assertEquals("부산 금정구 서동 피부과 전문의가 있는 곳",
                DestinationContext.combine("부산 금정구 서동 피부과", "전문의가 있는 곳"));
    }

    @Test public void missingPreviousRequestDoesNotAddAnEmptyPrefix() {
        assertEquals("서동 피부과", DestinationContext.combine("  ", " 서동   피부과 "));
    }

    @Test public void conversationContextIsBoundedWithoutDroppingTheOriginalRegion() {
        String previous = "부산 금정구 서동 피부과";
        String combined = DestinationContext.combine(previous, "조건 ".repeat(300));
        assertTrue(combined.startsWith(previous + " "));
        assertTrue(combined.length() <= 500);
    }
}
