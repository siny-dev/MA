package com.siny.mobilebasic;

import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PttAudioTurnFramerTest {
    @Test public void oneLongHoldWithLongPausesHasOneStartAndOnlyReleaseEndsTheTurn() {
        PttAudioTurnFramer turn = new PttAudioTurnFramer(7L);
        byte[] first = new byte[PttAudioTurnFramer.PREROLL_BYTES / 2];
        byte[] pause = new byte[PttAudioTurnFramer.PREROLL_BYTES / 2];
        byte[] last = new byte[]{9, 8};

        List<LiveAudioTurnBuffer.Frame> pending = turn.offer(first);
        assertTrue(pending.isEmpty());
        pending = turn.offer(pause);
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, pending.get(0).kind);
        assertArrayEquals(first, pending.get(1).pcm);
        assertArrayEquals(pause, pending.get(2).pcm);
        assertTrue(turn.isStarted());

        List<LiveAudioTurnBuffer.Frame> longPauseFrames = new ArrayList<>();
        byte[] silence = new byte[2048];
        for (int i = 0; i < 80; i++) longPauseFrames.addAll(turn.offer(silence));
        assertEquals(80, longPauseFrames.size());
        for (LiveAudioTurnBuffer.Frame frame : longPauseFrames) {
            assertEquals(LiveAudioTurnBuffer.Kind.AUDIO, frame.kind);
        }

        List<LiveAudioTurnBuffer.Frame> speechAfterLongPause = turn.offer(last);
        assertEquals(1, speechAfterLongPause.size());
        assertEquals(LiveAudioTurnBuffer.Kind.AUDIO, speechAfterLongPause.get(0).kind);

        List<LiveAudioTurnBuffer.Frame> ended = turn.finish();
        assertEquals(1, ended.size());
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, ended.get(0).kind);
        assertTrue(turn.finish().isEmpty());
    }

    @Test public void releaseBeforePrerollThresholdStillSendsTheWholeFirstSyllableBeforeEnd() {
        PttAudioTurnFramer turn = new PttAudioTurnFramer(8L);
        byte[] shortSpeech = new byte[]{1, 2, 3, 4};
        assertTrue(turn.offer(shortSpeech).isEmpty());

        List<LiveAudioTurnBuffer.Frame> ended = turn.finish();

        assertEquals(3, ended.size());
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START, ended.get(0).kind);
        assertArrayEquals(shortSpeech, ended.get(1).pcm);
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, ended.get(2).kind);
    }

    @Test public void cancelBeforeOpeningTurnDiscardsBufferedAudio() {
        PttAudioTurnFramer turn = new PttAudioTurnFramer(9L);
        assertTrue(turn.offer(new byte[]{1, 2, 3}).isEmpty());

        assertTrue(turn.cancel().isEmpty());
        assertTrue(turn.finish().isEmpty());
    }

    @Test public void cancelAfterStreamingClosesManualActivityExactlyOnce() {
        PttAudioTurnFramer turn = new PttAudioTurnFramer(10L);
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_START,
                turn.offer(new byte[PttAudioTurnFramer.PREROLL_BYTES])
                        .get(0).kind);

        List<LiveAudioTurnBuffer.Frame> cancelled = turn.cancel();

        assertEquals(1, cancelled.size());
        assertEquals(LiveAudioTurnBuffer.Kind.ACTIVITY_END, cancelled.get(0).kind);
        assertFalse(turn.isStarted());
        assertTrue(turn.finish().isEmpty());
    }

    @Test public void releaseAndCancelRaceCanEmitOnlyOneActivityEnd() throws Exception {
        PttAudioTurnFramer turn = new PttAudioTurnFramer(11L);
        turn.offer(new byte[PttAudioTurnFramer.PREROLL_BYTES]);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger endMarkers = new AtomicInteger();
        Thread release = new Thread(() -> {
            await(start);
            countEnds(turn.finish(), endMarkers);
        });
        Thread cancel = new Thread(() -> {
            await(start);
            countEnds(turn.cancel(), endMarkers);
        });
        release.start();
        cancel.start();
        start.countDown();
        release.join();
        cancel.join();
        assertEquals(1, endMarkers.get());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void countEnds(List<LiveAudioTurnBuffer.Frame> frames, AtomicInteger count) {
        for (LiveAudioTurnBuffer.Frame frame : frames) {
            if (frame.kind == LiveAudioTurnBuffer.Kind.ACTIVITY_END) count.incrementAndGet();
        }
    }
}
