package com.siny.mobilebasic;

/** Narrow-scope policy for resolving the accessible NAVER guide-start control. */
final class NaverGuideClickTargetPolicy {
    static final int MAX_CLICKABLE_ANCESTOR_DEPTH = 3;
    static final int MAX_TARGET_VIEWPORT_PERCENT = 20;

    private NaverGuideClickTargetPolicy() {}

    static boolean isSafe(int clickableNodesOnPath, boolean hasCompetingClickableDescendant,
                          int targetLeft, int targetTop, int targetRight, int targetBottom,
                          int labelLeft, int labelTop, int labelRight, int labelBottom,
                          int viewportLeft, int viewportTop, int viewportRight, int viewportBottom) {
        if (clickableNodesOnPath != 1 || hasCompetingClickableDescendant) return false;
        long targetWidth = (long) targetRight - targetLeft;
        long targetHeight = (long) targetBottom - targetTop;
        long labelWidth = (long) labelRight - labelLeft;
        long labelHeight = (long) labelBottom - labelTop;
        long viewportWidth = (long) viewportRight - viewportLeft;
        long viewportHeight = (long) viewportBottom - viewportTop;
        if (targetWidth <= 0L || targetHeight <= 0L
                || labelWidth <= 0L || labelHeight <= 0L
                || viewportWidth <= 0L || viewportHeight <= 0L) return false;
        if (targetLeft > labelLeft || targetTop > labelTop
                || targetRight < labelRight || targetBottom < labelBottom) return false;
        if (targetLeft < viewportLeft || targetTop < viewportTop
                || targetRight > viewportRight || targetBottom > viewportBottom) return false;
        double targetArea = (double) targetWidth * targetHeight;
        double viewportArea = (double) viewportWidth * viewportHeight;
        return targetArea / viewportArea <= MAX_TARGET_VIEWPORT_PERCENT / 100.0d;
    }
}
