package com.siny.mobilebasic;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One expiring, typed slot for the answer to the most recent spoken MA prompt. */
final class PendingActionGate {
    enum Type { CONDITION_ANSWER, TOPIC_SELECTION, DESTINATION_CONFIRM, CANDIDATE_SELECT,
        SPECIALIST_CONSENT }

    static final long CONDITION_TTL_MS = 2 * 60_000L;

    private Action pending;
    private Action expired;

    Type pendingType(long now) {
        expireIfNeeded(now);
        return pending == null ? null : pending.type;
    }

    boolean hasExpiredAction() {
        return expired != null;
    }

    void replaceConditionAnswer(String topicKey, long now) {
        replace(new Action(Type.CONDITION_ANSWER, topicKey,
                Collections.emptySet(), now + CONDITION_TTL_MS));
    }

    void replaceTopicSelection(Set<String> topicKeys, long now) {
        replaceTopicSelection(topicKeys, "topic-selection", now);
    }

    void replaceTopicSelection(Set<String> topicKeys, String scopeKey, long now) {
        if (scopeKey == null || scopeKey.trim().isEmpty()) {
            clear();
            return;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (topicKeys != null) {
            for (String key : topicKeys) {
                if (ConditionCancellationMatcher.isKnownTopic(key)) keys.add(key);
            }
        }
        replace(keys.isEmpty() ? null : new Action(Type.TOPIC_SELECTION, scopeKey,
                keys, now + CONDITION_TTL_MS));
    }

    ConditionTopicResolver.Resolution previewTopicSelection(String utterance, long now) {
        expireIfNeeded(now);
        if (pending == null || pending.type != Type.TOPIC_SELECTION) return null;
        return ConditionTopicResolver.resolve(utterance, pending.topicKeys);
    }

    void replace(Type type, String scopeKey, long ttlMs, long now) {
        if (type == null || scopeKey == null || scopeKey.trim().isEmpty() || ttlMs <= 0L) {
            clear();
            return;
        }
        replace(new Action(type, scopeKey, Collections.emptySet(), now + ttlMs));
    }

    private void replace(Action action) {
        pending = action;
        expired = null;
    }

    boolean isPending(long now) {
        expireIfNeeded(now);
        return pending != null;
    }

    Answer consume(Type expectedType, String expectedScope, String utterance, long now) {
        expireIfNeeded(now);
        if (pending == null) return consumeExpiredAnswer(utterance);
        Action action = pending;
        pending = null;
        expired = null;
        if (action.type != expectedType || expectedScope != null
                && !expectedScope.equals(action.scopeKey)) return null;
        switch (action.type) {
            case CONDITION_ANSWER:
                return Answer.condition(action,
                        ConditionCancellationMatcher.classifyConditionAnswer(utterance));
            case TOPIC_SELECTION:
                return Answer.selection(action,
                        ConditionTopicResolver.resolve(utterance, action.topicKeys));
            default:
                return Answer.action(action, utterance);
        }
    }

    Answer consumePending(String utterance, long now) {
        expireIfNeeded(now);
        if (pending == null) return consumeExpiredAnswer(utterance);
        return consume(pending.type, pending.scopeKey, utterance, now);
    }

    void clear() {
        pending = null;
        expired = null;
    }

    private void expireIfNeeded(long now) {
        if (pending != null && pending.expiresAt <= now) {
            expired = pending;
            pending = null;
        }
    }

    private Answer consumeExpiredAnswer(String utterance) {
        if (expired == null) return null;
        Action old = expired;
        expired = null;
        boolean answerLike = old.type == Type.TOPIC_SELECTION
                ? ConditionTopicResolver.resolve(utterance, old.topicKeys).kind
                != ConditionTopicResolver.Kind.UNCLEAR
                : ConditionCancellationMatcher.classifyConditionAnswer(utterance)
                != ConditionCancellationMatcher.AnswerPolarity.UNCLEAR;
        return answerLike ? Answer.expired() : null;
    }

    static final class Action {
        final Type type;
        final String scopeKey;
        final Set<String> topicKeys;
        final long expiresAt;

        Action(Type type, String scopeKey, Set<String> topicKeys, long expiresAt) {
            this.type = type;
            this.scopeKey = scopeKey;
            this.topicKeys = Collections.unmodifiableSet(new LinkedHashSet<>(topicKeys));
            this.expiresAt = expiresAt;
        }
    }

    static final class Answer {
        final Type type;
        final String scopeKey;
        final Set<String> allowedTopicKeys;
        final String selectedTopicKey;
        final List<String> selectedTopicKeys;
        final ConditionTopicResolver.Kind selectionKind;
        final ConditionCancellationMatcher.AnswerPolarity polarity;
        final String utterance;
        final boolean expired;

        private Answer(Type type, String scopeKey, String selectedTopicKey,
                       ConditionCancellationMatcher.AnswerPolarity polarity,
                       String utterance, boolean expired) {
            this.type = type;
            this.scopeKey = scopeKey;
            this.allowedTopicKeys = Collections.emptySet();
            this.selectedTopicKey = selectedTopicKey;
            this.selectedTopicKeys = Collections.emptyList();
            this.selectionKind = null;
            this.polarity = polarity;
            this.utterance = utterance;
            this.expired = expired;
        }

        static Answer condition(Action action,
                                ConditionCancellationMatcher.AnswerPolarity polarity) {
            return new Answer(action.type, action.scopeKey, null, polarity, "", false);
        }

        private Answer(Action action, ConditionTopicResolver.Resolution resolution) {
            this.type = action.type;
            this.scopeKey = action.scopeKey;
            this.allowedTopicKeys = action.topicKeys;
            this.selectedTopicKeys = resolution.topicKeys;
            this.selectionKind = resolution.kind;
            this.selectedTopicKey = resolution.kind == ConditionTopicResolver.Kind.SELECTED
                    && !resolution.topicKeys.isEmpty() ? resolution.topicKeys.get(0) : null;
            this.polarity = null;
            this.utterance = "";
            this.expired = false;
        }

        static Answer selection(Action action, ConditionTopicResolver.Resolution resolution) {
            return new Answer(action, resolution);
        }

        static Answer action(Action action, String utterance) {
            return new Answer(action.type, action.scopeKey, null, null,
                    utterance == null ? "" : utterance, false);
        }

        static Answer expired() {
            return new Answer(null, "", null,
                    ConditionCancellationMatcher.AnswerPolarity.UNCLEAR, "", true);
        }
    }
}
