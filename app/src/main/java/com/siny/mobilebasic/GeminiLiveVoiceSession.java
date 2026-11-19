package com.siny.mobilebasic;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Direct, native-audio Gemini Live conversation. No Android speech-to-text is used. */
final class GeminiLiveVoiceSession implements AutoCloseable {
    interface UserTurnFactory { long begin(); }

    interface Listener {
        void onReady();
        void onStatus(String status);
        void onFailure(String message);
        void onToolCalls(JSONArray calls, long userTurn);
        void onAssistantTurnComplete(boolean includedAudio, String transcript, long playbackToken);
        void onAssistantInterrupted();
        void onAssistantTranscript(String transcript);
        void onUserTurnAutoEnded();
    }

    static final String MODEL = GeminiLiveProtocol.MODEL;
    private static final int INPUT_RATE = GeminiLiveProtocol.INPUT_SAMPLE_RATE;
    private static final int OUTPUT_RATE = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE;
    private static final int INPUT_CHUNK_BYTES = 2048;
    private static final int MAX_PENDING_INPUT_BYTES = INPUT_RATE * 2 * 12;
    private static final long MAX_USER_TURN_MS = 60_000L;
    private static final long INTERRUPTED_RESPONSE_BOUNDARY_TIMEOUT_MS = 5_000L;

