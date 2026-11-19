package com.siny.mobilebasic;

/** Rejects queued responses from a search that was superseded before its UI callback ran. */
final class SearchGenerationGuard {
    private SearchGenerationGuard() {}

    static boolean isCurrent(long requestId, long activeRequestId) {
        return requestId == activeRequestId;
    }
}
