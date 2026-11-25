package com.siny.mobilebasic.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded 16 kHz mono PCM recorder. The UI owns start/stop so it only records while held. */
public final class PcmAudioRecorder {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SECONDS = 30;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private volatile AudioRecord recorder;
    private Thread reader;
    private volatile IOException readFailure;

    public synchronized void start() throws IOException {
        if (recording.get()) return;
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("마이크를 시작하지 못했습니다.");
        int bufferSize = Math.max(minimum, SAMPLE_RATE * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            throw new IOException("마이크를 사용할 수 없습니다.");
        }
        pcm.reset();
        readFailure = null;
        recording.set(true);
        recorder.startRecording();
        reader = new Thread(() -> readLoop(bufferSize / 2), "ma-voice-capture");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(int shortBufferSize) {
        short[] buffer = new short[Math.max(shortBufferSize, 1024)];
        int maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS;
        while (recording.get()) {
            AudioRecord active = recorder;
            if (active == null) break;
            int count = active.read(buffer, 0, buffer.length);
            if (count < 0) {
                if (recording.get()) readFailure = new IOException("마이크 입력을 읽지 못했습니다.");
                break;
            }
            synchronized (pcm) {
                int remaining = maxBytes - pcm.size();
                int accepted = Math.min(count, Math.max(0, remaining / 2));
                for (int i = 0; i < accepted; i++) {
                    int sample = buffer[i];
                    pcm.write(sample & 0xff);
                    pcm.write((sample >>> 8) & 0xff);
                }
                if (accepted < count) {
                    break;
                }
            }
        }
    }

    public synchronized byte[] stopAndGetWav() throws IOException {
        AudioRecord active = recorder;
        Thread activeReader = reader;
        if (!recording.get() && active == null) throw new IOException("녹음된 음성이 없습니다.");
        stopCapture();
        if (activeReader != null) {
            try { activeReader.join(1500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("녹음을 마치지 못했습니다.", e); }
        }
        if (active != null) active.release();
        recorder = null;
        reader = null;
        if (readFailure != null) throw readFailure;
        byte[] audio;
        synchronized (pcm) { audio = pcm.toByteArray(); }
        if (audio.length < SAMPLE_RATE / 2) throw new IOException("조금 더 길게 말씀해 주세요.");
        return wav(audio);
    }

    /** Stops audio capture immediately on finger-up; joining and encoding happen off the UI thread. */
    public void stopCapture() {
        AudioRecord active;
        synchronized (this) {
            recording.set(false);
            active = recorder;
        }
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    public synchronized void cancel() {
        try { stopAndGetWav(); } catch (IOException ignored) { }
    }

    private static byte[] wav(byte[] pcm) {
        byte[] header = new byte[44];
        ByteBuffer out = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.putInt(36 + pcm.length);
        out.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.putInt(16).putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE);
        out.putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.putInt(pcm.length);
        ByteArrayOutputStream result = new ByteArrayOutputStream(header.length + pcm.length);
        try { result.write(header); result.write(pcm); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return result.toByteArray();
    }
}
