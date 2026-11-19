package com.siny.mobilebasic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Flow-scoped, one-reask limit that survives consuming and reopening the pending answer gate. */
final class ConditionAnswerRoundTracker {
    private static final int MAX_REASKS_PER_QUESTION = 1;
    private final Map<String, Integer> reasksByTopic = new LinkedHashMap<>();
    private String activeActionType = "";
    private String activeScope = "";

    void beginQuestion(String topicKey) {
        beginQuestion(PendingActionGate.Type.CONDITION_ANSWER, topicKey);
    }

    void beginQuestion(PendingActionGate.Type actionType, String scopeKey) {
        clear();
        if (actionType != null && scopeKey != null && !scopeKey.trim().isEmpty()) {
            activeActionType = actionType.name();
            activeScope = scopeKey;
            reasksByTopic.put(counterKey(activeActionType, activeScope), 0);
        }
    }

    boolean shouldReask(String topicKey) {
        return shouldReask(PendingActionGate.Type.CONDITION_ANSWER, topicKey);
    }

    boolean shouldReask(PendingActionGate.Type actionType, String scopeKey) {
        if (actionType == null || scopeKey == null
                || !actionType.name().equals(activeActionType) || !scopeKey.equals(activeScope)) {
            return false;
        }
        String key = counterKey(activeActionType, activeScope);
        int used = reasksByTopic.containsKey(key) ? reasksByTopic.get(key) : 0;
        if (used >= MAX_REASKS_PER_QUESTION) {
            reasksByTopic.remove(key);
            return false;
        }
        reasksByTopic.put(key, used + 1);
        return true;
    }

    void resolve(String topicKey) {
        resolve(PendingActionGate.Type.CONDITION_ANSWER, topicKey);
    }

    void resolve(PendingActionGate.Type actionType, String scopeKey) {
        if (actionType == null || scopeKey == null) return;
        reasksByTopic.remove(counterKey(actionType.name(), scopeKey));
        if (actionType.name().equals(activeActionType) && scopeKey.equals(activeScope)) {
            activeActionType = "";
            activeScope = "";
        }
    }

    void clear() {
        reasksByTopic.clear();
        activeActionType = "";
        activeScope = "";
    }

    private static String counterKey(String actionType, String scopeKey) {
        return actionType + "\u0000" + scopeKey;
    }
}
