package com.siny.mobilebasic;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Clicks only NAVER Map's guide-start control after MA has a one-shot user confirmation. */
public final class NaverGuideAccessibilityService extends AccessibilityService {
    private static final Set<String> START_LABELS = new HashSet<>();
    private static final Set<String> ACTIVE_LABELS = new HashSet<>();
    private static final Set<String> TRANSIT_MODE_LABELS = new HashSet<>();
    private static final Set<String> CAR_MODE_LABELS = new HashSet<>();
    private static final int MAX_NODES = 600;
    private long observedArmGeneration = -1L;
    private String armBaselineSignature = "";
    private boolean armBaselineCaptured;
    private String lastVerifiedSignature = "";
    private long lastVerifiedAtElapsedRealtime;
    private long stableScreenStartedAtElapsedRealtime;
    private int stableScreenEvents;
    private String lastActiveGuidanceSignature = "";
    private long lastActiveGuidanceAtElapsedRealtime;
    private long stableActiveGuidanceStartedAtElapsedRealtime;
    private int stableActiveGuidanceEvents;

    static {
        START_LABELS.add("안내 시작");
        START_LABELS.add("안내시작");
        START_LABELS.add("안내 시작하기");
        START_LABELS.add("길안내 시작");
        START_LABELS.add("길안내시작");
        START_LABELS.add("대중교통 안내 시작");
        START_LABELS.add("대중교통안내시작");
        ACTIVE_LABELS.add("안내 종료");
        ACTIVE_LABELS.add("안내종료");
        ACTIVE_LABELS.add("안내 중");
        ACTIVE_LABELS.add("안내중");
        ACTIVE_LABELS.add("경로 안내 중");
        ACTIVE_LABELS.add("경로안내중");
        TRANSIT_MODE_LABELS.add("대중교통");
        TRANSIT_MODE_LABELS.add("대중교통 경로");
        TRANSIT_MODE_LABELS.add("버스");
        TRANSIT_MODE_LABELS.add("지하철");
        TRANSIT_MODE_LABELS.add("버스/지하철");
        TRANSIT_MODE_LABELS.add("버스·지하철");
        TRANSIT_MODE_LABELS.add("버스, 지하철");
        CAR_MODE_LABELS.add("자동차");
        CAR_MODE_LABELS.add("자동차 경로");
        CAR_MODE_LABELS.add("자동차 길찾기");
        CAR_MODE_LABELS.add("승용차");
        CAR_MODE_LABELS.add("자가용");
        CAR_MODE_LABELS.add("차량");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !NaverGuideAutomation.TARGET_PACKAGE.contentEquals(event.getPackageName())) return;

