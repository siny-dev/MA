package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Drops bulk-confirmation topics that a prior condition change has already removed. */
final class ConditionTopicQueue {
    static final class Next {
        final String topicKey;
        final List<String> skippedByCascade;
        final List<String> skippedWhileInactive;

        private Next(String topicKey, List<String> skippedByCascade,
                     List<String> skippedWhileInactive) {
            this.topicKey = topicKey;
            this.skippedByCascade = Collections.unmodifiableList(new ArrayList<>(skippedByCascade));
            this.skippedWhileInactive = Collections.unmodifiableList(
                    new ArrayList<>(skippedWhileInactive));
        }
    }

    private ConditionTopicQueue() {}

    static Next takeNextActive(List<String> queuedTopicKeys, Set<String> activeTopicKeys,
                               Set<String> cascadeRemovedTopicKeys) {
        ArrayList<String> skippedByCascade = new ArrayList<>();
        ArrayList<String> skippedWhileInactive = new ArrayList<>();
        while (queuedTopicKeys != null && !queuedTopicKeys.isEmpty()) {
            String next = queuedTopicKeys.remove(0);
            if (activeTopicKeys != null && activeTopicKeys.contains(next)) {
                return new Next(next, skippedByCascade, skippedWhileInactive);
            }
            if (cascadeRemovedTopicKeys != null && cascadeRemovedTopicKeys.contains(next)) {
                skippedByCascade.add(next);
            } else {
                skippedWhileInactive.add(next);
            }
        }
        return new Next(null, skippedByCascade, skippedWhileInactive);
    }
}
