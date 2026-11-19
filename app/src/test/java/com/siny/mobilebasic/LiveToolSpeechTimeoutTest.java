package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LiveToolSpeechTimeoutTest {
    @Test public void usesMinimumForShortPrompts() {
        assertEquals(8_000L, LiveToolSpeechTimeout.forSpeech("네."));
    }

    @Test public void increasesTimeoutForLongAddressPrompts() {
        assertEquals(14_000L, LiveToolSpeechTimeout.forSpeech("가".repeat(100)));
    }

    @Test public void capsVeryLongSpeech() {
        assertEquals(20_000L, LiveToolSpeechTimeout.forSpeech("가".repeat(500)));
    }
}