    private final Object lock = new Object();
    private final Listener listener;
    private final OkHttpClient client;
    private final String apiKey;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ma-live-audio");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ma-live-playback");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService responseWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ma-live-response-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final LiveAudioTurnBuffer pendingInputAudio = new LiveAudioTurnBuffer(MAX_PENDING_INPUT_BYTES);
    private final AtomicLong playbackGeneration = new AtomicLong();
    private PttAudioTurnFramer activeInputTurn;

    private WebSocket socket;
    private AudioRecord recorder;
    private AudioTrack player;
    private boolean ready;
    private volatile boolean recordingRequested;
    private volatile long activeUserTurn;
    private long lastSentAudioUserTurn = -1L;
    private long socketActivityUserTurn = -1L;
    private boolean captureRunning;
    private volatile boolean closed;
    private boolean toolCallPending;
    private boolean assistantResponsePending;
    private long assistantResponseGeneration;
    private ScheduledFuture<?> assistantResponseTimeout;
    private ScheduledFuture<?> interruptedResponseBoundaryTimeout;
    private long interruptedResponseBoundaryGeneration;
    private boolean suppressAssistantAudioTurn;
    private final LiveAssistantAudioGate assistantAudioGate = new LiveAssistantAudioGate();
    private long captureRequestGeneration;
    private long queuedCaptureGeneration = -1L;
    private long queuedCaptureUserTurn = -1L;
    private boolean assistantAudioInTurn;
    private final StringBuilder assistantTranscriptInTurn = new StringBuilder();

    GeminiLiveVoiceSession(String apiKey, Listener listener) {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("Gemini API 키가 비어 있습니다.");
        this.listener = listener;
        this.apiKey = apiKey.trim();
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        connect(this.apiKey);
    }

    boolean startUserTurn(UserTurnFactory turnFactory) {
        if (turnFactory == null) return false;
        synchronized (lock) {
            // ACTION_UP stops AudioRecord asynchronously. Do not overlap two manual activity windows.
            if (captureRunning) return false;
            LiveInputTurnAdmission.Decision admission = LiveInputTurnAdmission.decide(
                    closed, toolCallPending, !ready, !pendingInputAudio.isEmpty());
            if (admission != LiveInputTurnAdmission.Decision.ACCEPTED) return false;
            boolean responseInFlight = assistantResponsePending || assistantAudioInTurn;
            if (assistantResponsePending) {
                assistantResponsePending = false;
                cancelAssistantResponseTimeoutLocked();
            }
            // A one-time flush cannot stop chunks already in flight from arriving later.
            // Keep them muted through capture and until the previous server turn boundary.
            assistantAudioGate.beginCapture(responseInFlight);
            flushPlayback();
            assistantAudioInTurn = false;
            assistantTranscriptInTurn.setLength(0);
            long userTurn = turnFactory.begin();
            activeUserTurn = userTurn;
            activeInputTurn = new PttAudioTurnFramer(userTurn);
            recordingRequested = true;
            long requestGeneration = ++captureRequestGeneration;
            if (socket == null) {
                connect(apiKey);
                listener.onStatus("AI 음성 대화를 연결하고 있어요.");
            }
            startCaptureLocked(requestGeneration, userTurn);
            return true;
        }
    }

    void stopUserTurn() {
        AudioRecord active;
        synchronized (lock) {
            recordingRequested = false;
            queuedCaptureGeneration = -1L;
            queuedCaptureUserTurn = -1L;
            active = recorder;
        }
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    void cancelUserTurn() {
        AudioRecord active;
        long cancelledTurn;
        synchronized (lock) {
            cancelledTurn = activeUserTurn;
            recordingRequested = false;
            captureRequestGeneration++;
            queuedCaptureGeneration = -1L;
            queuedCaptureUserTurn = -1L;
            pendingInputAudio.discardTurn(cancelledTurn);
            if (activeInputTurn != null && activeInputTurn.userTurn() == cancelledTurn) {
                activeInputTurn.cancel();
                activeInputTurn = null;
            }
            if (socketActivityUserTurn == cancelledTurn && ready && socket != null) {
                socket.send(GeminiLiveProtocol.activityEndMessage().toString());
                socketActivityUserTurn = -1L;
            }
            active = recorder;
        }
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    void sendToolResponses(JSONArray functionResponses) {
        synchronized (lock) {
            if (closed || socket == null || !ready) return;
            if (!socket.send(GeminiLiveProtocol.toolResponseMessage(functionResponses).toString())) {
                WebSocket failed = socket;
                if (failed != null) failConnection(failed,
                        "검색 결과를 AI 대화에 전달하지 못했어요. 마이크를 다시 눌러 주세요.");
                return;
            }
            toolCallPending = false;
            assistantResponsePending = true;
            scheduleAssistantResponseTimeoutLocked();
            if (!pendingInputAudio.isEmpty()) {
                WebSocket failed = socket;
                if (failed != null) failConnection(failed,
                        "AI가 장소를 확인하는 동안 음성이 섞였어요. 대화를 다시 연결해 주세요.");
            }
        }
    }

    boolean isInputBlocked() {
        // A pending tool call remains serialized; assistant audio is interruptible by PTT.
        synchronized (lock) { return toolCallPending && !closed; }
    }

    void suppressAssistantAudioUntilTurnComplete() {
        synchronized (lock) {
            if (!closed) suppressAssistantAudioTurn = true;
        }
    }

    boolean isCurrentPlayback(long playbackToken) {
        return !closed && playbackGeneration.get() == playbackToken;
    }

    boolean isReady() {
        synchronized (lock) { return ready && !closed; }
    }

    private void connect(String apiKey) {
        final String url;
        try {
            url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta."
                    + "GenerativeService.BidiGenerateContent?key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            listener.onFailure("Gemini Live 연결 주소를 만들지 못했어요.");
            return;
        }
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                JSONObject setup = GeminiLiveProtocol.setupMessage();
                if (!webSocket.send(setup.toString())) {
                    listener.onFailure("Gemini Live 설정을 보내지 못했어요.");
                }
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleServerMessage(webSocket, text);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                AudioRecord activeRecorder = null;
                boolean current = false;
                synchronized (lock) {
                    if (socket == webSocket) {
                        socket = null;
                        ready = false;
                        toolCallPending = false;
                        assistantResponsePending = false;
                        socketActivityUserTurn = -1L;
                        activeInputTurn = null;
                        assistantAudioGate.reset();
                        suppressAssistantAudioTurn = false;
                        cancelAssistantResponseTimeoutLocked();
                cancelInterruptedResponseBoundaryTimeoutLocked();
                        recordingRequested = false;
                        captureRequestGeneration++;
                        queuedCaptureGeneration = -1L;
                        queuedCaptureUserTurn = -1L;
                        clearPendingInputLocked();
                        activeRecorder = recorder;
                        current = true;
                    }
                }
                if (activeRecorder != null) {
                    try { activeRecorder.stop(); } catch (RuntimeException ignored) { }
                }
                if (current && !closed) listener.onFailure("Gemini Live 연결이 끊겼어요. 인터넷과 API 키를 확인해 주세요.");
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                AudioRecord activeRecorder = null;
                boolean current = false;
                synchronized (lock) {
                    if (socket == webSocket) {
                        socket = null;
                        ready = false;
                        toolCallPending = false;
                        assistantResponsePending = false;
                        socketActivityUserTurn = -1L;
                        activeInputTurn = null;
                        assistantAudioGate.reset();
                        suppressAssistantAudioTurn = false;
                        cancelAssistantResponseTimeoutLocked();
                        cancelInterruptedResponseBoundaryTimeoutLocked();
                        recordingRequested = false;
                        captureRequestGeneration++;
                        queuedCaptureGeneration = -1L;
                        queuedCaptureUserTurn = -1L;
                        clearPendingInputLocked();
                        activeRecorder = recorder;
                        current = true;
                    }
                }
                if (activeRecorder != null) {
                    try { activeRecorder.stop(); } catch (RuntimeException ignored) { }
                }
                if (current && !closed) listener.onFailure("AI 음성 연결이 종료됐어요. 마이크를 다시 눌러 재연결해 주세요.");
            }
        });
    }

    private void handleServerMessage(WebSocket source, String text) {
        try {
            synchronized (lock) {
                if (closed || socket != source) return;
            }
            JSONObject root = new JSONObject(text);
            if (root.has("setupComplete")) {
                boolean setupReady;
                synchronized (lock) {
                    if (closed || socket != source) return;
                    ready = true;
                    setupReady = flushPendingInputLocked();
                    if (setupReady && recordingRequested) {
                        startCaptureLocked(captureRequestGeneration, activeUserTurn);
                    }
                }
                if (!setupReady) {
                    failConnection(source, "Gemini Live에 첫 음성을 전달하지 못했어요. 마이크를 다시 눌러 주세요.");
                    return;
                }
                listener.onReady();
                return;
            }
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                failConnection(source, "Gemini Live 오류: " + error.optString("message", "요청을 처리하지 못했어요."));
                return;
            }
            JSONArray calls = root.optJSONObject("toolCall") == null ? null
                    : root.optJSONObject("toolCall").optJSONArray("functionCalls");
            if (calls != null && calls.length() > 0) {
                long toolUserTurn;
                WebSocket staleCallSocket = null;
                synchronized (lock) {
                    if (closed || socket != source) return;
                    if (assistantAudioGate.shouldRejectToolCall()) {
                        staleCallSocket = socket;
                        toolUserTurn = -1L;
                    } else {
                        toolCallPending = true;
                        scheduleToolCallTimeoutLocked();
                        for (int i = 0; i < calls.length(); i++) {
                            JSONObject call = calls.optJSONObject(i);
                            String name = call == null ? "" : call.optString("name", "");
                            if ("search_places".equals(name) || "select_candidate".equals(name)) {
                                suppressAssistantAudioTurn = true;
                                break;
                            }
                        }
                        toolUserTurn = lastSentAudioUserTurn;
                    }
                }
                if (staleCallSocket != null) {
                    failConnection(staleCallSocket,
                            "이전 음성 응답이 새 말씀과 겹쳐 대화를 다시 연결해요. 마이크를 다시 눌러 주세요.");
                    return;
                }
                listener.onToolCalls(calls, toolUserTurn);
            }

            JSONObject serverContent = root.optJSONObject("serverContent");
            if (serverContent == null) return;
            boolean interrupted = serverContent.optBoolean("interrupted", false);
            if (interrupted) {
                flushPlayback();
                synchronized (lock) {
                    assistantAudioInTurn = false;
                    assistantTranscriptInTurn.setLength(0);
                    assistantAudioGate.onAssistantResponseBoundary();
                    cancelInterruptedResponseBoundaryTimeoutLocked();
                }
                listener.onAssistantInterrupted();
            }
            JSONObject outputTranscription = serverContent.optJSONObject("outputTranscription");
            if (outputTranscription != null) {
                String transcript = outputTranscription.optString("text", "").trim();
                if (!transcript.isEmpty()) {
                    boolean suppressed;
                    synchronized (lock) {
                        suppressed = interrupted || suppressAssistantAudioTurn
                                || assistantAudioGate.shouldDropAudio();
                        if (!suppressed) assistantTranscriptInTurn.append(transcript).append(' ');
                    }
                    if (!suppressed) listener.onAssistantTranscript(transcript);
                }
            }
            JSONObject modelTurn = serverContent.optJSONObject("modelTurn");
            JSONArray parts = modelTurn == null ? null : modelTurn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    JSONObject inlineData = part == null ? null : part.optJSONObject("inlineData");
                    if (inlineData == null) continue;
                    String mimeType = inlineData.optString("mimeType", "");
                    if (!mimeType.startsWith("audio/pcm")) continue;
                    synchronized (lock) {
                        if (interrupted || suppressAssistantAudioTurn
                                || assistantAudioGate.shouldDropAudio()) continue;
                    }
                    byte[] pcm = Base64.getDecoder().decode(inlineData.optString("data", ""));
                    if (pcm.length > 0) {
                        synchronized (lock) {
                            assistantAudioInTurn = true;
                        }
                        play(pcm);
                    }
                }
            }
            if (serverContent.optBoolean("turnComplete", false)) {
                boolean includedAudio;
                String transcript;
                synchronized (lock) {
                    includedAudio = assistantAudioInTurn;
                    transcript = assistantTranscriptInTurn.toString();
                    assistantAudioInTurn = false;
                    assistantTranscriptInTurn.setLength(0);
                    suppressAssistantAudioTurn = false;
                    assistantResponsePending = false;
                    cancelAssistantResponseTimeoutLocked();
                    assistantAudioGate.onAssistantResponseBoundary();
                    cancelInterruptedResponseBoundaryTimeoutLocked();
                }
                long completedPlaybackGeneration = playbackGeneration.get();
                playbackExecutor.execute(() -> {
                    if (!closed && playbackGeneration.get() == completedPlaybackGeneration) {
                        listener.onAssistantTurnComplete(includedAudio, transcript,
                                completedPlaybackGeneration);
                    }
                });
            } else if (interrupted) {
                // Drop audio bundled with the interruption frame before reopening playback.
                synchronized (lock) {
                    assistantAudioGate.onAssistantResponseBoundary();
                    cancelInterruptedResponseBoundaryTimeoutLocked();
                }
            }
        } catch (Exception e) {
            failConnection(source, "Gemini Live 응답을 읽지 못했어요.");
        }
    }

    private void failConnection(WebSocket source, String message) {
        AudioRecord activeRecorder = null;
        boolean notify = false;
        synchronized (lock) {
            if (closed || socket != source) return;
            socket = null;
            ready = false;
            toolCallPending = false;
            assistantResponsePending = false;
            assistantAudioInTurn = false;
            assistantTranscriptInTurn.setLength(0);
            socketActivityUserTurn = -1L;
            activeInputTurn = null;
            assistantAudioGate.reset();
            suppressAssistantAudioTurn = false;
            cancelAssistantResponseTimeoutLocked();
            cancelInterruptedResponseBoundaryTimeoutLocked();
            pendingInputAudio.clear();
            captureRequestGeneration++;
            queuedCaptureGeneration = -1L;
            queuedCaptureUserTurn = -1L;
            notify = true;
            if (recordingRequested) {
                recordingRequested = false;
                activeRecorder = recorder;
            }
        }
        if (activeRecorder != null) {
            try { activeRecorder.stop(); } catch (RuntimeException ignored) { }
        }
        source.close(1000, "fail closed");
        source.cancel();
        if (notify) listener.onFailure(message);
    }

    private void startCaptureLocked(long requestGeneration, long userTurn) {
        if (!recordingRequested || closed || requestGeneration != captureRequestGeneration) return;
        if (captureRunning) {
            queuedCaptureGeneration = requestGeneration;
            queuedCaptureUserTurn = userTurn;
            return;
        }
        captureRunning = true;
        try {
            audioExecutor.execute(() -> captureAudio(requestGeneration, userTurn));
        } catch (RuntimeException e) {
            captureRunning = false;
            recordingRequested = false;
            captureRequestGeneration++;
            queuedCaptureGeneration = -1L;
            queuedCaptureUserTurn = -1L;
            listener.onFailure("마이크 음성을 시작하지 못했어요. 다시 눌러 주세요.");
        }
    }

    private void captureAudio(long requestGeneration, long userTurn) {
        AudioRecord active = null;
        boolean autoEnded = false;
        try {
            int minBytes = AudioRecord.getMinBufferSize(INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) throw new IOException("마이크 오디오 형식을 사용할 수 없습니다.");
            int bufferBytes = Math.max(minBytes * 2, INPUT_CHUNK_BYTES * 2);
            active = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    INPUT_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (active.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("마이크를 초기화하지 못했습니다.");
            }
            synchronized (lock) {
                if (!isRecordingRequestActive(requestGeneration)) return;
                recorder = active;
                active.startRecording();
            }
            listener.onStatus("Gemini와 대화 중이에요. 손을 떼면 말하기를 마쳐요.");
            byte[] buffer = new byte[INPUT_CHUNK_BYTES];
            long captureStartedAt = SystemClock.elapsedRealtime();
            while (isRecordingRequestActive(requestGeneration)) {
                if (SystemClock.elapsedRealtime() - captureStartedAt >= MAX_USER_TURN_MS) {
                    synchronized (lock) {
                        if (recordingRequested && requestGeneration == captureRequestGeneration) {
                            recordingRequested = false;
                            autoEnded = true;
                        }
                    }
                    if (autoEnded) {
                        try { active.stop(); } catch (RuntimeException ignored) { }
                    }
                    break;
                }
                int count = active.read(buffer, 0, buffer.length);
                // Preserve the last PCM frame returned by AudioRecord when ACTION_UP stops it.
                // A subsequent ACTION_DOWN increments the generation and makes late old frames stale.
                if (count > 0 && isCurrentCaptureGeneration(requestGeneration)) {
                    byte[] chunk = new byte[count];
                    System.arraycopy(buffer, 0, chunk, 0, count);
                    if (!sendAudioChunk(chunk, requestGeneration, userTurn)) {
                        throw new IOException("Gemini Live에 음성을 보내지 못했습니다.");
                    }
                } else if (count < 0 && isRecordingRequestActive(requestGeneration)) {
                    throw new IOException("마이크 입력이 중단됐습니다.");
                }
            }
        } catch (Exception e) {
            boolean notify;
            boolean releasedNormally;
            WebSocket activitySocket = null;
            synchronized (lock) {
                notify = !closed && recordingRequested
                        && requestGeneration == captureRequestGeneration;
                releasedNormally = !closed && !recordingRequested
                        && requestGeneration == captureRequestGeneration;
                if (notify) {
                    recordingRequested = false;
                    captureRequestGeneration++;
                    queuedCaptureGeneration = -1L;
                    queuedCaptureUserTurn = -1L;
                }
                if (recorder == active) recorder = null;
                if (!releasedNormally) {
                    pendingInputAudio.discardTurn(userTurn);
                    if (socketActivityUserTurn == userTurn) activitySocket = socket;
                    if (activeInputTurn != null && activeInputTurn.userTurn() == userTurn) {
                        activeInputTurn.cancel();
                        activeInputTurn = null;
                    }
                }
            }
            if (active != null) {
                try { active.stop(); } catch (RuntimeException ignored) { }
            }
            if (activitySocket != null) {
                failConnection(activitySocket,
                        "음성 전송이 끊겨 대화를 안전하게 다시 시작해야 해요.");
            }
            if (notify && activitySocket == null) {
                listener.onFailure("마이크 음성을 전달하지 못했어요. 연결을 확인하고 다시 눌러 주세요.");
            }
        } finally {
            synchronized (lock) {
                if (recorder == active) recorder = null;
            }
            if (active != null) {
                try { active.release(); } catch (RuntimeException ignored) { }
            }
            if (isCurrentCaptureGeneration(requestGeneration)) finishAudioInputTurn(userTurn);
            synchronized (lock) {
                captureRunning = false;
                if (recordingRequested && queuedCaptureGeneration == captureRequestGeneration
                        && !closed) {
                    long nextGeneration = queuedCaptureGeneration;
                    long nextUserTurn = queuedCaptureUserTurn;
                    queuedCaptureGeneration = -1L;
                    queuedCaptureUserTurn = -1L;
                    startCaptureLocked(nextGeneration, nextUserTurn);
                }
            }
            if (autoEnded) listener.onUserTurnAutoEnded();
        }
    }

    private boolean sendAudioChunk(byte[] pcm, long requestGeneration, long userTurn) {
        synchronized (lock) {
            if (closed
                    || requestGeneration != captureRequestGeneration
                    || pcm == null || pcm.length == 0) return false;
            if (toolCallPending) return false;
            if (activeInputTurn == null || activeInputTurn.userTurn() != userTurn) return false;
            return queueOrSendInputFramesLocked(activeInputTurn.offer(pcm));
        }
    }

    private void finishAudioInputTurn(long userTurn) {
        synchronized (lock) {
            if (closed) return;
            if (toolCallPending) {
                WebSocket failed = socket;
                if (failed != null) failConnection(failed,
                        "장소 확인과 음성 입력이 겹쳐 대화를 다시 연결해야 해요.");
                return;
            }
            if (activeInputTurn == null || activeInputTurn.userTurn() != userTurn) return;
            List<LiveAudioTurnBuffer.Frame> frames = activeInputTurn.finish();
            activeInputTurn = null;
            if (!queueOrSendInputFramesLocked(frames) && socket != null) {
                failConnection(socket, "음성 입력을 마치지 못해 대화를 다시 연결해야 해요.");
            }
            assistantAudioGate.endCapture();
            if (assistantAudioGate.isWaitingForPriorResponseBoundary()) {
                scheduleInterruptedResponseBoundaryTimeoutLocked();
            } else {
                cancelInterruptedResponseBoundaryTimeoutLocked();
            }
        }
    }

    private void scheduleInterruptedResponseBoundaryTimeoutLocked() {
        cancelInterruptedResponseBoundaryTimeoutLocked();
        long generation = ++interruptedResponseBoundaryGeneration;
        interruptedResponseBoundaryTimeout = responseWatchdog.schedule(() -> {
            WebSocket failed;
            synchronized (lock) {
                if (closed || generation != interruptedResponseBoundaryGeneration
                        || !assistantAudioGate.isWaitingForPriorResponseBoundary()) return;
                failed = socket;
            }
            if (failed != null) failConnection(failed,
                    "AI 음성 응답이 끝나지 않아 대화를 다시 연결해요. 마이크를 다시 눌러 주세요.");
        }, INTERRUPTED_RESPONSE_BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelInterruptedResponseBoundaryTimeoutLocked() {
        interruptedResponseBoundaryGeneration++;
        if (interruptedResponseBoundaryTimeout != null) {
            interruptedResponseBoundaryTimeout.cancel(false);
            interruptedResponseBoundaryTimeout = null;
        }
    }

    /** Called with lock held; keeps activityStart, audio frames and activityEnd in press/release order. */
    private boolean queueOrSendInputFramesLocked(List<LiveAudioTurnBuffer.Frame> frames) {
        if (frames == null || frames.isEmpty()) return true;
        for (LiveAudioTurnBuffer.Frame frame : frames) {
            if (!ready || socket == null || !pendingInputAudio.isEmpty()) {
                boolean queued;
                if (frame.kind == LiveAudioTurnBuffer.Kind.ACTIVITY_START) {
                    pendingInputAudio.offerActivityStart(frame.userTurn);
                    queued = true;
                } else if (frame.kind == LiveAudioTurnBuffer.Kind.ACTIVITY_END) {
                    pendingInputAudio.offerActivityEnd(frame.userTurn);
                    queued = true;
                } else {
                    queued = pendingInputAudio.offerAudio(frame.pcm, frame.userTurn);
                }
                if (!queued) return false;
            } else if (!sendInputFrameLocked(frame)) {
                return false;
            }
        }
        return !ready || flushPendingInputLocked();
    }

    private boolean sendInputFrameLocked(LiveAudioTurnBuffer.Frame frame) {
        if (socket == null) return false;
        JSONObject message;
        switch (frame.kind) {
            case ACTIVITY_START:
                if (socketActivityUserTurn != -1L || !socket.send(
                        GeminiLiveProtocol.activityStartMessage().toString())) return false;
                socketActivityUserTurn = frame.userTurn;
                return true;
            case AUDIO:
                if (socketActivityUserTurn != frame.userTurn
                        || !socket.send(GeminiLiveProtocol.audioMessage(frame.pcm).toString())) return false;
                return true;
            case ACTIVITY_END:
                if (socketActivityUserTurn != frame.userTurn) return false;
                message = GeminiLiveProtocol.activityEndMessage();
                if (!socket.send(message.toString())) return false;
                socketActivityUserTurn = -1L;
                lastSentAudioUserTurn = frame.userTurn;
                return true;
            default:
                return false;
        }
    }

    /** Called with lock held; flushes each touch turn with its explicit manual boundary. */
    private boolean flushPendingInputLocked() {
        if (!ready || socket == null || toolCallPending) return false;
        while (!pendingInputAudio.isEmpty()) {
            LiveAudioTurnBuffer.Frame frame = pendingInputAudio.poll();
            if (!sendInputFrameLocked(frame)) return false;
        }
        return true;
    }

    private void scheduleAssistantResponseTimeoutLocked() {
        cancelAssistantResponseTimeoutLocked();
        long generation = ++assistantResponseGeneration;
        assistantResponseTimeout = responseWatchdog.schedule(() -> {
            WebSocket failed;
            synchronized (lock) {
                if (closed || !assistantResponsePending || generation != assistantResponseGeneration) return;
                failed = socket;
            }
            if (failed != null) failConnection(failed,
                    "AI 응답이 늦어져 대화를 다시 연결해야 해요. 잠시 뒤 마이크를 다시 눌러 주세요.");
        }, 30, TimeUnit.SECONDS);
    }

    private void scheduleToolCallTimeoutLocked() {
        cancelAssistantResponseTimeoutLocked();
        long generation = ++assistantResponseGeneration;
        assistantResponseTimeout = responseWatchdog.schedule(() -> {
            WebSocket failed;
            synchronized (lock) {
                if (closed || !toolCallPending || generation != assistantResponseGeneration) return;
                failed = socket;
            }
            if (failed != null) failConnection(failed,
                    "장소 검색이나 음성 안내가 오래 걸려 대화를 다시 연결해 주세요. 다시 말씀해 주세요.");
        }, 30, TimeUnit.SECONDS);
    }

    private void cancelAssistantResponseTimeoutLocked() {
        assistantResponseGeneration++;
        if (assistantResponseTimeout != null) {
            assistantResponseTimeout.cancel(false);
            assistantResponseTimeout = null;
        }
    }

    private boolean isRecordingRequestActive(long requestGeneration) {
        synchronized (lock) {
            return !closed && recordingRequested && requestGeneration == captureRequestGeneration;
        }
    }

    private boolean isCurrentCaptureGeneration(long requestGeneration) {
        synchronized (lock) {
            return !closed && requestGeneration == captureRequestGeneration;
        }
    }

    private void play(byte[] pcm) {
        queuePlayback(pcm, playbackGeneration.get());
    }

    private void queuePlayback(byte[] pcm, long generation) {
        playbackExecutor.execute(() -> {
            try {
                if (closed || generation != playbackGeneration.get()) return;
                AudioTrack active = ensurePlayer();
                if (active.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) active.play();
                int offset = 0;
                while (offset < pcm.length && !closed && generation == playbackGeneration.get()) {
                    int written = active.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) return;
                    offset += written;
                }
            } catch (RuntimeException e) {
                if (!closed) listener.onFailure("AI 음성을 재생하지 못했어요.");
            }
        });
    }

    private void queuePlaybackCompletion(boolean includedAudio, String transcript, long generation) {
        playbackExecutor.execute(() -> {
            if (!closed && playbackGeneration.get() == generation) {
                listener.onAssistantTurnComplete(includedAudio, transcript, generation);
            }
        });
    }

    private AudioTrack ensurePlayer() {
        synchronized (lock) {
            if (player != null && player.getState() == AudioTrack.STATE_INITIALIZED) return player;
            int minBytes = AudioTrack.getMinBufferSize(OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack created = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(OUTPUT_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(minBytes, OUTPUT_RATE), AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                created.release();
                throw new IllegalStateException("AI 음성 출력을 초기화하지 못했습니다.");
            }
            player = created;
            return created;
        }
    }

    private void flushPlayback() {
        playbackGeneration.incrementAndGet();
        AudioTrack active;
        synchronized (lock) { active = player; }
        if (active == null) return;
        try {
            active.pause();
            active.flush();
            active.play();
        } catch (RuntimeException ignored) { }
    }

    private boolean send(JSONObject message) {
        WebSocket active;
        synchronized (lock) {
            active = socket;
            if (closed || active == null || !ready) return false;
        }
        return active.send(message.toString());
    }

    private void clearPendingInputLocked() {
        pendingInputAudio.clear();
        activeInputTurn = null;
        socketActivityUserTurn = -1L;
    }

    @Override public void close() {
        WebSocket active;
        AudioRecord activeRecorder;
        AudioTrack activePlayer;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            recordingRequested = false;
            toolCallPending = false;
            assistantResponsePending = false;
            activeInputTurn = null;
            assistantAudioGate.reset();
            suppressAssistantAudioTurn = false;
            socketActivityUserTurn = -1L;
            cancelAssistantResponseTimeoutLocked();
            captureRequestGeneration++;
            queuedCaptureGeneration = -1L;
            queuedCaptureUserTurn = -1L;
            clearPendingInputLocked();
            activeRecorder = recorder;
            recorder = null;
            active = socket;
            socket = null;
            ready = false;
            activePlayer = player;
            player = null;
        }
        playbackGeneration.incrementAndGet();
        if (activeRecorder != null) {
            try { activeRecorder.stop(); } catch (RuntimeException ignored) { }
        }
        if (active != null) active.close(1000, "activity stopped");
        if (activePlayer != null) {
            try { activePlayer.pause(); activePlayer.flush(); activePlayer.release(); }
            catch (RuntimeException ignored) { }
        }
        audioExecutor.shutdownNow();
        playbackExecutor.shutdownNow();
        responseWatchdog.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
