package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PendingActionGateTest {
    @Test public void replacesThePreviousActionAndConsumesConditionAnswerOnce() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")), 10L);
        gate.replaceConditionAnswer("parking", 20L);

        PendingActionGate.Answer answer = gate.consumePending("주차는 빼지 마", 21L);
        assertEquals(PendingActionGate.Type.CONDITION_ANSWER, answer.type);
        assertEquals("parking", answer.scopeKey);
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.NO, answer.polarity);
        assertNull(gate.consumePending("네", 22L));
    }

    @Test public void selectionPreservesMultipleTopicsForSequentialConfirmation() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")), 100L);
        PendingActionGate.Answer answer = gate.consumePending("둘 다", 101L);

        assertEquals(PendingActionGate.Type.TOPIC_SELECTION, answer.type);
        assertEquals(ConditionTopicResolver.Kind.MULTIPLE, answer.selectionKind);
        assertEquals(Arrays.asList("parking", "stairs"), answer.selectedTopicKeys);
    }

    @Test public void allowedTopicMatchWinsBeforeTheNewDestinationEscape() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")),
                "selection-round-1", 100L);
        ConditionTopicResolver.Resolution preview = gate.previewTopicSelection("주차", 101L);
        assertEquals(ConditionTopicResolver.Kind.SELECTED, preview.kind);
        assertEquals(PendingActionGate.Type.TOPIC_SELECTION, gate.pendingType(101L));
        PendingActionGate.Answer answer = gate.consumePending("주차", 101L);
        assertEquals("selection-round-1", answer.scopeKey);
        assertEquals("parking", answer.selectedTopicKey);
    }

    @Test public void expiredYesNoAndTopicAnswersReturnOneExpiryNoticeWithoutBinding() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceConditionAnswer("parking", 0L);
        PendingActionGate.Answer expired = gate.consumePending("네",
                PendingActionGate.CONDITION_TTL_MS + 1L);
        assertTrue(expired.expired);
        assertNull(gate.consumePending("네", PendingActionGate.CONDITION_TTL_MS + 2L));

        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")), 10L);
        PendingActionGate.Answer expiredTopic = gate.consumePending("계단",
                10L + PendingActionGate.CONDITION_TTL_MS + 1L);
        assertTrue(expiredTopic.expired);
    }

    @Test public void unrelatedUtteranceAfterExpiryCanStartANewRequest() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")), 10L);
        assertNull(gate.consumePending("서동 피부과 찾아줘",
                10L + PendingActionGate.CONDITION_TTL_MS + 1L));
    }

    @Test public void selectedTopicsCanBeConfirmedSequentiallyWithoutAnIdleAnswerSlot() {
        PendingActionGate gate = new PendingActionGate();
        gate.replaceTopicSelection(new LinkedHashSet<>(Arrays.asList("parking", "stairs")), 10L);
        PendingActionGate.Answer selection = gate.consumePending("둘 다", 11L);
        assertEquals(ConditionTopicResolver.Kind.MULTIPLE, selection.selectionKind);

        gate.replaceConditionAnswer(selection.selectedTopicKeys.get(0), 12L);
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.YES,
                gate.consumePending("네", 13L).polarity);
        gate.replaceConditionAnswer(selection.selectedTopicKeys.get(1), 14L);
        PendingActionGate.Answer second = gate.consumePending("아니요", 15L);
        assertEquals("stairs", second.scopeKey);
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.NO, second.polarity);
    }
}
