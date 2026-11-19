package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.List;

/** Adds a short microphone-on pre-roll, then marks exactly one manual Live API activity per press. */
final class PttAudioTurnFramer {
    static final int PREROLL_MILLIS = 250;
    static final int PREROLL_BYTES = GeminiLiveProtocol.INPUT_SAMPLE_RATE * 2 * PREROLL_MILLIS / 1000;

    private final long userTurn;
    private final List<byte[]> preroll = new ArrayList<>();
    private int prerollBytes;
    private boolean started;
    private boolean finished;

    PttAudioTurnFramer(long userTurn) {
        this.userTurn = userTurn;
    }

    synchronized List<LiveAudioTurnBuffer.Frame> offer(byte[] pcm) {
        if (finished || pcm == null || pcm.length == 0) return new ArrayList<>();
        if (started) {
            List<LiveAudioTurnBuffer.Frame> result = new ArrayList<>(1);
            result.add(audio(pcm));
            return result;
        }
        preroll.add(pcm.clone());
        prerollBytes += pcm.length;
        return prerollBytes >= PREROLL_BYTES ? startWithPreroll() : new ArrayList<>();
    }

    synchronized List<LiveAudioTurnBuffer.Frame> finish() {
        if (finished) return new ArrayList<>();
        finished = true;
        List<LiveAudioTurnBuffer.Frame> result = new ArrayList<>();
        if (!started && !preroll.isEmpty()) appendPreroll(result);
        if (started || !result.isEmpty()) {
            result.add(new LiveAudioTurnBuffer.Frame(LiveAudioTurnBuffer.Kind.ACTIVITY_END,
                    new byte[0], userTurn));
        }
        preroll.clear();
        prerollBytes = 0;
        return result;
    }

    /** Drops audio not yet sent; returns a closing marker only if this turn had already opened. */
    synchronized List<LiveAudioTurnBuffer.Frame> cancel() {
        if (finished) return new ArrayList<>();
        finished = true;
        preroll.clear();
        prerollBytes = 0;
        List<LiveAudioTurnBuffer.Frame> result = new ArrayList<>();
        if (started) result.add(new LiveAudioTurnBuffer.Frame(LiveAudioTurnBuffer.Kind.ACTIVITY_END,
                new byte[0], userTurn));
        started = false;
        return result;
    }

    synchronized boolean isStarted() { return started; }
    long userTurn() { return userTurn; }

    private List<LiveAudioTurnBuffer.Frame> startWithPreroll() {
        List<LiveAudioTurnBuffer.Frame> result = new ArrayList<>();
        appendPreroll(result);
        return result;
    }

    private void appendPreroll(List<LiveAudioTurnBuffer.Frame> result) {
        if (preroll.isEmpty()) return;
        result.add(new LiveAudioTurnBuffer.Frame(LiveAudioTurnBuffer.Kind.ACTIVITY_START,
                new byte[0], userTurn));
        for (byte[] frame : preroll) result.add(audio(frame));
        preroll.clear();
        prerollBytes = 0;
        started = true;
    }

    private LiveAudioTurnBuffer.Frame audio(byte[] pcm) {
        return new LiveAudioTurnBuffer.Frame(LiveAudioTurnBuffer.Kind.AUDIO, pcm.clone(), userTurn);
    }
}
