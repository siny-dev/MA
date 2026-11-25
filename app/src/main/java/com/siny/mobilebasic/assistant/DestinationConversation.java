package com.siny.mobilebasic.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Deterministic confirmation rules: a rejected candidate is never asked again. */
public final class DestinationConversation {
    private static final long CONFIRMATION_TTL_NANOS = 120_000_000_000L;
    public enum State { IDLE, SEARCHING, AWAITING_CONFIRMATION, AWAITING_SELECTION, HANDED_OFF }
    public enum Reply { YES, NO, CORRECTION, UNCLEAR, ABORTED }

    private State state = State.IDLE;
    private PlaceCandidate candidate;
    private List<PlaceCandidate> choices = Collections.emptyList();
    private int unclearConfirmationCount;
    private final LongSupplier monotonicClock;
    private long confirmationStartedNanos;

    public DestinationConversation() {
        this(System::nanoTime);
    }

    DestinationConversation(LongSupplier monotonicClock) {
        this.monotonicClock = monotonicClock;
    }

    public synchronized State state() { return state; }
    public synchronized PlaceCandidate candidate() { return candidate; }
    public synchronized List<PlaceCandidate> choices() { return new ArrayList<>(choices); }

    public synchronized void beginSearch() {
        candidate = null;
        choices = Collections.emptyList();
        unclearConfirmationCount = 0;
        state = State.SEARCHING;
    }

    public synchronized String confirm(PlaceCandidate selected) {
        if (selected == null) throw new IllegalArgumentException("A verified place is required");
        candidate = selected;
        choices = Collections.emptyList();
        unclearConfirmationCount = 0;
        confirmationStartedNanos = monotonicClock.getAsLong();
        state = State.AWAITING_CONFIRMATION;
        return selected.confirmationText();
    }

    public synchronized List<String> offerChoices(List<PlaceCandidate> items) {
        if (items == null || items.size() < 2) throw new IllegalArgumentException("At least two places are required");
        choices = new ArrayList<>(items.subList(0, Math.min(items.size(), 3)));
        candidate = null;
        confirmationStartedNanos = monotonicClock.getAsLong();
        state = State.AWAITING_SELECTION;
        List<String> prompts = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            PlaceCandidate item = choices.get(i);
            String area = item.spokenArea();
            prompts.add((i + 1) + "번, " + item.name + (area.isEmpty() ? "" : ", " + area));
        }
        return prompts;
    }

    public synchronized List<String> choiceDetails() {
        List<String> details = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            PlaceCandidate item = choices.get(i);
            details.add((i + 1) + ". " + item.name + "\n주소: " + item.displayAddress());
        }
        return details;
    }

    public synchronized String selectByVoice(String spoken) {
        if (state != State.AWAITING_SELECTION) return null;
        int index = selectionIndex(spoken);
        if (index < 0 || index >= choices.size()) return null;
        PlaceCandidate selected = choices.get(index);
        return confirm(selected);
    }

    public synchronized Reply replyToConfirmation(String spoken) {
        if (state != State.AWAITING_CONFIRMATION) return Reply.UNCLEAR;
        String value = normalize(spoken);
        if (value.isEmpty()) return unclear();
        if (isExactPositive(value)) return Reply.YES;
        if (isExactNegative(value)) {
            clearToIdle();
            return Reply.NO;
        }
        // A correction such as “아니, 피부과 말고 약국” starts a fresh search.
        if (hasCorrection(value)) {
            clearToIdle();
            return Reply.CORRECTION;
        }
        return unclear();
    }

    /** Applies a constrained intent classification; the assistant never receives the candidate coordinates. */
    public synchronized Reply applyAssistantConfirmation(String intent) {
        if (state != State.AWAITING_CONFIRMATION) return Reply.UNCLEAR;
        String value = intent == null ? "" : intent.trim().toLowerCase(Locale.ROOT);
        if (value.equals("yes")) {
            unclearConfirmationCount = 0;
            return Reply.YES;
        }
        if (value.equals("no")) {
            clearToIdle();
            return Reply.NO;
        }
        if (value.equals("correction")) {
            clearToIdle();
            return Reply.CORRECTION;
        }
        return Reply.UNCLEAR;
    }

    public synchronized void markHandedOff() {
        if (state != State.AWAITING_CONFIRMATION || candidate == null)
            throw new IllegalStateException("Only the confirmed place can be handed off");
        state = State.HANDED_OFF;
    }

    public synchronized void reset() { clearToIdle(); }

    /** Clears an old confirmation/selection so a late reply cannot open a stale destination. */
    public synchronized boolean expirePendingDestinationIfStale() {
        if (state != State.AWAITING_CONFIRMATION && state != State.AWAITING_SELECTION) return false;
        if (monotonicClock.getAsLong() - confirmationStartedNanos < CONFIRMATION_TTL_NANOS) return false;
        clearToIdle();
        return true;
    }

    private Reply unclear() {
        unclearConfirmationCount++;
        if (unclearConfirmationCount >= 2) {
            // Do not trap an older user in repeated yes/no prompts or treat hesitation as a new search.
            clearToIdle();
            return Reply.ABORTED;
        }
        return Reply.UNCLEAR;
    }

    private void clearToIdle() {
        state = State.IDLE;
        candidate = null;
        choices = Collections.emptyList();
        unclearConfirmationCount = 0;
        confirmationStartedNanos = 0L;
    }

    private static boolean isExactPositive(String value) {
        if (value.matches("(네|예|응|어|맞아|맞아요|맞습니다|맞는것같아|맞는것같아요|그거맞아|그거맞아요|그래|그래요|그렇지|그곳맞아|그곳맞아요|맞아그곳|네맞아|네맞아요|예맞아요|그래맞아)+")) return true;
        return value.matches("(네|예|응|어|맞아|맞아요|맞습니다|그래|그래요|그렇지)(그럼|그러면)?(그렇게)?(해주세요|해줘요|해줘|부탁해요|부탁해|안내해주세요|가주세요|좋아요|좋습니다|그러세요)");
    }

    private static boolean isExactNegative(String value) {
        return value.matches("(아니|아니요|아니오|아니에요|아닙니다|아니야|그게아니야|그게아니에요|그건아니야|그거아니야|틀려|틀렸어|틀렸어요|다시)+");
    }

    private static boolean hasCorrection(String value) {
        return value.contains("말고") || value.contains("다른") || value.contains("아니")
                || value.contains("틀렸") || value.contains("틀린") || value.contains("잘못")
                || value.contains("아니야") || value.contains("아닙");
    }

    private static int selectionIndex(String spoken) {
        String value = normalize(spoken);
        if (value.matches("(1|1번|첫번째|첫째|첫번째곳|첫번째거)")) return 0;
        if (value.matches("(2|2번|두번째|둘째|두번째곳|두번째거)")) return 1;
        if (value.matches("(3|3번|세번째|셋째|세번째곳|세번째거)")) return 2;
        return -1;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\s.,!?~…。、·]", "");
    }
}
