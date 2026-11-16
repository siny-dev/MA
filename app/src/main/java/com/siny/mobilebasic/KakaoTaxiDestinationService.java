package com.siny.mobilebasic;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Handles confirmed Kakao T destination entry and one explicit, narrowly matched call action. */
public final class KakaoTaxiDestinationService extends AccessibilityService {
    private static final int MAX_NODES = 600;
    private static final Set<String> DESTINATION_LABELS = buildDestinationLabels();

    private static Set<String> buildDestinationLabels() {
        Set<String> labels = new HashSet<>();
        for (String label : Arrays.asList(
            "어디로 갈까요", "어디로 모실까요", "어디로 가시나요", "목적지", "목적지 검색",
            "목적지를 선택해 주세요", "목적지를 입력해 주세요",
            "목적지를 검색해 주세요", "도착지", "도착지 검색", "도착지를 입력해 주세요",
            "도착지를 검색해 주세요", "도착지를 설정해 주세요", "도착 장소", "도착 장소 검색",
            "장소를 검색해 주세요", "검색할 장소를 입력하세요")) labels.add(normalize(label));
        return labels;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !KakaoTaxiAutomation.TARGET_PACKAGE.contentEquals(event.getPackageName())
                || !KakaoTaxiAutomation.isArmed(this)) return;

        String destination = KakaoTaxiAutomation.address(this);
        if (destination.isEmpty()) {
            KakaoTaxiAutomation.finish(this, "empty_destination");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (KakaoTaxiAutomation.STAGE_ENTER_DESTINATION.equals(KakaoTaxiAutomation.stage(this))) {
                AccessibilityNodeInfo input = findDestinationInput(root);
                if (input == null) return;
                try {
                    if (input.isEditable()) {
                        if (!input.isFocused()) input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        Bundle arguments = new Bundle();
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                destination);
                        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                            KakaoTaxiAutomation.markTextEntered(this);
                        }
                    } else if (input.isEnabled() && input.isClickable()) {
                        input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                } finally {
                    input.recycle();
                }
                return;
            }

            if (KakaoTaxiAutomation.STAGE_REQUEST_TAXI.equals(KakaoTaxiAutomation.stage(this))) {
                AccessibilityNodeInfo callButton = findUniqueTaxiCallButton(root,
                        KakaoTaxiAutomation.name(this), destination);
                if (callButton == null) return;
                try {
                    if (callButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        KakaoTaxiAutomation.finish(this, "taxi_request_tapped");
                    }
                } finally {
                    callButton.recycle();
                }
                return;
            }

            AccessibilityNodeInfo suggestion = findDestinationSuggestion(root,
                    KakaoTaxiAutomation.name(this), destination);
            if (suggestion != null) {
                try {
                    if (suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        KakaoTaxiAutomation.markDestinationSelected(this);
                    }
                } finally {
                    suggestion.recycle();
                }
            }
        } finally {
            root.recycle();
        }
    }

    @Override public void onInterrupt() {
        if (KakaoTaxiAutomation.isArmed(this)) KakaoTaxiAutomation.finish(this, "interrupted");
    }

    private AccessibilityNodeInfo findDestinationInput(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (hasDestinationLabel(node)) {
                if (node.isVisibleToUser() && (node.isEditable() || node.isClickable())) {
                    recycleAll(pending);
                    return node;
                }
                AccessibilityNodeInfo clickableParent = clickableParent(node);
                if (clickableParent != null) {
                    node.recycle();
                    recycleAll(pending);
                    return clickableParent;
                }
            }
            for (int i = 0; i < node.getChildCount() && visited + pending.size() < MAX_NODES; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        recycleAll(pending);
        return null;
    }

    private AccessibilityNodeInfo findDestinationSuggestion(AccessibilityNodeInfo root,
                                                             String expectedName, String expectedAddress) {
        String name = normalize(expectedName == null ? "" : expectedName);
        String address = normalize(expectedAddress == null ? "" : expectedAddress);
        if (name.length() < 2 || address.length() < 4) return null;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (node.isVisibleToUser() && node.isEnabled() && node.isClickable() && !node.isEditable()) {
                String subtreeText = textInSubtree(node);
                if (KakaoTaxiScreenMatcher.destinationMatches(
                        subtreeText, expectedName, expectedAddress)) {
                    recycleAll(pending);
                    return node;
                }
            }
            for (int i = 0; i < node.getChildCount() && visited + pending.size() < MAX_NODES; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        recycleAll(pending);
        return null;
    }

    private AccessibilityNodeInfo findUniqueTaxiCallButton(AccessibilityNodeInfo root,
                                                            String expectedName, String expectedAddress) {
        String visibleScreen = textInSubtree(root);
        if (!KakaoTaxiScreenMatcher.destinationMatches(visibleScreen, expectedName, expectedAddress)) return null;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        ArrayDeque<AccessibilityNodeInfo> matches = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = pending.removeFirst();
            String label = nodeLabel(node);
            boolean matchesCall = node.isVisibleToUser() && node.isEnabled()
                    && node.isClickable() && !node.isEditable()
                    && KakaoTaxiScreenMatcher.isCallButtonLabel(label);
            for (int i = 0; i < node.getChildCount() && visited + pending.size() < MAX_NODES; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            if (matchesCall) matches.addLast(node);
            else node.recycle();
        }
        recycleAll(pending);
        if (!KakaoTaxiScreenMatcher.shouldTapCall(visibleScreen, expectedName,
                expectedAddress, matches.size())) {
            recycleAll(matches);
            return null;
        }
        return matches.removeFirst();
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return normalize(text.toString());
        CharSequence description = node.getContentDescription();
        if (description != null && description.length() > 0) return normalize(description.toString());
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence hint = node.getHintText();
            if (hint != null) return normalize(hint.toString());
        }
        return "";
    }

    private String textInSubtree(AccessibilityNodeInfo root) {
        StringBuilder text = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!pending.isEmpty() && visited++ < 80) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (node.isVisibleToUser()) {
                appendText(text, node.getText());
                appendText(text, node.getContentDescription());
                if (Build.VERSION.SDK_INT >= 26) appendText(text, node.getHintText());
            }
            for (int i = 0; i < node.getChildCount() && visited + pending.size() < 80; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
            node.recycle();
        }
        recycleAll(pending);
        return normalize(text.toString());
    }

    private void appendText(StringBuilder target, CharSequence value) {
        if (value == null || value.length() == 0) return;
        if (target.length() > 0) target.append(' ');
        target.append(value);
    }

    private AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return null;
    }

    private boolean hasDestinationLabel(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence hint = node.getHintText();
            if (hint != null && DESTINATION_LABELS.contains(normalize(hint.toString()))) return true;
        }
        CharSequence text = node.getText();
        if (text != null && DESTINATION_LABELS.contains(normalize(text.toString()))) return true;
        CharSequence description = node.getContentDescription();
        return description != null && DESTINATION_LABELS.contains(normalize(description.toString()));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.KOREAN).replaceAll("[\\p{P}\\s]", "");
    }

    private void recycleAll(ArrayDeque<AccessibilityNodeInfo> nodes) {
        while (!nodes.isEmpty()) nodes.removeFirst().recycle();
    }
}
