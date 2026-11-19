package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.*;

public final class DestinationCandidateContextTest {
    @Test public void resultSetExpiresAtFixedBoundary() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        context.begin(1_000L);

        assertTrue(context.isCurrent(1_000L));
        assertTrue(context.isCurrent(1_000L + DestinationCandidateContext.MAX_AGE_MS - 1));
        assertFalse(context.isCurrent(1_000L + DestinationCandidateContext.MAX_AGE_MS));
        assertTrue(context.hasExpired(1_000L + DestinationCandidateContext.MAX_AGE_MS));
    }

    @Test public void rereadingOrShowingTheSameResultCannotExtendItsLease() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long firstGeneration = context.begin(4_000L);
        long originalExpiry = context.expiresAtElapsedRealtime();

        assertTrue(context.isCurrent(firstGeneration, 4_000L + 60_000L));
        assertEquals(originalExpiry, context.expiresAtElapsedRealtime());
        assertFalse(context.isCurrent(firstGeneration, originalExpiry));
    }

    @Test public void onlyANewSearchResultStartsANewGeneration() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long firstGeneration = context.begin(10_000L);
        long nextGeneration = context.begin(20_000L);

        assertTrue(nextGeneration > firstGeneration);
        assertFalse(context.isCurrent(firstGeneration, 20_000L));
        assertTrue(context.isCurrent(nextGeneration, 20_000L));
        assertEquals(20_000L + DestinationCandidateContext.MAX_AGE_MS,
                context.expiresAtElapsedRealtime());
    }

    @Test public void nearExpiredResultCannotStartAnUnanswerableConfirmation() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long startedAt = 2_000L;
        context.begin(startedAt);
        long minimumConfirmationWindow = DestinationCandidateContext.MIN_CONFIRMATION_REMAINING_MS;
        long beforeCutoff = startedAt + DestinationCandidateContext.MAX_AGE_MS
                - minimumConfirmationWindow;

        assertTrue(context.hasMinimumRemaining(beforeCutoff, minimumConfirmationWindow));
        assertFalse(context.hasMinimumRemaining(beforeCutoff + 1, minimumConfirmationWindow));
        assertFalse(context.hasMinimumRemaining(
                startedAt + DestinationCandidateContext.MAX_AGE_MS, minimumConfirmationWindow));
    }

    @Test public void initialPresentationKeepsRenderBufferBeyondTheAnswerWindow() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        context.begin(1_000L);
        long atMinimum = context.expiresAtElapsedRealtime()
                - DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS;
        long belowMinimum = atMinimum + 1L;

        assertTrue(context.hasMinimumRemaining(atMinimum,
                DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS));
        assertFalse(context.hasMinimumRemaining(belowMinimum,
                DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS));
        assertTrue(DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS
                >= DestinationCandidateContext.MIN_CONFIRMATION_REMAINING_MS);
    }

    @Test public void staleGenerationCannotUseAReplacementResultsLease() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long oldGeneration = context.begin(100L);
        long newGeneration = context.begin(200L);

        assertFalse(context.hasMinimumRemaining(oldGeneration, 201L, 5_000L));
        assertTrue(context.hasMinimumRemaining(newGeneration, 201L, 5_000L));
    }

    @Test public void candidateListNeedsTimeForReadingSelectionAndConfirmation() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long generation = context.begin(7_000L);
        long atMinimum = context.expiresAtElapsedRealtime()
                - DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS;

        assertTrue(context.hasMinimumRemaining(generation, atMinimum,
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS));
        assertFalse(context.hasMinimumRemaining(generation, atMinimum + 1L,
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS));
        assertTrue(DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS
                >= DestinationCandidateContext.MAX_CANDIDATE_LIST_SPEECH_MS
                + DestinationCandidateContext.MIN_CONFIRMATION_REMAINING_MS
                + DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS);
    }

    @Test public void oldCandidateListCannotBorrowReplacementGenerationTime() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long oldGeneration = context.begin(1_000L);
        long newGeneration = context.begin(2_000L);

        assertFalse(context.hasMinimumRemaining(oldGeneration, 2_001L,
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS));
        assertTrue(context.hasMinimumRemaining(newGeneration, 2_001L,
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS));
    }

    @Test public void selectingFromAnAlreadyDisplayedListUsesConfirmationBudget() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long generation = context.begin(0L);
        long selectionAfterLongNarration = context.expiresAtElapsedRealtime()
                - DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS
                + DestinationCandidateContext.MAX_CANDIDATE_LIST_SPEECH_MS;

        assertFalse(context.hasMinimumRemaining(generation, selectionAfterLongNarration,
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS));
        assertTrue(context.hasMinimumRemaining(generation, selectionAfterLongNarration,
                DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS));

        long exactConfirmationCutoff = context.expiresAtElapsedRealtime()
                - DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS;
        assertTrue(context.hasMinimumRemaining(generation, exactConfirmationCutoff,
                DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS));
        assertFalse(context.hasMinimumRemaining(generation, exactConfirmationCutoff + 1L,
                DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS));
    }

    @Test public void handoffRequiresMinimumRemainingTimeAtTheLastGate() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long generation = context.begin(0L);
        long cutoff = context.expiresAtElapsedRealtime() - 5_000L;

        assertTrue(context.hasMinimumRemaining(generation, cutoff, 5_000L));
        assertFalse(context.hasMinimumRemaining(generation, cutoff + 1, 5_000L));
        assertFalse(context.hasMinimumRemaining(generation, context.expiresAtElapsedRealtime(), 0L));
    }

    @Test public void delayedUiDeliveryDoesNotRestartTheResultsLease() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long responseReceivedAt = 50_000L;
        context.begin(responseReceivedAt);
        long uiHandledAt = responseReceivedAt + DestinationCandidateContext.MAX_AGE_MS - 29_999L;

        assertFalse(context.hasMinimumRemaining(uiHandledAt, 30_000L));
        assertEquals(responseReceivedAt + DestinationCandidateContext.MAX_AGE_MS,
                context.expiresAtElapsedRealtime());
    }

    @Test public void clearInvalidatesDelayedSelections() {
        DestinationCandidateContext context = new DestinationCandidateContext();
        long generation = context.begin(1L);
        context.clear();

        assertFalse(context.isCurrent(generation, 2L));
        assertFalse(context.hasLease());
    }
}
