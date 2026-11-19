package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationClarificationCancellationTest {
    @Test public void recognizesExplicitCancellationButNotAnUnclearNoOrNewDestination() {
        assertTrue(DestinationClarificationCancellation.isCancellation("그만해요."));
        assertTrue(DestinationClarificationCancellation.isCancellation("취소할래요"));
        assertTrue(DestinationClarificationCancellation.isCancellation("이제 됐어"));
        assertTrue(DestinationClarificationCancellation.isCancellation("아니, 됐어요"));
        assertFalse(DestinationClarificationCancellation.isCancellation("아니요"));
        assertFalse(DestinationClarificationCancellation.isCancellation("아니 서동"));
        assertFalse(DestinationClarificationCancellation.isCancellation("아니 서동역 피부과"));
        assertFalse(DestinationClarificationCancellation.isCancellation("아니 부산 금정구 서동 피부과"));
        assertFalse(DestinationClarificationCancellation.isCancellation("서동 약국"));
        assertFalse(DestinationClarificationCancellation.isCancellation("부산 금정구 서동 피부과"));
    }
}
