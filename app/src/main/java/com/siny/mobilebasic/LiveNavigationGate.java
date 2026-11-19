package com.siny.mobilebasic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** App-owned consent gate for candidates returned by NAVER Local Search. */
final class LiveNavigationGate {
    enum Decision { ALLOWED, UNKNOWN_CANDIDATE, NOT_SELECTED, NOT_PRESENTED, NEEDS_NEW_USER_TURN, ALREADY_USED }

    private static final class Entry {
        final String fingerprint;
        boolean selected;
        boolean rejected;
        boolean consumed;
        long presentedAtTurn = -1L;
        Entry(String fingerprint) { this.fingerprint = fingerprint; }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> rejectedPlaces = new HashSet<>();
    private long userTurn;
    private String selectedId = "";

    long beginUserTurn() { return ++userTurn; }
    long currentUserTurn() { return userTurn; }

    boolean register(String id, String name, String address) {
        if (blank(id) || blank(name) && blank(address)) return false;
        String fingerprint = fingerprint(name, address);
        if (rejectedPlaces.contains(fingerprint)) return false;
        entries.put(id, new Entry(fingerprint));
        return true;
    }

    boolean select(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.rejected || entry.consumed) return false;
        if (!selectedId.isEmpty() && !selectedId.equals(id)) {
            Entry previous = entries.get(selectedId);
            if (previous != null) previous.selected = false;
        }
        entry.selected = true;
        entry.presentedAtTurn = -1L;
        selectedId = id;
        return true;
    }

    boolean markPresented(String id, long assistantTurn) {
        Entry entry = entries.get(id);
        if (entry == null || entry.rejected || !entry.selected || assistantTurn < 0L) return false;
        entry.presentedAtTurn = assistantTurn;
        return true;
    }

    boolean reject(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.consumed) return false;
        entry.rejected = true;
        rejectedPlaces.add(entry.fingerprint);
        if (id.equals(selectedId)) selectedId = "";
        return true;
    }

    Decision consumeForNavigation(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.rejected) return Decision.UNKNOWN_CANDIDATE;
        if (entry.consumed) return Decision.ALREADY_USED;
        if (!entry.selected || !id.equals(selectedId)) return Decision.NOT_SELECTED;
        if (entry.presentedAtTurn < 0L) return Decision.NOT_PRESENTED;
        if (userTurn <= entry.presentedAtTurn) return Decision.NEEDS_NEW_USER_TURN;
        entry.consumed = true;
        selectedId = "";
        return Decision.ALLOWED;
    }

    boolean wasRejected(String name, String address) {
        return rejectedPlaces.contains(fingerprint(name, address));
    }

    void clearActive() {
        entries.clear();
        selectedId = "";
    }

    /** Starts a new conversation without carrying a previous conversation's rejection history. */
    void reset() {
        clearActive();
        rejectedPlaces.clear();
        userTurn = 0L;
    }

    private static String fingerprint(String name, String address) {
        return normalize(name) + "|" + normalize(address);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
