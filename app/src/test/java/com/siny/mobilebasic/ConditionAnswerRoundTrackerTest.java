package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConditionAnswerRoundTrackerTest {
    @Test public void unclearGetsOnlyOneReaskAndAnewQuestionStartsANewRound() {
        ConditionAnswerRoundTracker rounds = new ConditionAnswerRoundTracker();
        rounds.beginQuestion("parking");

        assertTrue(rounds.shouldReask("parking"));
        // Consuming/reopening the answer gate does not reset this flow-scoped counter.
        new PendingActionGate().replaceConditionAnswer("parking", 10L);
        assertFalse(rounds.shouldReask("parking"));

        rounds.beginQuestion("parking");
        assertTrue(rounds.shouldReask("parking"));
    }

    @Test public void resetAndQuestionReplacementClearOldRoundCounts() {
        ConditionAnswerRoundTracker rounds = new ConditionAnswerRoundTracker();
        rounds.beginQuestion("parking");
        assertTrue(rounds.shouldReask("parking"));

        rounds.beginQuestion("elevator");
        assertFalse(rounds.shouldReask("parking"));
        assertTrue(rounds.shouldReask("elevator"));

        rounds.clear();
        rounds.beginQuestion("parking");
        assertTrue(rounds.shouldReask("parking"));
    }

    @Test public void repeatedNegationNeverBecomesAReaskPositive() {
        for (String utterance : new String[]{"주차는 빼지 마", "주차는 빼지 마", "빼지 마"}) {
            assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
            assertTrue(ConditionCancellationMatcher.classifyConditionAnswer(utterance)
                    == ConditionCancellationMatcher.AnswerPolarity.NO);
        }
    }

    @Test public void selectionAndAnswerStagesHaveIndependentSingleReaskRounds() {
        ConditionAnswerRoundTracker rounds = new ConditionAnswerRoundTracker();
        rounds.beginQuestion(PendingActionGate.Type.TOPIC_SELECTION, "parking,stairs");
        assertTrue(rounds.shouldReask(PendingActionGate.Type.TOPIC_SELECTION, "parking,stairs"));
        assertFalse(rounds.shouldReask(PendingActionGate.Type.TOPIC_SELECTION, "parking,stairs"));

        rounds.beginQuestion(PendingActionGate.Type.CONDITION_ANSWER, "parking");
        assertTrue(rounds.shouldReask(PendingActionGate.Type.CONDITION_ANSWER, "parking"));
        assertFalse(rounds.shouldReask(PendingActionGate.Type.TOPIC_SELECTION, "parking,stairs"));
        assertFalse(rounds.shouldReask(PendingActionGate.Type.CONDITION_ANSWER, "parking"));
    }
}