        String result = NaverGuideAutomation.result(this);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Consume a user's manual START tap while the one-shot arm is live.
            // An automatic tap is disarmed before ACTION_CLICK and tagged as a
            // start_tap_* result, so its own click event cannot be mistaken for manual.
            if (result.startsWith("start_tap_")) {
                resetStableScreen();
                return;
            }
            if (NaverGuideAutomation.isArmed(this) && eventMatchesLabel(event, START_LABELS)) {
                NaverGuideAutomation.disarm(this, "manual_start_tapped");
            }
            resetStableScreen();
            resetStableActiveGuidance();
            return;
        }
        if ("start_tap_sent".equals(result) || "manual_start_tapped".equals(result)) {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot == null) {
                resetStableScreen();
                resetStableActiveGuidance();
                return;
            }
            try {
                AccessibilityNodeInfo active = findUniqueLabel(activeRoot, ACTIVE_LABELS);
                if (active == null) {
                    resetStableActiveGuidance();
                } else {
                    String activeLabel = matchingLabel(active, ACTIVE_LABELS);
                    active.recycle();
                    if (observeStableActiveGuidance(activeLabel)) {
                        NaverGuideAutomation.disarm(this, "guidance_active");
                        resetStableActiveGuidance();
                    }
                }
            } finally {
                activeRoot.recycle();
            }
            return;
        }

        if (!NaverGuideAutomation.isArmed(this)) {
            resetStableScreen();
            resetStableActiveGuidance();
            return;
        }
        resetForNewArmGeneration();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            resetStableScreen();
            resetStableActiveGuidance();
            return;
        }
        try {
            if (!isTargetRoot(root)) {
                resetStableScreen();
                resetStableActiveGuidance();
                return;
            }
            // A manually started route consumes the old one-shot arm only after
            // an exact active-guidance label is stable across multiple events.
            // A transient label in a changing route screen must not disarm early.
            AccessibilityNodeInfo active = findUniqueLabel(root, ACTIVE_LABELS);
            if (active != null) {
                String activeLabel = matchingLabel(active, ACTIVE_LABELS);
                active.recycle();
                resetStableScreen();
                if (observeStableActiveGuidance(activeLabel)) {
                    NaverGuideAutomation.disarm(this, "guidance_active");
                    resetStableActiveGuidance();
                }
                return;
            }
            resetStableActiveGuidance();
            // A duplicated or truncated active-guidance label is still a reason
            // to fail closed; it must not be interpreted as an idle route screen.
            if (containsLabelOrUnknown(root, ACTIVE_LABELS)) {
                resetStableScreen();
                return;
            }

            String verifiedDestinationLabel = findVerifiedDestinationLabel(root,
                    NaverGuideAutomation.destinationName(this),
                    NaverGuideAutomation.destinationAddress(this));
            AccessibilityNodeInfo start = findUniqueLabel(root, START_LABELS);
            AccessibilityNodeInfo actionTarget = start == null ? null
                    : findSafeClickableTarget(root, start);

            String modeState = findSelectedRouteMode(root);
            String screenSignature = routeScreenSignature(verifiedDestinationLabel,
                    start, actionTarget, modeState);
            if (!armBaselineCaptured) {
                // The first Naver tree after an arm can still be the previous route
                // or can already be the new route; accessibility does not guarantee
                // which one arrives first. Treat it as a baseline and require visible
                // semantic change before any automatic click. If it was already the
                // correct screen, this deliberately falls back to a manual tap.
                armBaselineSignature = screenSignature;
                armBaselineCaptured = true;
                if (start != null) start.recycle();
                if (actionTarget != null) actionTarget.recycle();
                resetStableScreen();
                return;
            }
            if (screenSignature.equals(armBaselineSignature)) {
                if (start != null) start.recycle();
                if (actionTarget != null) actionTarget.recycle();
                resetStableScreen();
                return;
            }
            if (verifiedDestinationLabel == null || start == null || actionTarget == null
                    || !modeState.startsWith("transit:")) {
                if (start != null) start.recycle();
                if (actionTarget != null) actionTarget.recycle();
                resetStableScreen();
                return;
            }
            if (!observeStableScreen(screenSignature)) {
                start.recycle();
                actionTarget.recycle();
                return;
            }
            start.recycle();
            actionTarget.recycle();

            // Re-read the active window after the stability interval. The node
            // found in the earlier tree may refer to a screen that has since
            // changed (for example, the user manually started guidance).
            AccessibilityNodeInfo clickRoot = getRootInActiveWindow();
            if (clickRoot == null) {
                resetStableScreen();
                return;
            }
            AccessibilityNodeInfo clickTarget = null;
            AccessibilityNodeInfo clickActionTarget = null;
            try {
                if (!isTargetRoot(clickRoot) || containsLabelOrUnknown(clickRoot, ACTIVE_LABELS)) {
                    resetStableScreen();
                    return;
                }
                String clickDestinationLabel = findVerifiedDestinationLabel(clickRoot,
                        NaverGuideAutomation.destinationName(this),
                        NaverGuideAutomation.destinationAddress(this));
                clickTarget = findUniqueLabel(clickRoot, START_LABELS);
                clickActionTarget = clickTarget == null ? null
                        : findSafeClickableTarget(clickRoot, clickTarget);
                String clickModeState = findSelectedRouteMode(clickRoot);
                String clickSignature = routeScreenSignature(clickDestinationLabel,
                        clickTarget, clickActionTarget, clickModeState);
                if (clickDestinationLabel == null || clickTarget == null || clickActionTarget == null
                        || !clickModeState.startsWith("transit:")
                        || !screenSignature.equals(clickSignature)) {
                    resetStableScreen();
                    return;
                }
                // The tree scan and stability window may consume most of the arm's
                // remaining lease. Re-read boot identity and expiry immediately before
                // consuming the one-shot and invoking ACTION_CLICK. Resolve the clickable
                // node first so the lease check stays adjacent to the actual action.
                if (!NaverGuideAutomation.hasMinimumRemaining(this,
                        NaverGuideArmLease.MINIMUM_CLICK_REMAINING_MS)) {
                    resetStableScreen();
                    return;
                }
                // Consume the one-shot before issuing the click so a queued view-click
                // event cannot be interpreted as a later manual tap or second attempt.
                NaverGuideAutomation.disarm(this, "start_tap_pending");
                boolean clicked;
                try {
                    clicked = clickActionTarget.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK);
                } catch (RuntimeException ignored) {
                    clicked = false;
                }
                // Disarm before another window-content event can issue a second click.
                NaverGuideAutomation.disarm(this,
                        clicked ? "start_tap_sent" : "start_tap_failed");
                resetStableScreen();
            } finally {
                if (clickActionTarget != null) clickActionTarget.recycle();
                if (clickTarget != null) clickTarget.recycle();
                clickRoot.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    @Override public void onInterrupt() {
        resetStableScreen();
        resetStableActiveGuidance();
        if (NaverGuideAutomation.isArmed(this)) {
            NaverGuideAutomation.disarm(this, "interrupted");
        }
    }

    private AccessibilityNodeInfo findUniqueLabel(AccessibilityNodeInfo root, Set<String> labels) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo match = null;
        int visited = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            visited++;
            AccessibilityNodeInfo node = pending.removeFirst();
            if (matches(node, labels)) {
                if (match != null) {
                    match.recycle();
                    node.recycle();
                    while (!pending.isEmpty()) pending.removeFirst().recycle();
                    return null;
                }
                match = AccessibilityNodeInfo.obtain(node);
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (visited + pending.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        truncated |= !pending.isEmpty();
        while (!pending.isEmpty()) pending.removeFirst().recycle();
        if (truncated && match != null) {
            match.recycle();
            return null;
        }
        return match;
    }

    private String findVerifiedDestinationLabel(AccessibilityNodeInfo root,
                                                String expectedName, String expectedAddress) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        String match = null;
        int visited = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            visited++;
            AccessibilityNodeInfo node = pending.removeFirst();
            boolean verified = false;
            String label = "";
            if (node.isVisibleToUser()) {
                CharSequence text = node.getText();
                CharSequence description = node.getContentDescription();
                label = (text == null ? "" : text.toString()) + " "
                        + (description == null ? "" : description.toString());
                verified = DestinationScreenMatcher.matches(label, expectedName, expectedAddress);
            }
            if (verified) {
                // NAVER can expose the same confirmed place in more than one visible
                // accessibility node (for example, a title and a route summary). Each
                // node must independently match the exact confirmed name and full
                // address; duplicate nodes for that same destination are not ambiguity.
                if (match == null) match = label.trim();
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (visited + pending.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        truncated |= !pending.isEmpty();
        while (!pending.isEmpty()) pending.removeFirst().recycle();
        return truncated ? null : match;
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        return (text == null ? "" : text.toString()) + " "
                + (description == null ? "" : description.toString());
    }

    /**
     * Returns a mode fingerprint only when exactly one recognized transit mode
     * node is selected and no recognized car mode node is selected. Naver may
     * expose no selected state; that intentionally produces a manual fallback.
     */
    private String findSelectedRouteMode(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int selectedTransitCount = 0;
        int selectedCarCount = 0;
        String selectedTransitLabel = "";
        String selectedCarLabel = "";
        int visited = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            visited++;
            AccessibilityNodeInfo node = pending.removeFirst();
            if (node.isVisibleToUser() && (node.isSelected() || node.isChecked())) {
                String transitLabel = matchingLabel(node, TRANSIT_MODE_LABELS);
                String carLabel = matchingLabel(node, CAR_MODE_LABELS);
                if (!transitLabel.isEmpty()) {
                    selectedTransitCount++;
                    selectedTransitLabel = transitLabel;
                }
                if (!carLabel.isEmpty()) {
                    selectedCarCount++;
                    selectedCarLabel = carLabel;
                }
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (visited + pending.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        truncated |= !pending.isEmpty();
        while (!pending.isEmpty()) pending.removeFirst().recycle();
        if (truncated) return "mode_unknown";
        if (selectedTransitCount > 0 && selectedCarCount > 0) return "mode_mixed";
        if (selectedCarCount > 0) return "car:" + normalize(selectedCarLabel);
        if (selectedTransitCount == 1) return "transit:" + normalize(selectedTransitLabel);
        if (selectedTransitCount > 1) return "mode_ambiguous";
        return "mode_unknown";
    }

    private String matchingLabel(AccessibilityNodeInfo node, Set<String> labels) {
        CharSequence text = node.getText();
        String normalizedText = text == null ? "" : normalize(text.toString());
        if (labels.contains(normalizedText)) return normalizedText;
        CharSequence description = node.getContentDescription();
        String normalizedDescription = description == null ? "" : normalize(description.toString());
        return labels.contains(normalizedDescription) ? normalizedDescription : "";
    }

    private boolean eventMatchesLabel(AccessibilityEvent event, Set<String> labels) {
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            try {
                if (matches(source, labels)) return true;
            } finally {
                source.recycle();
            }
        }
        for (CharSequence text : event.getText()) {
            if (text != null && labels.contains(normalize(text.toString()))) return true;
        }
        return false;
    }

    private boolean observeStableScreen(String signature) {
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastVerifiedAtElapsedRealtime;
        if (!signature.equals(lastVerifiedSignature)
                || stableScreenStartedAtElapsedRealtime == 0L
                || elapsed < 0L || elapsed > 3_000L) {
            stableScreenEvents = 1;
            stableScreenStartedAtElapsedRealtime = now;
        } else {
            // Keep the first-seen time across fast accessibility events. Resetting on
            // every sub-250ms event starves screens that report content changes rapidly.
            stableScreenEvents++;
        }
        lastVerifiedSignature = signature;
        lastVerifiedAtElapsedRealtime = now;
        return stableScreenEvents >= 2
                && now - stableScreenStartedAtElapsedRealtime >= 250L;
    }

    private boolean observeStableActiveGuidance(String signature) {
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastActiveGuidanceAtElapsedRealtime;
        if (!signature.equals(lastActiveGuidanceSignature)
                || stableActiveGuidanceStartedAtElapsedRealtime == 0L
                || elapsed < 0L || elapsed > 3_000L) {
            stableActiveGuidanceEvents = 1;
            stableActiveGuidanceStartedAtElapsedRealtime = now;
        } else {
            stableActiveGuidanceEvents++;
        }
        lastActiveGuidanceSignature = signature;
        lastActiveGuidanceAtElapsedRealtime = now;
        return stableActiveGuidanceEvents >= 2
                && now - stableActiveGuidanceStartedAtElapsedRealtime >= 250L;
    }

    private void resetForNewArmGeneration() {
        long generation = NaverGuideAutomation.armGeneration(this);
        if (generation != observedArmGeneration) {
            observedArmGeneration = generation;
            armBaselineSignature = "";
            armBaselineCaptured = false;
            resetStableScreen();
            resetStableActiveGuidance();
        }
    }

    private void resetStableScreen() {
        lastVerifiedSignature = "";
        lastVerifiedAtElapsedRealtime = 0L;
        stableScreenStartedAtElapsedRealtime = 0L;
        stableScreenEvents = 0;
    }

    private void resetStableActiveGuidance() {
        lastActiveGuidanceSignature = "";
        lastActiveGuidanceAtElapsedRealtime = 0L;
        stableActiveGuidanceStartedAtElapsedRealtime = 0L;
        stableActiveGuidanceEvents = 0;
    }

    private boolean isTargetRoot(AccessibilityNodeInfo root) {
        CharSequence packageName = root.getPackageName();
        return packageName != null
                && NaverGuideAutomation.TARGET_PACKAGE.contentEquals(packageName);
    }

    /** Returns true on a match or an incomplete tree, so ambiguity fails closed. */
    private boolean containsLabelOrUnknown(AccessibilityNodeInfo root, Set<String> labels) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            visited++;
            AccessibilityNodeInfo node = pending.removeFirst();
            if (matches(node, labels)) {
                node.recycle();
                while (!pending.isEmpty()) pending.removeFirst().recycle();
                return true;
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (visited + pending.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        truncated |= !pending.isEmpty();
        while (!pending.isEmpty()) pending.removeFirst().recycle();
        return truncated;
    }

    private String routeScreenSignature(String verifiedDestinationLabel,
                                        AccessibilityNodeInfo start,
                                        AccessibilityNodeInfo actionTarget,
                                        String modeState) {
        return (verifiedDestinationLabel == null ? "destination_unverified"
                : "destination_verified:" + normalize(verifiedDestinationLabel))
                + "|" + (start == null ? "start_unavailable" : matchingLabel(start, START_LABELS))
                + "|control:" + nodeIdentity(actionTarget)
                + "|" + modeState;
    }

    private String nodeIdentity(AccessibilityNodeInfo node) {
        if (node == null) return "unavailable";
        StringBuilder identity = new StringBuilder();
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        for (int depth = 0; current != null
                && depth <= NaverGuideClickTargetPolicy.MAX_CLICKABLE_ANCESTOR_DEPTH; depth++) {
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            CharSequence viewId = current.getViewIdResourceName();
            CharSequence className = current.getClassName();
            if (depth > 0) identity.append("^");
            identity.append(normalize(viewId == null ? "" : viewId.toString()))
                    .append('|').append(normalize(className == null ? "" : className.toString()))
                    .append('|').append(bounds.left).append(',').append(bounds.top)
                    .append(',').append(bounds.right).append(',').append(bounds.bottom)
                    .append('|').append(current.isEnabled()).append('|').append(current.isClickable());
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return identity.toString();
    }

    private boolean matches(AccessibilityNodeInfo node, Set<String> labels) {
        if (!node.isVisibleToUser()) return false;
        CharSequence text = node.getText();
        if (text != null && labels.contains(normalize(text.toString()))) return true;
        CharSequence description = node.getContentDescription();
        return description != null && labels.contains(normalize(description.toString()));
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.KOREAN).replaceAll("\\s+", " ");
    }

    private AccessibilityNodeInfo findSafeClickableTarget(AccessibilityNodeInfo root,
                                                          AccessibilityNodeInfo labelNode) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(labelNode);
        AccessibilityNodeInfo target = null;
        int clickableNodesOnPath = 0;
        for (int depth = 0; current != null
                && depth <= NaverGuideClickTargetPolicy.MAX_CLICKABLE_ANCESTOR_DEPTH; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                clickableNodesOnPath++;
                if (target == null) target = AccessibilityNodeInfo.obtain(current);
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) {
            current.recycle();
        }
        if (target == null) return null;

        Rect targetBounds = new Rect();
        Rect labelBounds = new Rect();
        Rect viewportBounds = new Rect();
        target.getBoundsInScreen(targetBounds);
        labelNode.getBoundsInScreen(labelBounds);
        root.getBoundsInScreen(viewportBounds);
        boolean hasCompetingClickable = hasCompetingClickableDescendant(target);
        boolean safe = NaverGuideClickTargetPolicy.isSafe(clickableNodesOnPath,
                hasCompetingClickable,
                targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom,
                labelBounds.left, labelBounds.top, labelBounds.right, labelBounds.bottom,
                viewportBounds.left, viewportBounds.top, viewportBounds.right, viewportBounds.bottom);
        if (!safe) {
            target.recycle();
            return null;
        }
        return target;
    }

    /** Unknown or competing interactive descendants make the parent scope unsafe. */
    private boolean hasCompetingClickableDescendant(AccessibilityNodeInfo target) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        int childCount = target.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = target.getChild(i);
            if (child != null) pending.addLast(child);
        }
        int visited = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            visited++;
            AccessibilityNodeInfo node = pending.removeFirst();
            boolean isTargetLabel = matches(node, START_LABELS);
            if (!isTargetLabel && node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                node.recycle();
                while (!pending.isEmpty()) pending.removeFirst().recycle();
                return true;
            }
            int descendants = node.getChildCount();
            for (int i = 0; i < descendants; i++) {
                if (visited + pending.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        truncated |= !pending.isEmpty();
        while (!pending.isEmpty()) pending.removeFirst().recycle();
        return truncated;
    }
}
