package com.siny.mobilebasic;

/** Prevents overlapping capture while still admitting deterministic push-to-talk barge-in. */
final class LiveInputTurnAdmission {
    enum Decision { ACCEPTED, CLOSED, TOOL_CALL_PENDING, SETUP_TURN_PENDING }

    private LiveInputTurnAdmission() { }

    static Decision decide(boolean closed, boolean toolCallPending,
                           boolean setupPending, boolean bufferedTurnPending) {
        if (closed) return Decision.CLOSED;
        if (toolCallPending) return Decision.TOOL_CALL_PENDING;
        if (setupPending && bufferedTurnPending) return Decision.SETUP_TURN_PENDING;
        return Decision.ACCEPTED;
    }
}
