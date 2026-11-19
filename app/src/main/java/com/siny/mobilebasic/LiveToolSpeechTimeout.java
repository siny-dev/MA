package com.siny.mobilebasic;

/** Bounds how long a local factual TTS utterance may hold a Gemini tool call open. */
final class LiveToolSpeechTimeout {
    private static final long MIN_TIMEOUT_MS = 8_000L;
    private static final long MAX_TIMEOUT_MS = 20_000L;
    private static final long BASE_MARGIN_MS = 3_000L;
    private static final long PER_CHARACTER_MS = 110L;

    private LiveToolSpeechTimeout() { }

    static long forSpeech(String speech) {
        int length = speech == null ? 0 : speech.length();
        long estimate = BASE_MARGIN_MS + (long) length * PER_CHARACTER_MS;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, estimate));
    }
}
