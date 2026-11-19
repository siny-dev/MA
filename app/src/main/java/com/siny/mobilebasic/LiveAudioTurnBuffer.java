package com.siny.mobilebasic;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Keeps push-to-talk audio and turn boundaries ordered while Gemini Live finishes setup. */
final class LiveAudioTurnBuffer {
    enum Kind { ACTIVITY_START, AUDIO, ACTIVITY_END }

    static final class Frame {
        final Kind kind;
        final byte[] pcm;
        final long userTurn;

        Frame(Kind kind, byte[] pcm, long userTurn) {
            this.kind = kind;
            this.pcm = pcm;
            this.userTurn = userTurn;
        }
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final int maxBytes;
    private int pendingBytes;

    LiveAudioTurnBuffer(int maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    void offerActivityStart(long userTurn) {
        frames.addLast(new Frame(Kind.ACTIVITY_START, new byte[0], userTurn));
    }

    boolean offerAudio(byte[] pcm, long userTurn) {
        if (pcm == null || pcm.length == 0) return false;
        if (pendingBytes + pcm.length > maxBytes) return false;
        frames.addLast(new Frame(Kind.AUDIO, pcm, userTurn));
        pendingBytes += pcm.length;
        return true;
    }

    void offerActivityEnd(long userTurn) {
        frames.addLast(new Frame(Kind.ACTIVITY_END, new byte[0], userTurn));
    }

    Frame poll() {
        Frame frame = frames.pollFirst();
        if (frame != null && frame.kind == Kind.AUDIO) pendingBytes -= frame.pcm.length;
        return frame;
    }

    void discardTurn(long userTurn) {
        Iterator<Frame> iterator = frames.iterator();
        while (iterator.hasNext()) {
            Frame frame = iterator.next();
            if (frame.userTurn != userTurn) continue;
            if (frame.kind == Kind.AUDIO) pendingBytes -= frame.pcm.length;
            iterator.remove();
        }
    }

    int pendingBytes() { return pendingBytes; }

    boolean isEmpty() { return frames.isEmpty(); }

    void clear() {
        frames.clear();
        pendingBytes = 0;
    }
}
