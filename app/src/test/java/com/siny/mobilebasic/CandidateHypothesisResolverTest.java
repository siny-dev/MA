package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class CandidateHypothesisResolverTest {
    @Test public void acceptsOnlyOneConsistentAlternativePlace() {
        assertEquals(1, CandidateHypothesisResolver.resolve(Arrays.asList(1, -1, 1)));
        assertEquals(-1, CandidateHypothesisResolver.resolve(Arrays.asList(-1, -1)));
        assertEquals(-1, CandidateHypothesisResolver.resolve(Arrays.asList(0, 1)));
    }
}
