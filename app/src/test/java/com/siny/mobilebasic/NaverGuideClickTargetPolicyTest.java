package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NaverGuideClickTargetPolicyTest {
    private static final int VIEWPORT_LEFT = 0;
    private static final int VIEWPORT_TOP = 0;
    private static final int VIEWPORT_RIGHT = 1_000;
    private static final int VIEWPORT_BOTTOM = 1_000;

    @Test public void narrowUniqueButtonAncestorIsAllowed() {
        assertTrue(isSafe(1, false,
                200, 800, 800, 900,
                350, 830, 650, 870));
    }

    @Test public void multipleClickableAncestorsAndCompetingChildAreRejected() {
        assertFalse(isSafe(2, false,
                200, 800, 800, 900,
                350, 830, 650, 870));
        assertFalse(isSafe(1, true,
                200, 800, 800, 900,
                350, 830, 650, 870));
    }

    @Test public void oversizedActionParentIsRejected() {
        assertFalse(isSafe(1, false,
                0, 700, 1_000, 1_000,
                350, 830, 650, 870));
    }

    @Test public void viewportAreaBoundaryIsInclusiveAtTwentyPercent() {
        assertTrue(isSafe(1, false,
                0, 700, 1_000, 900,
                350, 800, 650, 850));
        assertFalse(isSafe(1, false,
                0, 700, 1_000, 901,
                350, 800, 650, 850));
    }

    @Test public void actionBoundsMustContainLabelAndFitInsideViewport() {
        assertFalse(isSafe(1, false,
                200, 800, 800, 900,
                350, 830, 850, 870));
        assertFalse(isSafe(1, false,
                -1, 800, 800, 900,
                350, 830, 650, 870));
    }

    @Test public void unknownOrEmptyGeometryIsRejected() {
        assertFalse(isSafe(1, false,
                0, 0, 0, 0,
                0, 0, 0, 0));
        assertFalse(isSafe(1, false,
                200, 800, 800, 900,
                400, 850, 400, 850));
        assertFalse(NaverGuideClickTargetPolicy.isSafe(1, false,
                200, 800, 800, 900,
                350, 830, 650, 870,
                0, 0, 0, 0));
    }

    @Test public void ancestorSearchDepthIsThreeParents() {
        assertTrue(NaverGuideClickTargetPolicy.MAX_CLICKABLE_ANCESTOR_DEPTH == 3);
    }

    private boolean isSafe(int clickableCount, boolean competingChild,
                           int targetLeft, int targetTop, int targetRight, int targetBottom,
                           int labelLeft, int labelTop, int labelRight, int labelBottom) {
        return NaverGuideClickTargetPolicy.isSafe(clickableCount, competingChild,
                targetLeft, targetTop, targetRight, targetBottom,
                labelLeft, labelTop, labelRight, labelBottom,
                VIEWPORT_LEFT, VIEWPORT_TOP, VIEWPORT_RIGHT, VIEWPORT_BOTTOM);
    }
}
