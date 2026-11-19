package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class ConditionTopicResolverTest {
    private static Set<String> topics(String... keys) {
        return new LinkedHashSet<>(Arrays.asList(keys));
    }

    @Test public void resolvesOnlyOneOfTheAllowedTopics() {
        ConditionTopicResolver.Resolution parking = ConditionTopicResolver.resolve(
                "주차", topics("parking", "stairs"));
        assertEquals(ConditionTopicResolver.Kind.SELECTED, parking.kind);
        assertEquals("parking", parking.topicKeys.get(0));

        ConditionTopicResolver.Resolution stairs = ConditionTopicResolver.resolve(
                "계단", topics("parking", "stairs"));
        assertEquals(ConditionTopicResolver.Kind.SELECTED, stairs.kind);
        assertEquals("stairs", stairs.topicKeys.get(0));
    }

    @Test public void allAndMultipleAnswersReturnTheSelectedAllowedSet() {
        ConditionTopicResolver.Resolution both = ConditionTopicResolver.resolve(
                "주차랑 계단 둘 다", topics("parking", "stairs"));
        assertEquals(ConditionTopicResolver.Kind.MULTIPLE, both.kind);
        assertEquals(topics("parking", "stairs"), new LinkedHashSet<>(both.topicKeys));

        ConditionTopicResolver.Resolution all = ConditionTopicResolver.resolve(
                "둘 다", topics("parking", "stairs"));
        assertEquals(ConditionTopicResolver.Kind.MULTIPLE, all.kind);
        assertEquals(topics("parking", "stairs"), new LinkedHashSet<>(all.topicKeys));
    }

    @Test public void unrelatedAndOutOfScopeTopicsCannotSelectACondition() {
        assertEquals(ConditionTopicResolver.Kind.UNCLEAR,
                ConditionTopicResolver.resolve("전문의", topics("parking", "stairs")).kind);
        assertEquals(ConditionTopicResolver.Kind.UNCLEAR,
                ConditionTopicResolver.resolve("서동 피부과 찾아줘",
                        topics("parking", "stairs")).kind);
        assertEquals(ConditionTopicResolver.Kind.UNCLEAR,
                ConditionTopicResolver.resolve("전문의 모두 있는 병원",
                        topics("parking", "stairs")).kind);
        assertEquals(ConditionTopicResolver.Kind.UNCLEAR,
                ConditionTopicResolver.resolve("주차 말고 계단", topics("parking", "stairs")).kind);
    }

    @Test public void specificRampNameDoesNotAlsoSelectSlope() {
        ConditionTopicResolver.Resolution resolution = ConditionTopicResolver.resolve(
                "경사로", topics("ramp", "slope"));
        assertEquals(ConditionTopicResolver.Kind.SELECTED, resolution.kind);
        assertEquals("ramp", resolution.topicKeys.get(0));
    }
}
