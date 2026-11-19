package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveAudioTurnBufferTest {
    @Test public void preservesAudioAndPushToTalkTurnBoundariesAcrossDelayedSetup() {
        LiveAudioTurnBuffer buffer = new LiveAudioTurnBuffer(16);
        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{4, 5};
        byte[] nextTurn = new byte[]{6};

        buffer.offerActivityStart(10L);
        assertTrue(buffer.offerAudio(first, 10L));
        assertTrue(buffer.offerAudio(second, 10L));
        buffer.offerActivityEnd(10L);
        buffer.offerActivityStart(11L);
        assertTrue(buffer.offerAudio(nextTurn, 11L));
        buffer.offerActivityEnd(11L);

        LiveAudioTurnBuffer.Frame firstFrame = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, firstFrame.kind);
        assertEquals(10L, firstFrame.userTurn);
        firstFrame = buffer.poll();
        assertArrayEquals(first, firstFrame.pcm);
        assertEquals(10L, firstFrame.userTurn);
        LiveAudioTurnBuffer.Frame secondFrame = buffer.poll();
        assertArrayEquals(second, secondFrame.pcm);
        assertEquals(10L, secondFrame.userTurn);
        LiveAudioTurnBuffer.Frame firstEnd = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, firstEnd.kind);
        assertEquals(10L, firstEnd.userTurn);
        LiveAudioTurnBuffer.Frame nextFrame = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, nextFrame.kind);
        nextFrame = buffer.poll();
        assertArrayEquals(nextTurn, nextFrame.pcm);
        assertEquals(11L, nextFrame.userTurn);
        LiveAudioTurnBuffer.Frame last = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, last.kind);
        assertEquals(11L, last.userTurn);
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.pendingBytes());
    }

    @Test public void refusesOverflowWithoutLosingEarlierAudioOrEndMarkers() {
        LiveAudioTurnBuffer buffer = new LiveAudioTurnBuffer(4);
        buffer.offerActivityStart(20L);
        assertTrue(buffer.offerAudio(new byte[]{1, 2, 3}, 20L));
        buffer.offerActivityEnd(20L);
        assertFalse(buffer.offerAudio(new byte[]{4, 5}, 21L));

        assertEquals(3, buffer.pendingBytes());
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, buffer.poll().kind);
        assertArrayEquals(new byte[]{1, 2, 3}, buffer.poll().pcm);
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, buffer.poll().kind);
        assertTrue(buffer.isEmpty());
    }

    @Test public void theFirstTurnStaysInOrderWhileGeminiSessionSetupCompletes() {
        LiveAudioTurnBuffer buffer = new LiveAudioTurnBuffer(16);
        byte[] userReply = new byte[]{9, 8, 7};
        buffer.offerActivityStart(42L);
        assertTrue(buffer.offerAudio(userReply, 42L));
        buffer.offerActivityEnd(42L);

        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, buffer.poll().kind);
        LiveAudioTurnBuffer.Frame audio = buffer.poll();
        LiveAudioTurnBuffer.Frame end = buffer.poll();
        assertArrayEquals(userReply, audio.pcm);
        assertEquals(42L, audio.userTurn);
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, end.kind);
        assertEquals(42L, end.userTurn);
        assertTrue(buffer.isEmpty());
    }

    @Test public void cancellingOneTouchTurnDoesNotDiscardEarlierBufferedTurns() {
        LiveAudioTurnBuffer buffer = new LiveAudioTurnBuffer(16);
        buffer.offerActivityStart(50L);
        assertTrue(buffer.offerAudio(new byte[]{1, 2}, 50L));
        buffer.offerActivityEnd(50L);
        buffer.offerActivityStart(51L);
        assertTrue(buffer.offerAudio(new byte[]{3, 4, 5}, 51L));
        buffer.offerActivityEnd(51L);

        buffer.discardTurn(51L);

        LiveAudioTurnBuffer.Frame audio = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, audio.kind);
        audio = buffer.poll();
        assertArrayEquals(new byte[]{1, 2}, audio.pcm);
        assertEquals(50L, audio.userTurn);
        LiveAudioTurnBuffer.Frame end = buffer.poll();
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, end.kind);
        assertEquals(50L, end.userTurn);
        assertEquals(0, buffer.pendingBytes());
        assertTrue(buffer.isEmpty());
    }
}
