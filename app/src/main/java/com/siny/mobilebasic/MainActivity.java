package com.siny.mobilebasic;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioRecord;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;

import com.siny.mobilebasic.assistant.ClovaSpeechClient;
import com.siny.mobilebasic.assistant.ClovaStudioClient;
import com.siny.mobilebasic.assistant.DestinationContext;
import com.siny.mobilebasic.assistant.DestinationConversation;
import com.siny.mobilebasic.assistant.NaverMapLink;
import com.siny.mobilebasic.assistant.NaverPlaceSearchClient;
import com.siny.mobilebasic.assistant.NaverPlaceRelevance;
import com.siny.mobilebasic.assistant.PcmAudioRecorder;
import com.siny.mobilebasic.assistant.PlaceCandidate;
import com.siny.mobilebasic.modules.AutoSyncModule;
import com.siny.mobilebasic.modules.AutoUpdateModule;
import com.siny.mobilebasic.modules.MobileJobs;
import com.siny.mobilebasic.modules.SecureSettings;
import com.siny.mobilebasic.storage.MobileStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A feature-free app shell with the shared update and sync modules wired in. */
public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mobile-basic-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicBoolean updateRunning = new AtomicBoolean(false);
    private MobileStore store;
    private SecureSettings settings;
    private AutoSyncModule autosync;
    private AutoUpdateModule autoupdate;
    private LinearLayout root;
    private TextView syncStatus;
    private TextView updateStatus;
    private TextView appVersionStatus;
    private String moduleFailure = "";
    private Button microphoneButton;
    private TextView conversationStatus;
    private TextView settingsSyncStatus;
    private TextView settingsUpdateStatus;
    private final PcmAudioRecorder recorder = new PcmAudioRecorder();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean finishingRecording = new AtomicBoolean(false);
    private final DestinationConversation conversation = new DestinationConversation();
    private TextToSpeech speech;
    private boolean speechReady;
    private int invalidChoiceCount;
    private String lastTranscript = "";
    private volatile String destinationRequest = "";
    private final Runnable maxRecordingTime = () -> finishVoiceInput();

    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            runSync();
            handler.postDelayed(this, AutoSyncModule.FOREGROUND_INTERVAL_MS);
        }
    };
    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            runUpdateCheck();
            handler.postDelayed(this, AutoUpdateModule.FOREGROUND_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new SecureSettings(this, AppIdentity.APP_ID);
        store = new MobileStore(this);
        autosync = new AutoSyncModule(this, store, settings);
        autoupdate = new AutoUpdateModule(this, settings);
        moduleFailure = verifyModuleContract();
        MobileJobs.schedule(this);
        buildUi();
        speech = new TextToSpeech(this, status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(Locale.KOREAN);
        });
        if (!moduleFailure.isEmpty()) {
            showSync("기본 모듈 확인 필요: " + moduleFailure);
            showUpdate("자동 업데이트를 시작하지 않았습니다.");
        } else {
            showLocalSyncState();
            showUpdate("앱 버전 " + installedVersionText() + " · 업데이트 확인 대기 중");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (moduleFailure.isEmpty()) {
            handler.removeCallbacks(syncTick);
            handler.post(syncTick);
            handler.removeCallbacks(updateTick);
            handler.post(updateTick);
        }
    }

    @Override protected void onPause() {
        if (recording.get()) cancelVoiceInput();
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(updateTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        recorder.cancel();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
        }
        worker.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(0xffffffff);
        getWindow().setNavigationBarColor(0xffffffff);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(0xffffffff);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(2), dp(22), dp(12));

        FrameLayout header = new FrameLayout(this);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("MA");
        title.setTextSize(38);
        title.setTextColor(0xff102a2e);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        brand.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Mother’s Assistant");
        subtitle.setTextSize(18);
        subtitle.setTextColor(0xff718087);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-2, -2);
        subtitleParams.topMargin = dp(2);
        brand.addView(subtitle, subtitleParams);
        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(-1, dp(136), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        header.addView(brand, brandParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(30);
        settingsButton.setTextColor(0xff718087);
        settingsButton.setAllCaps(false);
        settingsButton.setMinWidth(dp(48));
        settingsButton.setMinHeight(dp(48));
        settingsButton.setPadding(0, 0, 0, 0);
        settingsButton.setBackgroundColor(0x00000000);
        settingsButton.setContentDescription("설정");
        settingsButton.setTranslationY(-dp(6));
        settingsButton.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.END);
        header.addView(settingsButton, settingsParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(146)));

        Space topSpace = new Space(this);
        root.addView(topSpace, new LinearLayout.LayoutParams(1, 0, 0.95f));
        microphoneButton = new Button(this);
        microphoneButton.setText("말하기");
        microphoneButton.setTextSize(25);
        microphoneButton.setTextColor(0xffffffff);
        microphoneButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        microphoneButton.setAllCaps(false);
        microphoneButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_ma_microphone, 0, 0, 0);
        microphoneButton.setCompoundDrawablePadding(dp(10));
        microphoneButton.setMinHeight(dp(70));
        microphoneButton.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable micBackground = new GradientDrawable();
        micBackground.setColor(0xff07877d);
        micBackground.setCornerRadius(dp(64));
        microphoneButton.setBackground(micBackground);
        microphoneButton.setContentDescription("누르고 말하기. 손가락을 떼면 음성 전송을 마칩니다.");
        microphoneButton.setOnClickListener(v -> { });
        microphoneButton.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startVoiceInput();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (event.getAction() == MotionEvent.ACTION_CANCEL) cancelVoiceInput();
                else {
                    finishVoiceInput();
                    view.performClick();
                }
                return true;
            }
            return true;
        });
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(164), dp(70));
        micParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(microphoneButton, micParams);

        conversationStatus = new TextView(this);
        conversationStatus.setTextSize(17);
        conversationStatus.setTextColor(0xff41535b);
        conversationStatus.setGravity(Gravity.CENTER);
        conversationStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams conversationParams = new LinearLayout.LayoutParams(-1, -2);
        conversationParams.topMargin = dp(14);
        root.addView(conversationStatus, conversationParams);

        root.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1.45f));
        LinearLayout taxiRow = new LinearLayout(this);
        taxiRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button taxi = new Button(this);
        taxi.setText("🚕");
        taxi.setTextSize(23);
        taxi.setAllCaps(false);
        taxi.setMinWidth(0);
        taxi.setMinHeight(0);
        taxi.setPadding(0, 0, 0, 0);
        taxi.setBackgroundColor(0x00000000);
        taxi.setContentDescription("택시 호출");
        taxi.setOnClickListener(v -> openKakaoTaxi());
        taxiRow.addView(taxi, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(taxiRow, new LinearLayout.LayoutParams(-1, dp(52)));

        syncStatus = statusText();
        updateStatus = statusText();
        appVersionStatus = statusText();
        appVersionStatus.setText(installedVersionText());
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
    }

    private TextView statusText() {
        TextView view = new TextView(this);
        view.setTextSize(14);
        view.setTextColor(0xff364152);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(0xffffffff);
        return view;
    }

    private void addStatusBlock(String title, TextView content) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setTextColor(0xff17202a);
        heading.setTypeface(null, 1);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(24);
        root.addView(heading, headingParams);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, -2);
        contentParams.topMargin = dp(8);
        root.addView(content, contentParams);
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        return params;
    }

    private void showLocalSyncState() {
        if (settings.serverUrl().trim().isEmpty()) {
            showSync("이 기기에 앱 자료를 저장합니다. 다른 기기 동기화는 서버 설정 후 사용할 수 있습니다.");
        } else {
            showSync("AutoSync 서버 연결을 확인하고 있습니다.");
        }
    }

    private void runSync() {
        if (!moduleFailure.isEmpty()) return;
        if (settings.serverUrl().trim().isEmpty()) {
            showLocalSyncState();
            return;
        }
        if (!syncRunning.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                AutoSyncModule.Result result = null;
                String failure = null;
                try {
                    result = autosync.syncOnce();
                } catch (RuntimeException e) {
                    failure = "AutoSync 오류 · 설정과 저장 상태를 확인하세요.";
                } finally {
                    syncRunning.set(false);
                }
                AutoSyncModule.Result completed = result;
                String error = failure;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) showSync(error);
                    else if (completed != null) {
                        if ("needs-configuration".equals(completed.status)) showLocalSyncState();
                        else showSync("AutoSync " + completed.detail + " · 대기 " + completed.pending
                                + " · 충돌 " + completed.conflicts);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            syncRunning.set(false);
        }
    }

    private void runUpdateCheck() {
        if (!moduleFailure.isEmpty() || !updateRunning.compareAndSet(false, true)) return;
        if (!settings.pendingUpdatePath().isEmpty()) {
            updateRunning.set(false);
            if (settings.pendingUpdateAwaitingPermission()) {
                if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                    settings.resetPendingUpdateInstallAttempted();
                    applyUpdate();
                } else {
                    showUpdate("업데이트 설치 권한이 필요합니다.");
                }
                return;
            }
            if (!settings.pendingUpdateInstallAttempted()) applyUpdate();
            else showUpdate("업데이트 설치 대기 중 · " + installedVersionText());
            return;
        }
        try {
            worker.execute(() -> {
                AutoUpdateModule.Result result = null;
                String failure = null;
                try {
                    result = autoupdate.checkAndDownload();
                } catch (RuntimeException e) {
                    failure = "AutoUpdate 확인 실패 · 인터넷 연결을 확인하고 다시 시도하세요.";
                } finally {
                    updateRunning.set(false);
                }
                AutoUpdateModule.Result completed = result;
                String error = failure;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) showUpdate(error);
                    else if (completed != null) {
                        if ("update-ready".equals(completed.status)) {
                            showUpdate("새 버전 " + formatVersion(completed.versionName, completed.versionCode) + " 설치를 시작합니다.");
                            applyUpdate();
                        } else if ("up-to-date".equals(completed.status)) {
                            long installedCode = installedVersionCodeForUi();
                            if (completed.versionCode == installedCode) {
                                showUpdate("최신 버전입니다.");
                            } else if (completed.versionCode < installedCode) {
                                showUpdate("현재 설치 버전이 배포 버전 "
                                        + formatVersion(completed.versionName, completed.versionCode)
                                        + "보다 새 버전입니다.");
                            } else {
                                showUpdate("현재 " + installedVersionText() + " · " + completed.message);
                            }
                        } else {
                            showUpdate(completed.message);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            updateRunning.set(false);
        }
    }

    private void applyUpdate() {
        AutoUpdateModule.Result result = autoupdate.installPending(this);
        showUpdate("AutoUpdate · " + result.message);
        if ("needs-permission".equals(result.status)) {
            Toast.makeText(this, "설치 권한을 허용한 뒤 다시 업데이트를 확인하세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void startVoiceInput() {
        if (recording.get() || finishingRecording.get()) return;
        if (conversation.expirePendingDestinationIfStale() || conversation.state() == DestinationConversation.State.HANDED_OFF) {
            destinationRequest = "";
            conversation.reset();
        }
        if (conversation.state() == DestinationConversation.State.SEARCHING) {
            showConversation("목적지를 찾고 있어요. 잠시 기다려 주세요.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            showConversation("마이크 권한을 허용한 뒤 말하기를 다시 눌러 주세요.");
            return;
        }
        try {
            finishingRecording.set(false);
            recorder.start();
            recording.set(true);
            microphoneButton.setText("●   듣고 있어요");
            showConversation("말씀하시는 동안만 듣습니다. 손을 떼면 전송합니다.");
            handler.removeCallbacks(maxRecordingTime);
            handler.postDelayed(maxRecordingTime, PcmAudioRecorder.MAX_SECONDS * 1000L);
        } catch (Exception e) {
            recording.set(false);
            showConversation(e.getMessage() == null ? "마이크를 시작하지 못했습니다." : e.getMessage());
            speak(showedConversation());
        }
    }

    private void finishVoiceInput() {
        if (!recording.get() || !finishingRecording.compareAndSet(false, true)) return;
        if (!recording.compareAndSet(true, false)) {
            finishingRecording.set(false);
            return;
        }
        handler.removeCallbacks(maxRecordingTime);
        if (microphoneButton != null) microphoneButton.setText("말하기");
        showConversation("말씀을 확인하고 있어요.");
        // Stop microphone capture on the release event itself, even if network work is queued.
        recorder.stopCapture();
        try {
            worker.execute(() -> {
                try {
                    if (settings.naverApiKeyId().trim().isEmpty() || settings.naverApiKey().trim().isEmpty()) {
                        handler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            sayAndShow("설정에서 NAVER 음성·검색 키와 CLOVA Studio 키를 먼저 입력해 주세요.");
                            showSettings();
                        });
                        return;
                    }
                    byte[] wav = recorder.stopAndGetWav();
                    String transcript = new ClovaSpeechClient().transcribe(wav,
                            settings.naverApiKeyId(), settings.naverApiKey());
                    lastTranscript = transcript;
                    handler.post(() -> {
                        if (!isFinishing() && !isDestroyed()) showConversation("들었어요: " + transcript);
                    });
                    handleTranscript(transcript);
                } catch (Exception e) {
                    postConversation(messageForVoiceError(e));
                } finally {
                    finishingRecording.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            finishingRecording.set(false);
            recorder.cancel();
            showConversation("음성을 처리할 수 없습니다. 다시 시도해 주세요.");
        }
    }

    private void cancelVoiceInput() {
        if (!recording.compareAndSet(true, false)) return;
        finishingRecording.set(true);
        handler.removeCallbacks(maxRecordingTime);
        recorder.stopCapture();
        if (microphoneButton != null) microphoneButton.setText("말하기");
        showConversation("음성 입력을 취소했어요.");
        try {
            worker.execute(() -> {
                try { recorder.cancel(); }
                finally { finishingRecording.set(false); }
            });
        } catch (RejectedExecutionException e) {
            recorder.cancel();
            finishingRecording.set(false);
        }
    }

    private void handleTranscript(String transcript) {
        if (conversation.expirePendingDestinationIfStale()) destinationRequest = "";
        if (transcript == null || transcript.trim().isEmpty()) {
            postConversation("말씀을 알아듣지 못했어요. 다시 말씀해 주세요.");
            speak("말씀을 알아듣지 못했어요. 다시 말씀해 주세요.");
            return;
        }
        if (conversation.state() == DestinationConversation.State.AWAITING_CONFIRMATION) {
            DestinationConversation.Reply reply = conversation.replyToConfirmation(transcript);
            if (reply == DestinationConversation.Reply.UNCLEAR && assistantKeysConfigured()) {
                PlaceCandidate pending = conversation.candidate();
                try {
                    String intent = new ClovaStudioClient(settings.clovaApiKey())
                            .classifyConfirmation(destinationRequest, pending, transcript);
                    if (!"unclear".equals(intent)) reply = conversation.applyAssistantConfirmation(intent);
                } catch (Exception e) {
                    sayAndShow("말씀을 판단하지 못했어요. 가려는 곳이면 네, 안내해 주세요라고 말씀해 주세요. 다른 곳이면 찾으시는 곳을 말씀해 주세요.");
                    return;
                }
            }
            if (reply == DestinationConversation.Reply.YES) {
                handler.post(this::openConfirmedTransitRoute);
            } else if (reply == DestinationConversation.Reply.NO) {
                destinationRequest = "";
                sayAndShow("알겠습니다. 가고 싶은 곳을 다시 말씀해 주세요.");
            } else if (reply == DestinationConversation.Reply.CORRECTION) {
                sayAndShow("알겠습니다. 말씀하신 새 목적지를 찾을게요.");
                searchDestination(transcript, destinationRequest);
            } else if (reply == DestinationConversation.Reply.ABORTED) {
                destinationRequest = "";
                sayAndShow("이번 목적지는 취소할게요. 다시 말씀해 주세요.");
            } else {
                sayAndShow("맞으면 네, 다른 곳이면 찾으시는 곳을 다시 말씀해 주세요.");
            }
            return;
        }
        if (conversation.state() == DestinationConversation.State.AWAITING_SELECTION) {
            String confirmation = conversation.selectByVoice(transcript);
            if (confirmation != null) {
                showPlaceConfirmation(conversation.candidate(), confirmation);
                return;
            }
            int matched = matchChoiceByName(transcript);
            if (matched >= 0) {
                List<PlaceCandidate> choices = conversation.choices();
                confirmation = conversation.confirm(choices.get(matched));
                showPlaceConfirmation(conversation.candidate(), confirmation);
                return;
            }
            // Natural corrections such as “전문의 있는 곳” refine the active destination
            // with HyperCLOVA and a fresh NAVER search instead of trapping the user in a menu.
            searchDestination(transcript, destinationRequest);
            return;
        }
        searchDestination(transcript);
    }

    private void searchDestination(String transcript) {
        searchDestination(transcript, destinationRequest);
    }

    private void searchDestination(String transcript, String previousRequest) {
        String context = previousRequest == null ? "" : previousRequest.trim();
        String utterance = transcript == null ? "" : transcript.trim();
        destinationRequest = DestinationContext.combine(context, utterance);
        String completeRequest = destinationRequest;
        conversation.beginSearch();
        invalidChoiceCount = 0;
        if (settings.clovaApiKey().trim().isEmpty()
                || settings.naverApiKeyId().trim().isEmpty()
                || settings.naverApiKey().trim().isEmpty()) {
            postConversation("설정에서 NAVER 음성·검색 키와 CLOVA Studio 키를 한 번 입력해 주세요.");
            speak("설정에서 네이버 음성 인식, 네이버 검색, 클로바 스튜디오 키를 입력해 주세요.");
            handler.post(this::showSettings);
            return;
        }
        postConversation("말씀하신 뜻을 이해하고 주변 장소를 찾고 있어요.");
        try {
            worker.execute(() -> {
                try {
                    ClovaStudioClient clova = new ClovaStudioClient(settings.clovaApiKey());
                    ClovaStudioClient.Interpretation interpreted = clova.interpret(context, utterance);
                    if (!"search".equals(interpreted.kind)) {
                        conversation.reset();
                        String message = interpreted.reply.isEmpty()
                                ? "어느 동네에서 어떤 장소를 찾을지 말씀해 주세요." : interpreted.reply;
                        postConversation(message);
                        speak(message);
                        return;
                    }
                    String preflightIssue = NaverPlaceRelevance.preflight(completeRequest, interpreted.searchQuery);
                    if (!preflightIssue.isEmpty()) {
                        conversation.reset();
                        if (!"area-required".equals(preflightIssue)) destinationRequest = "";
                        sayAndShow(messageForUnverifiedPlace(preflightIssue));
                        return;
                    }
                    NaverPlaceSearchClient search = new NaverPlaceSearchClient();
                    List<PlaceCandidate> places = search.search(interpreted.searchQuery,
                            settings.naverApiKeyId(), settings.naverApiKey());
                    if (places.isEmpty()) {
                        conversation.reset();
                        destinationRequest = "";
                        sayAndShow("말씀하신 조건에 맞는 장소를 찾지 못했어요. 동네와 장소를 다시 말씀해 주세요.");
                        return;
                    }
                    ClovaStudioClient.Ranking ranking = clova.rank(completeRequest, interpreted.searchQuery, places);
                    if ("confirm".equals(ranking.action)) {
                        String question = conversation.confirm(places.get(ranking.index));
                        showPlaceConfirmation(conversation.candidate(), question);
                    } else if ("choose".equals(ranking.action) && ranking.eligibleIndexes.size() > 1) {
                        List<PlaceCandidate> eligiblePlaces = new ArrayList<>();
                        for (int index : ranking.eligibleIndexes) eligiblePlaces.add(places.get(index));
                        List<String> options = conversation.offerChoices(eligiblePlaces);
                        String prompt = "비슷한 곳이 몇 군데 있어요. 번호나 장소 이름을 말씀해 주세요. "
                                + String.join(". ", options);
                        sayAndShow(prompt, prompt + "\n" + String.join("\n", conversation.choiceDetails()));
                    } else {
                        conversation.reset();
                        destinationRequest = "";
                        String message = messageForUnverifiedPlace(ranking.rejectionReason);
                        sayAndShow(message);
                    }
                } catch (Exception e) {
                    conversation.reset();
                    String message = messageForAssistantError(e);
                    postConversation(message);
                    speak(message);
                }
            });
        } catch (RejectedExecutionException e) {
            conversation.reset();
            sayAndShow("목적지를 찾을 수 없습니다. 잠시 후 다시 말씀해 주세요.");
        }
    }

    private boolean assistantKeysConfigured() {
        return !settings.clovaApiKey().trim().isEmpty()
                && !settings.naverApiKeyId().trim().isEmpty()
                && !settings.naverApiKey().trim().isEmpty();
    }

    private void openConfirmedTransitRoute() {
        PlaceCandidate destination = conversation.candidate();
        if (destination == null) {
            conversation.reset();
            sayAndShow("확인한 목적지가 없습니다. 다시 말씀해 주세요.");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, NaverMapLink.publicTransit(destination));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
            conversation.markHandedOff();
            destinationRequest = "";
            sayAndShow(destination.name + " 대중교통 경로를 네이버 지도에서 열었어요.");
        } catch (Exception e) {
            conversation.reset();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.nhn.android.nmap")));
                sayAndShow("네이버 지도를 설치해야 대중교통 경로를 열 수 있어요.");
            } catch (Exception ignored) {
                sayAndShow("네이버 지도 앱을 설치한 뒤 다시 이용해 주세요.");
            }
        }
    }

    private void openKakaoTaxi() {
        try {
            Intent app = getPackageManager().getLaunchIntentForPackage("com.kakao.taxi");
            if (app != null) startActivity(app);
            else startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.kakao.taxi")));
        } catch (Exception e) {
            showConversation("택시 앱을 열지 못했습니다.");
        }
    }

    private int matchChoiceByName(String transcript) {
        String normalized = transcript == null ? "" : transcript.replaceAll("\\s+", "");
        List<PlaceCandidate> items = conversation.choices();
        for (int i = 0; i < items.size(); i++) {
            if (normalized.contains(items.get(i).name.replaceAll("\\s+", ""))) return i;
        }
        return -1;
    }

    private String messageForVoiceError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "음성을 처리하지 못했습니다. 다시 시도해 주세요.";
        return message;
    }

    private String messageForAssistantError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "AI 또는 네이버 연결을 확인하고 다시 말씀해 주세요.";
        return message;
    }

    private void showConversation(String message) {
        if (conversationStatus == null) return;
        conversationStatus.setText(message);
        conversationStatus.setVisibility(View.VISIBLE);
    }

    private String showedConversation() {
        return conversationStatus == null ? "마이크를 확인해 주세요." : conversationStatus.getText().toString();
    }

    private void postConversation(String message) {
        handler.post(() -> {
            if (!isFinishing() && !isDestroyed()) showConversation(message);
        });
    }

    private void sayAndShow(String message) {
        sayAndShow(message, message);
    }

    private void sayAndShow(String spokenMessage, String visibleMessage) {
        postConversation(visibleMessage);
        speak(spokenMessage);
    }

    private void showPlaceConfirmation(PlaceCandidate candidate, String question) {
        if (candidate == null) {
            sayAndShow("장소를 확인하지 못했어요. 다시 말씀해 주세요.");
            return;
        }
        sayAndShow(question, candidate.confirmationDetails());
    }

    private String messageForUnverifiedPlace(String reason) {
        if ("area-required".equals(reason))
            return "가까운 곳을 찾으려면 동네가 필요해요. 어느 동네인지 말씀해 주세요.";
        if ("qualification-unverified".equals(reason))
            return "검색 정보만으로 전문의 자격을 확인할 수 없어 길 안내를 시작하지 않았어요. 다른 조건이나 병원 이름을 말씀해 주세요.";
        if ("category-not-verified".equals(reason))
            return "검색 결과에서 요청하신 진료과를 확인하지 못했어요. 다른 병원 이름이나 동네를 말씀해 주세요.";
        if ("area-not-matched".equals(reason))
            return "말씀하신 동네와 주소가 맞는 곳을 찾지 못했어요. 동네나 장소 이름을 다시 말씀해 주세요.";
        return "검색 결과에서 요청하신 조건과 맞는 곳을 확인하지 못했어요. 다른 동네나 장소 이름을 말씀해 주세요.";
    }

    private void speak(String message) {
        handler.post(() -> {
            if (speechReady && speech != null && !isFinishing() && !isDestroyed())
                speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ma-response");
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            sayAndShow("음성 대화를 하려면 마이크 권한이 필요해요. 권한을 허용한 뒤 다시 눌러 주세요.");
        } else if (requestCode == REQUEST_AUDIO) {
            showConversation("마이크 권한을 허용했어요. 버튼을 누른 채 말씀해 주세요.");
        }
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));

        EditText clova = field("클로바 AI 키", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clova.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(clova);
        EditText naverId = field("네이버 Key ID", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        naverId.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(naverId);
        EditText naverKey = field("네이버 API 키", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        naverKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(naverKey);
        CheckBox clearAssistantKeys = new CheckBox(this);
        clearAssistantKeys.setText("저장된 API 키 삭제");
        form.addView(clearAssistantKeys);
        TextView versionInfo = statusText();
        versionInfo.setText("현재 버전 " + installedVersionText());
        form.addView(versionInfo);
        TextView updateInfo = statusText();
        updateInfo.setText(updateStatus.getText());
        settingsUpdateStatus = updateInfo;
        form.addView(updateInfo);
        Button checkUpdate = actionButton("업데이트 확인");
        checkUpdate.setText("최신 버전 확인");
        checkUpdate.setOnClickListener(v -> {
            if (!settings.pendingUpdatePath().isEmpty()) {
                if (settings.pendingUpdateAwaitingPermission()
                        && Build.VERSION.SDK_INT >= 26
                        && !getPackageManager().canRequestPackageInstalls()) {
                    applyUpdate();
                    return;
                }
                settings.resetPendingUpdateInstallAttempted();
            }
            runUpdateCheck();
        });
        form.addView(checkUpdate);
        Button rollback = actionButton("이전 버전으로 복구");
        rollback.setOnClickListener(v -> showRollbackVersions());
        form.addView(rollback);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("설정").setView(scroll).setPositiveButton("저장", (dialog, which) -> {
            try {
                String nextClova = clearAssistantKeys.isChecked() ? "" : clova.getText().toString();
                String nextNaverId = clearAssistantKeys.isChecked() ? "" : naverId.getText().toString();
                String nextNaverKey = clearAssistantKeys.isChecked() ? "" : naverKey.getText().toString();
                if (!clearAssistantKeys.isChecked()) {
                    if (nextClova.isEmpty()) nextClova = settings.clovaApiKey();
                    if (nextNaverId.isEmpty()) nextNaverId = settings.naverApiKeyId();
                    if (nextNaverKey.isEmpty()) nextNaverKey = settings.naverApiKey();
                }
                settings.saveAssistant(nextClova, nextNaverId, nextNaverKey);
                runUpdateCheck();
            } catch (Exception e) {
                Toast.makeText(this, "설정을 저장하지 못했습니다. 입력값을 확인하세요.", Toast.LENGTH_LONG).show();
            }
        }).setNegativeButton("취소", null).show();
    }

    private void showRollbackVersions() {
        if (!moduleFailure.isEmpty()) return;
        showUpdate("복구 가능한 버전을 불러오는 중입니다.");
        try {
            worker.execute(() -> {
                AutoUpdateModule.RollbackCatalog catalog = null;
                String failure = null;
                try {
                    catalog = autoupdate.getRollbackCatalog();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                AutoUpdateModule.RollbackCatalog ready = catalog;
                String error = failure;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) {
                        showUpdate("복구 목록을 불러오지 못했습니다.");
                        return;
                    }
                    if (ready == null || ready.versions.isEmpty()) {
                        String stable = ready == null ? "" : formatVersion(ready.stableVersion, ready.stableVersionCode);
                        showUpdate(stable.isEmpty()
                                ? "복구할 버전이 아직 등록되지 않았습니다."
                                : "현재 배포 " + stable + " · 복구 버전이 아직 등록되지 않았습니다.");
                        return;
                    }
                    String[] labels = new String[ready.versions.size()];
                    for (int i = 0; i < labels.length; i++) {
                        AutoUpdateModule.RollbackVersion item = ready.versions.get(i);
                        labels[i] = item.versionName + " · 복구 설치 번호 " + item.installVersionCode;
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("복구할 버전 선택")
                            .setItems(labels, (dialog, which) -> startRollback(ready.versions.get(which)))
                            .setNegativeButton("취소", null)
                            .show();
                });
            });
        } catch (RejectedExecutionException e) {
            showUpdate("복구 목록을 불러오지 못했습니다.");
        }
    }

    private void startRollback(AutoUpdateModule.RollbackVersion version) {
        showUpdate(version.versionName + " 복구 파일을 확인하는 중입니다.");
        try {
            worker.execute(() -> {
                AutoUpdateModule.Result result = autoupdate.checkAndDownloadRollback(version);
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showUpdate(result.message);
                    if ("update-ready".equals(result.status)) applyUpdate();
                });
            });
        } catch (RejectedExecutionException e) {
            showUpdate("복구를 시작하지 못했습니다.");
        }
    }

    private EditText field(String hint, String value, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        input.setInputType(inputType);
        return input;
    }

    private String verifyModuleContract() {
        try (InputStream in = getAssets().open("app-modules.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            JSONObject manifest = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (!"autoupdate-app-modules-v1".equals(manifest.optString("contract"))
                    || !AppIdentity.APP_ID.equals(manifest.optString("app_id"))) {
                return "앱 설정 ID가 맞지 않습니다.";
            }
            JSONArray modules = manifest.getJSONArray("modules");
            boolean update = false;
            boolean sync = false;
            for (int i = 0; i < modules.length(); i++) {
                JSONObject item = modules.getJSONObject(i);
                String id = item.optString("module_id");
                int api = item.optInt("api_major");
                if ("AutoUpdate".equals(id) && api == AutoUpdateModule.API_MAJOR) update = true;
                if ("AutoSync".equals(id) && api == AutoSyncModule.API_MAJOR) sync = true;
            }
            return update && sync ? "" : "AutoUpdate/AutoSync API 1 설정이 필요합니다.";
        } catch (Exception e) {
            return "앱 모듈 manifest를 읽지 못했습니다.";
        }
    }

    private void showSync(String text) {
        if (syncStatus != null) syncStatus.setText(text);
        if (settingsSyncStatus != null) settingsSyncStatus.setText(text);
    }

    private void showUpdate(String text) {
        if (updateStatus != null) updateStatus.setText(text);
        if (settingsUpdateStatus != null) settingsUpdateStatus.setText(text);
    }

    private String installedVersionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return formatVersion(info.versionName, code);
        } catch (Exception e) {
            return "설치 버전을 확인할 수 없습니다.";
        }
    }

    private long installedVersionCodeForUi() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String formatVersion(String name, long code) {
        return (name == null || name.trim().isEmpty() ? "버전" : name.trim()) + " (" + code + ")";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
