package com.siny.mobilebasic;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConditionTopicQueueTest {
    @Test public void cascadeRemovedChildIsSkippedInsteadOfAskedAgain() {
        LinkedHashSet<String> activeAfterParentCancellation =
                new LinkedHashSet<>(Arrays.asList("parking"));
        activeAfterParentCancellation.remove("accessibility");
        activeAfterParentCancellation.remove("wheelchair");
        ArrayList<String> queue = new ArrayList<>(Arrays.asList("wheelchair", "parking"));

        ConditionTopicQueue.Next next = ConditionTopicQueue.takeNextActive(
                queue, activeAfterParentCancellation,
                new LinkedHashSet<>(Arrays.asList("wheelchair")));

        assertEquals("parking", next.topicKey);
        assertEquals(Arrays.asList("wheelchair"), next.skippedByCascade);
        assertEquals(0, next.skippedWhileInactive.size());
        assertEquals(0, queue.size());
    }

    @Test public void bulkFlowEndsWhenAllRemainingTopicsWereRemovedByCascade() {
        LinkedHashSet<String> activeAfterParentCancellation = new LinkedHashSet<>();
        activeAfterParentCancellation.remove("accessibility");
        activeAfterParentCancellation.remove("wheelchair");
        ArrayList<String> queue = new ArrayList<>(Arrays.asList("wheelchair"));

        ConditionTopicQueue.Next next = ConditionTopicQueue.takeNextActive(
                queue, activeAfterParentCancellation,
                new LinkedHashSet<>(Arrays.asList("wheelchair")));

        assertNull(next.topicKey);
        assertEquals(Arrays.asList("wheelchair"), next.skippedByCascade);
        assertEquals(0, queue.size());
    }

    @Test public void noOnFirstParentQuestionKeepsTheChildEligibleForItsOwnQuestion() {
        LinkedHashSet<String> activeAfterNo =
                new LinkedHashSet<>(Arrays.asList("accessibility", "wheelchair"));
        ArrayList<String> queue = new ArrayList<>(Arrays.asList("wheelchair"));

        ConditionTopicQueue.Next next = ConditionTopicQueue.takeNextActive(
                queue, activeAfterNo, new LinkedHashSet<>());

        assertEquals("wheelchair", next.topicKey);
        assertEquals(0, next.skippedByCascade.size());
        assertEquals(0, next.skippedWhileInactive.size());
    }

    @Test public void nonCascadeInactiveTopicGetsDifferentSkipReason() {
        ArrayList<String> queue = new ArrayList<>(Arrays.asList("stairs"));
        ConditionTopicQueue.Next next = ConditionTopicQueue.takeNextActive(
                queue, new LinkedHashSet<>(Arrays.asList("parking")), new LinkedHashSet<>());

        assertNull(next.topicKey);
        assertEquals(Arrays.asList("stairs"), next.skippedWhileInactive);
        assertEquals(0, next.skippedByCascade.size());
    }
}
