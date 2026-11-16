package com.siny.mobilebasic;

import java.util.List;

/** Accepts alternate speech candidates only when they converge on one place. */
final class CandidateHypothesisResolver {
    private CandidateHypothesisResolver() {}

    static int resolve(List<Integer> candidateIndexes) {
        if (candidateIndexes == null || candidateIndexes.isEmpty()) return -1;
        int selected = -1;
        for (Integer index : candidateIndexes) {
            if (index == null || index < 0) continue;
            if (selected >= 0 && selected != index) return -1;
            selected = index;
        }
        return selected;
    }
}
