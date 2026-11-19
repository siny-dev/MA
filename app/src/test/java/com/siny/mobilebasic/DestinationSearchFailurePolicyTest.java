package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationSearchFailurePolicyTest {
    @Test public void distinguishesNoSearchResultsFromResultsRejectedByArea() {
        NaverLocalSearchClient.Result noMatches = new NaverLocalSearchClient.Result(
                Collections.emptyList(), 0, 0, 0, 0);
        NaverLocalSearchClient.Result filtered = new NaverLocalSearchClient.Result(
                Collections.emptyList(), 3, 3, 3, 0);

        assertEquals(DestinationSearchFailurePolicy.Kind.NO_MATCHES,
                DestinationSearchFailurePolicy.classify(noMatches));
        assertEquals(DestinationSearchFailurePolicy.Kind.CONSTRAINT_MISMATCH,
                DestinationSearchFailurePolicy.classify(filtered));
        assertTrue(DestinationSearchFailurePolicy.messageFor(filtered).contains("검색 결과는 있었지만"));
        assertFalse(DestinationSearchFailurePolicy.messageFor(filtered)
                .equals(DestinationSearchFailurePolicy.messageFor(noMatches)));
    }

    @Test public void distinguishesUnusableResultsAndCapsAutomaticVoiceRetryAtOne() {
        NaverLocalSearchClient.Result unusable = new NaverLocalSearchClient.Result(
                Collections.emptyList(), 2, 0, 0, 0);

        assertEquals(DestinationSearchFailurePolicy.Kind.UNUSABLE_RESULTS,
                DestinationSearchFailurePolicy.classify(unusable));
        assertTrue(DestinationSearchFailurePolicy.messageFor(unusable).contains("주소나 위치"));
        assertTrue(DestinationSearchFailurePolicy.shouldRetryVoice(1));
        assertFalse(DestinationSearchFailurePolicy.shouldRetryVoice(2));
    }
}
