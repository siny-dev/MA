package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConditionCancellationMatcherTest {
    @Test
    public void trailingCancellationAppliesToCoordinatedTopics() {
        String utterance = "주차랑 계단은 필요 없어";

        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "stairs"));
    }

    @Test
    public void recognizedKoreanCoordinationParticlesShareTrailingCancellation() {
        String[] utterances = {
                "주차하고 계단은 필요 없어", "주차와 계단은 필요 없어",
                "주차과 계단은 필요 없어", "주차및 계단은 필요 없어",
                "주차도 계단은 필요 없어"
        };
        for (String utterance : utterances) {
            assertTrue(utterance, ConditionCancellationMatcher.hasTopicCancellation(
                    utterance, "parking"));
        }
    }

    @Test
    public void laterIndependentTopicCancellationDoesNotEraseEarlierCoordinatedTopics() {
        String utterance = "주차랑 계단은 원하고 엘리베이터는 필요 없어";

        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "stairs"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "elevator"));
    }

    @Test
    public void sharedPostposedCancellationStillAppliesToWholeCoordination() {
        String utterance = "주차랑 계단은 괜찮고 엘리베이터는 필요 없어";

        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "stairs"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "elevator"));
    }

    @Test
    public void explicitCancellationStillAppliesAcrossCoordinatedTopics() {
        String utterance = "주차랑 계단은 필요 없고 엘리베이터는 필요 없어";

        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "stairs"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "elevator"));
    }

    @Test
    public void directCancellationRemainsLocalToItsTopic() {
        String utterance = "주차는 필요 없어 계단은 있어야 해";

        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(utterance, "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(utterance, "stairs"));
    }

    @Test
    public void slopeMatcherDoesNotConfuseRampWithIncline() {
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "경사로는 필요 없어", "slope"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(
                "경사로는 필요 없어", "ramp"));
    }

    @Test
    public void okayAsAQualityModifierIsNotAConditionCancellation() {
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차 괜찮은 곳", "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차는 괜찮아", "parking"));
        assertTrue(ConditionCancellationMatcher.isStandaloneNeutralOkayExpression(
                "주차는 괜찮아", "parking"));
        assertFalse(ConditionCancellationMatcher.isStandaloneNeutralOkayExpression(
                "주차 괜찮은 곳", "parking"));
        assertFalse(ConditionCancellationMatcher.isStandaloneNeutralOkayExpression(
                "주차 괜찮아?", "parking"));
        assertFalse(ConditionCancellationMatcher.isStandaloneNeutralOkayExpression(
                "주차는 괜찮은데 엘리베이터는 필요 없어", "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차는 빼지 마", "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차는 문제없어", "parking"));
        assertFalse(ConditionCancellationMatcher.hasCancellationText("괜찮아요"));
    }

    @Test
    public void okayOnlyCancelsWhenMaAskedAboutThatTopic() {
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차 괜찮아?", "parking"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차 괜찮아", "parking"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(
                "주차 괜찮아", "parking", true));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                "주차 괜찮아?", "parking", true));
        assertTrue(ConditionCancellationMatcher.isNeutralOkayExpression(
                "주차 괜찮아?", "parking"));
        assertTrue(ConditionCancellationMatcher.isOkayAnswer("괜찮아요"));
        assertFalse(ConditionCancellationMatcher.isOkayAnswer("주차 괜찮아?"));
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.YES,
                ConditionCancellationMatcher.classifyConditionAnswer("네"));
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.NO,
                ConditionCancellationMatcher.classifyConditionAnswer("아니요"));
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.YES,
                ConditionCancellationMatcher.classifyConditionAnswer("그거 빼줘"));
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.UNCLEAR,
                ConditionCancellationMatcher.classifyConditionAnswer("그래도 돼"));
        assertEquals(ConditionCancellationMatcher.AnswerPolarity.UNCLEAR,
                ConditionCancellationMatcher.classifyConditionAnswer("주차 괜찮아?"));
    }

    @Test
    public void okayButClauseIsNeutralWhileIndependentCancellationRemainsLocal() {
        String utterance = "주차는 괜찮은데 엘리베이터는 필요 없어";
        assertTrue(ConditionCancellationMatcher.isNeutralOkayExpression(
                utterance, "parking"));
        assertFalse(ConditionCancellationMatcher.isNeutralOkayExpression(
                utterance, "elevator"));
        assertFalse(ConditionCancellationMatcher.hasTopicCancellation(
                utterance, "parking"));
        assertTrue(ConditionCancellationMatcher.hasTopicCancellation(
                utterance, "elevator"));
    }
}
