package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchGenerationGuardTest {
    @Test public void queuedOldSearchResponseCannotOpenCandidateAfterClarificationReplacesIt() {
        long originalSearch = 41L;
        long activeSearchAfterClarification = 42L;
        assertFalse(SearchGenerationGuard.isCurrent(originalSearch, activeSearchAfterClarification));
        assertTrue(SearchGenerationGuard.isCurrent(activeSearchAfterClarification,
                activeSearchAfterClarification));
    }
}
