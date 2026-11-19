package com.siny.mobilebasic;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.siny.mobilebasic.modules.AutoSyncModule;
import com.siny.mobilebasic.modules.AutoUpdateModule;
import com.siny.mobilebasic.modules.MobileJobs;
import com.siny.mobilebasic.modules.SecureSettings;
import com.siny.mobilebasic.storage.MobileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Voice-first MA. Destinations remain local; confirmed public-transit routes go to NAVER Maps and confirmed taxi trips go to Kakao T. */
public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 4101;
    private static final long SPECIALIST_CONSENT_TTL_MS = 2 * 60 * 1000L;
    private static final long UI_APPROVAL_TURN_SEQUENCE = -1L;
    private static final long MIN_CANDIDATE_CONFIRMATION_WINDOW_MS =
            DestinationCandidateContext.MIN_INITIAL_PRESENTATION_REMAINING_MS;
    private static final long MIN_CANDIDATE_HANDOFF_WINDOW_MS =
            DestinationCandidateContext.MIN_HANDOFF_REMAINING_MS;
    private static final String[] CLINICAL_TYPES = {
            "정신건강의학과", "재활의학과", "정형외과", "산부인과", "이비인후과", "비뇨기과",
            "가정의학과", "피부과", "신경과", "내과", "외과", "안과", "치과", "소아과", "한의원"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ma-worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService resolverWorker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ma-place-resolver");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicBoolean updateRunning = new AtomicBoolean(false);

    private MobileStore store;
    private SecureSettings settings;
    private AutoSyncModule autosync;
    private AutoUpdateModule autoupdate;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private GeminiLiveVoiceSession liveVoiceSession;
    private long liveVoiceSessionGeneration;
    private final LiveNavigationGate liveNavigationGate = new LiveNavigationGate();
    private final LinkedHashMap<String, Candidate> liveCandidates = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> liveCandidatePresentationTurns = new LinkedHashMap<>();
    private final LinkedHashMap<String, PendingLiveToolReply> liveToolTtsReplies = new LinkedHashMap<>();
    private final LinkedHashSet<String> liveAwaitingAssistantPresentation = new LinkedHashSet<>();
    private long liveCandidateContextGeneration = -1L;
    private SpeechPurpose activeSpeechPurpose;
    private LinearLayout content;
    private ScrollView rootScroll;
    private TextView recognizedText;
    private TextView destinationText;
    private TextView statusText;
    private TextView syncStatus;
    private TextView updateStatus;
    private TextView updateSetupStatus;
    private AlertDialog updateSetupDialog;
    private AlertDialog candidateChoiceDialog;
    private TextView settingsButton;
    private ObjectAnimator settingsPulse;
    private LinearLayout speakButton;
    private ImageButton taxiButton;
    private TravelMode travelMode = TravelMode.TRANSIT;
    private Button confirmButton;
    private Button cancelButton;
    private Button updateButton;
    private boolean ttsReady;
    private final ConfirmationPromptGate confirmationPromptGate = new ConfirmationPromptGate();
    private String confirmationPromptText = "";
    private long speechUtteranceSequence;
    private boolean speechActive;
    private boolean waitingForMapReturn;
    private boolean waitingForTaxiReturn;
    private boolean taxiDestinationCopied;
    private boolean awaitingUpdateInstallPermission;
    private boolean updateInstallPermissionBlocked;
    private boolean waitingForGuideAccessibilitySettings;
    private boolean waitingForTaxiAccessibilitySettings;
    private SpeechPurpose permissionSpeechPurpose = SpeechPurpose.DESTINATION;
    private boolean permissionPreserveClarificationContext;
    private SpeechPurpose queuedMicPurpose;
    private boolean queuedMicPreserveClarificationContext;
    private boolean microphonePressed;
    private boolean microphonePressAccepted;
    private boolean liveToolBusyNoticeSpoken;
    private int confirmationListenAttempts;
    private int destinationSpeechRetryCount;
    private final DestinationSpeechClarificationGate destinationHypothesisRepeatGate =
            new DestinationSpeechClarificationGate();
    private final DestinationSpeechClarificationGate destinationAreaOnlyPromptGate =
            new DestinationSpeechClarificationGate();
    private int destinationClarificationAttempts;
    private int destinationSearchFailureCount;
    private boolean activeSearchFromTextEntry;
    private long activeSearchId;
    private long specialistConditionRequestId = -1L;
    private long speechCaptureSequence;
    private long candidateGeneration;
    private long pendingCandidateExpiresAtElapsedRealtime;
    private long pendingCandidateContextGeneration = -1L;
    private String moduleFailure = "";
    private final DestinationClarificationLease destinationClarificationLease =
            new DestinationClarificationLease();
    private final DestinationCorrectionContext destinationCorrectionContext =
            new DestinationCorrectionContext();
    private boolean hasUnverifiedConditionsForCurrentFlow;
    private boolean hasUngroundedAiConditionForCurrentFlow;
    private boolean usedLiteralAiFallbackForCurrentFlow;
    private String literalFallbackContextNoticeForCurrentFlow = "";
    private final java.util.LinkedHashMap<String, ConditionState> activeConditionDetailsForCurrentFlow =
            new java.util.LinkedHashMap<>();
    private final LinkedHashSet<String> cancelledConditionTopicsForCurrentFlow = new LinkedHashSet<>();
    private final LinkedHashSet<String> recentlyCancelledConditionTopicsForCurrentFlow =
            new LinkedHashSet<>();
    private final PendingActionGate pendingActionGate = new PendingActionGate();
    private final ConditionAnswerRoundTracker conditionAnswerRounds =
            new ConditionAnswerRoundTracker();
    private final DestinationDistrictClarification districtClarification =
            new DestinationDistrictClarification();
    private final RepeatedAmbiguousDestinationGuard repeatedAmbiguousDestination =
            new RepeatedAmbiguousDestinationGuard();
    private final ArrayList<Candidate> rememberedAmbiguousDestinationCandidates = new ArrayList<>();
    private boolean awaitingDistrictClarification;
    private boolean awaitingRepeatedDistrictClarification;
    private final ArrayList<String> pendingBulkConditionRemovalTopics = new ArrayList<>();
    private final LinkedHashSet<String> pendingBulkCascadeRemovedTopics = new LinkedHashSet<>();
    private final LinkedHashSet<String> pendingBulkSkippedByCascade = new LinkedHashSet<>();
    private final LinkedHashSet<String> pendingBulkSkippedWhileInactive = new LinkedHashSet<>();
    private long topicSelectionRoundCounter;
    private String activeTopicSelectionScope = "";
    private String requestedAreaConstraintForCurrentFlow = "";
    private boolean someSearchResultsExcludedForCurrentFlow;
    private int excludedByAreaForCurrentFlow;
    private int excludedByCategoryForCurrentFlow;
    private boolean searchUsedNoExplicitAreaForCurrentFlow;
    private int regionConflictAttempts;
    private String pendingSpecialistSearchQuery = "";
    private String pendingSpecialistConsentPrompt = "";
    private String pendingSpecialistConsentButtonText = "";
    private long pendingSpecialistSearchRequestId = -1L;
    private long pendingSpecialistConsentExpiresAtElapsedRealtime;
    private Runnable pendingSpecialistConsentExpiryRunnable;
    private int specialistConsentAttempts;
    private boolean awaitingSpecialistVerification;
    private boolean specialistConditionForCurrentFlow;
    private boolean specialistStatusUnverifiedForCandidate;
    private Candidate pendingCandidate;
    private ArrayList<Candidate> pendingCandidateChoices;
    private ArrayList<Candidate> voiceCandidateChoices;
    private ArrayList<Candidate> confirmationCandidateChoices;
    private final DestinationCandidateContext destinationCandidateContext =
            new DestinationCandidateContext();
    private Runnable destinationCandidateExpiryTask;
    private Runnable candidateListSpeechTimeoutTask;
    private String candidateListSpeechUtteranceId = "";
    private long candidateListSpeechGeneration = -1L;
    private int candidateSelectionAttempts;

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
        settings.setUpdateManifestUrlIfEmpty(BuildConfig.LOCAL_UPDATE_MANIFEST_URL);
        settings.setGithubUpdateRepositoryIfEmpty(BuildConfig.GITHUB_UPDATE_REPOSITORY);
        settings.migrateGithubUpdateRepository("siny-dev/ma-updates", BuildConfig.GITHUB_UPDATE_REPOSITORY);
        store = new MobileStore(this);
        autosync = new AutoSyncModule(this, store, settings);
        autoupdate = new AutoUpdateModule(this, settings);
        moduleFailure = verifyModuleContract();
        MobileJobs.schedule(this);
        buildUi();
        applyDisplayInsets();
        initializeTts();
        if (!moduleFailure.isEmpty()) {
            showSync("모듈 설정을 확인하세요 · " + moduleFailure);
        } else {
            showSync("AutoSync · 로컬 우선");
            showUpdate("AutoUpdate · 앱별 manifest 설정 필요");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean returningFromExternalFlow = waitingForGuideAccessibilitySettings
                || waitingForTaxiAccessibilitySettings || waitingForMapReturn || waitingForTaxiReturn;
        if (!waitingForGuideAccessibilitySettings && !waitingForTaxiAccessibilitySettings
                && !waitingForMapReturn && !waitingForTaxiReturn
                && (pendingCandidate != null || pendingCandidateChoices != null)) {
            long now = SystemClock.elapsedRealtime();
            // The list is already on screen when the activity resumes. Starting a new
            // narration/list presentation needs the larger list budget, but simply
            // returning to an existing result only needs enough time for confirmation.
            long minimumRemaining = MIN_CANDIDATE_CONFIRMATION_WINDOW_MS;
            long expectedResultGeneration = pendingCandidateContextGeneration >= 0L
                    ? pendingCandidateContextGeneration : destinationCandidateContext.generation();
            if (destinationCandidateContext.hasExpired(now)) {
                expireDestinationCandidateContextIfNeeded();
            } else if (!destinationCandidateContext.hasMinimumRemaining(
                    expectedResultGeneration, now, minimumRemaining)) {
                discardDestinationCandidateContext("검색 결과가 오래되어요. 목적지를 다시 말씀해 주세요.");
            }
        }
        if (waitingForGuideAccessibilitySettings) {
            waitingForGuideAccessibilitySettings = false;
            Candidate candidate = pendingCandidate;
            if (candidate != null && isNaverGuideServiceEnabled()) {
                openConfirmedRoute(candidate, candidateGeneration, UI_APPROVAL_TURN_SEQUENCE);
            } else if (candidate != null && !isCurrentCandidate(candidate, candidateGeneration)) {
                if (!ensureDestinationCandidateContextCurrent()) return;
                clearPendingCandidate();
                destinationCorrectionContext.clear();
                confirmButton.setVisibility(View.GONE);
                showStatus("확인 시간이 지났어요. 목적지를 다시 찾아 주세요.", true);
            } else {
                showStatus("MA 네이버 안내 보조 기능이 켜지지 않았어요. 경로 확인 버튼을 다시 눌러 설정할 수 있어요.", true);
            }
        } else if (waitingForTaxiAccessibilitySettings) {
            waitingForTaxiAccessibilitySettings = false;
            Candidate candidate = pendingCandidate;
            if (candidate != null && !isCurrentCandidate(candidate, candidateGeneration)) {
                if (!ensureDestinationCandidateContextCurrent()) return;
                clearPendingCandidate();
                destinationCorrectionContext.clear();
                showStatus("목적지 확인 시간이 지났어요. 다시 찾아 주세요.", true);
                return;
            }
            if (candidate != null) launchTaxiHandoff(candidate, isKakaoTaxiServiceEnabled());
        } else if (waitingForTaxiReturn) {
            waitingForTaxiReturn = false;
            boolean taxiAutomationArmed = KakaoTaxiAutomation.isArmed(this);
            String result = KakaoTaxiAutomation.result(this);
            if ("waiting".equals(result)) {
                KakaoTaxiAutomation.finish(this, "returned_without_destination");
                result = "returned_without_destination";
            }
            if (taxiAutomationArmed
                    && KakaoTaxiAutomation.STAGE_REQUEST_TAXI.equals(KakaoTaxiAutomation.stage(this))) {
                KakaoTaxiAutomation.finish(this, "call_button_not_found");
                result = "call_button_not_found";
            }
            if ("taxi_request_tapped".equals(result)) {
                showStatus("카카오 T의 호출 버튼을 눌렀어요. 실제 배차가 완료됐는지는 카카오 T 화면에서 확인해 주세요.", true);
            } else if ("call_button_not_found".equals(result)) {
                showStatus("카카오 T에서 호출 버튼을 찾지 못했어요. 목적지와 요금을 확인한 뒤 카카오 T에서 직접 호출해 주세요.", true);
            } else if ("expired".equals(result) || "interrupted".equals(result)) {
                showStatus("안전 시간 안에 택시 호출을 마치지 못해 자동 조작을 끝냈어요. 카카오 T에서 목적지를 확인하고 직접 호출해 주세요.", true);
            } else if ("destination_entered".equals(result)) {
                showStatus("목적지를 카카오 T에 입력했어요. 검색 결과와 출발지·요금을 확인하고 택시 호출을 눌러주세요.", true);
            } else if (taxiDestinationCopied) {
                showStatus("카카오 T에 주소를 복사해 두었어요. 목적지 칸에 붙여넣고 출발지와 요금을 확인한 뒤 호출해 주세요.", true);
            } else {
                showStatus("카카오 T에서 목적지를 직접 입력하고 출발지와 요금을 확인한 뒤 호출해 주세요.", true);
            }
            taxiDestinationCopied = false;
            KakaoTaxiAutomation.clearResult(this);
            clearPendingCandidate();
            destinationCorrectionContext.clear();
        } else if (waitingForMapReturn) {
            waitingForMapReturn = false;
            String result = NaverGuideAutomation.result(this);
            if ("waiting".equals(result)) {
                NaverGuideAutomation.disarm(this, "returned_without_start");
                result = "returned_without_start";
            }
            if ("guidance_active".equals(result)) {
                showStatus("네이버 지도에서 안내가 진행 중인 화면을 확인했어요.", true);
            } else if ("start_tap_sent".equals(result)) {
                showStatus("네이버 지도에 안내 시작 버튼을 눌렀어요. 안내 화면이 진행 중인지 확인해 주세요.", true);
            } else if ("manual_start_tapped".equals(result)) {
                showStatus("네이버 지도에서 ‘안내 시작’을 눌렀어요. 안내가 진행 중인지 확인해 주세요.", true);
            } else {
                showStatus("네이버 지도에서 경로와 안내 상태를 확인해 주세요. 안내가 시작되지 않았다면 ‘안내 시작’을 눌러주세요.", true);
            }
            NaverGuideAutomation.clearResult(this);
            // A confirmed destination is a one-shot handoff. Returning from NAVER must not
            // leave the old approval available to the microphone or confirmation button.
            clearPendingCandidate();
        }
        if (awaitingUpdateInstallPermission) {
            awaitingUpdateInstallPermission = false;
            if (Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
                updateInstallPermissionBlocked = false;
                applyUpdate(false);
            } else {
                updateInstallPermissionBlocked = true;
                showUpdate("AutoUpdate · Android 설치 권한을 허용하면 업데이트를 적용합니다.");
                updateButton.setVisibility(View.VISIBLE);
            }
        }
        if (moduleFailure.isEmpty()) {
            handler.removeCallbacks(syncTick);
            handler.post(syncTick);
            handler.removeCallbacks(updateTick);
            runUpdateCheck();
            handler.postDelayed(updateTick, AutoUpdateModule.FOREGROUND_INTERVAL_MS);
        }
        if (!returningFromExternalFlow) preconnectLiveVoiceSession();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(updateTick);
        microphonePressed = false;
        microphonePressAccepted = false;
        cancelPendingAutoListen();
        closeLiveVoiceSession();
        if (speechRecognizer != null) invalidateSpeechCapture();
        super.onPause();
    }

    @Override protected void onDestroy() {
        clearDestinationCandidateContext();
        handler.removeCallbacksAndMessages(null);
        closeLiveVoiceSession();
        invalidateSpeechCapture();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        worker.shutdown();
        resolverWorker.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        rootScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xffffffff);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(18));
        content.setBackgroundColor(0xffffffff);

        FrameLayout header = new FrameLayout(this);
        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams titleGroupParams = new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleGroupParams.topMargin = dp(16);
        header.addView(titleGroup, titleGroupParams);

        TextView title = new TextView(this);
        title.setText("MA");
        title.setTextSize(44);
        title.setTextColor(0xff182f33);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        titleGroup.addView(title, fullWidth());

        TextView subtitle = new TextView(this);
        subtitle.setText("Mother's Assistant");
        subtitle.setTextSize(18);
        subtitle.setTextColor(0xff74838a);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(3), 0, 0);
        titleGroup.addView(subtitle, fullWidth());

        settingsButton = new TextView(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(34);
        settingsButton.setTextColor(0xff718087);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setContentDescription("설정");
        settingsButton.setClickable(true);
        settingsButton.setFocusable(true);
        settingsButton.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.TOP | Gravity.RIGHT);
        settingsParams.topMargin = dp(12);
        settingsParams.rightMargin = dp(2);
        header.addView(settingsButton, settingsParams);
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(132)));

        View upperSpace = new View(this);
        content.addView(upperSpace, new LinearLayout.LayoutParams(1, 0, 0.47f));

        speakButton = new LinearLayout(this);
        speakButton.setOrientation(LinearLayout.HORIZONTAL);
        speakButton.setGravity(Gravity.CENTER);
        speakButton.setPadding(dp(22), 0, dp(24), 0);
        speakButton.setBackground(roundedBackground(0xff07877c, 40));
        speakButton.setElevation(dp(3));
        speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
        speakButton.setClickable(true);
        speakButton.setFocusable(true);
        ImageView microphoneIcon = new ImageView(this);
        microphoneIcon.setImageResource(R.drawable.ic_microphone);
        microphoneIcon.setColorFilter(0xffffffff);
        microphoneIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams microphoneParams = new LinearLayout.LayoutParams(dp(30), dp(34));
        microphoneParams.rightMargin = dp(12);
        speakButton.addView(microphoneIcon, microphoneParams);
        TextView speakLabel = new TextView(this);
        speakLabel.setText("말하기");
        speakLabel.setTextSize(24);
        speakLabel.setTextColor(0xffffffff);
        speakLabel.setTypeface(null, Typeface.BOLD);
        speakButton.addView(speakLabel, new LinearLayout.LayoutParams(-2, -2));
        speakButton.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (microphonePressAccepted) return true;
                if (hasPendingLiveAppSpeech()) {
                    showStatus("안내가 끝난 뒤 다시 눌러 말씀해 주세요.", false);
                    return true;
                }
                if (liveVoiceSession != null && liveVoiceSession.isInputBlocked()) {
                    showStatus("AI가 장소를 확인하고 있어요. 잠시만 기다려 주세요.", false);
                    if (!liveToolBusyNoticeSpoken && (tts == null || !tts.isSpeaking())) {
                        liveToolBusyNoticeSpoken = true;
                        speakLiveNotice("장소를 확인하고 있어요. 끝나면 다시 말씀해 주세요.");
                    }
                    return true;
                }
                microphonePressAccepted = true;
                microphonePressed = true;
                speakButton.setScaleX(0.98f);
                speakButton.setScaleY(0.98f);
                if (tts != null && tts.isSpeaking()) tts.stop();
                beginSpeech();
                microphonePressAccepted = microphonePressed;
                if (!microphonePressAccepted) {
                    speakButton.setScaleX(1f);
                    speakButton.setScaleY(1f);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                boolean accepted = microphonePressAccepted;
                microphonePressAccepted = false;
                if (accepted) microphonePressed = false;
                speakButton.setScaleX(1f);
                speakButton.setScaleY(1f);
                if (accepted) {
                    stopSpeechCapture();
                    view.performClick();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                boolean accepted = microphonePressAccepted;
                microphonePressAccepted = false;
                if (accepted) microphonePressed = false;
                speakButton.setScaleX(1f);
                speakButton.setScaleY(1f);
                if (accepted) {
                    if (liveVoiceSession != null) {
                        liveVoiceSession.cancelUserTurn();
                        // Manual Live VAD requires a closing activityEnd. A canceled partial
                        // utterance is discarded by closing the session and its turn context.
                        closeLiveVoiceSession();
                    }
                    if (speechRecognizer != null) invalidateSpeechCapture();
                    clearPendingConditionFlow();
                    showStatus("음성 입력을 취소했어요.", false);
                }
                return true;
            }
            return true;
        });
        taxiButton = new ImageButton(this);
        taxiButton.setImageResource(R.drawable.ic_taxi);
        taxiButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        taxiButton.setColorFilter(0xffffffff);
        taxiButton.setPadding(dp(5), dp(5), dp(5), dp(5));
        taxiButton.setBackground(circleBackground(0xff77848b));
        taxiButton.setElevation(dp(2));
        taxiButton.setContentDescription("택시 목적지 말하고 카카오 T 열기");
        taxiButton.setOnClickListener(v -> beginTaxiFlow());

        FrameLayout modeArea = new FrameLayout(this);
        modeArea.setMinimumHeight(dp(86));
        FrameLayout.LayoutParams micParams = new FrameLayout.LayoutParams(dp(166), dp(62), Gravity.CENTER);
        modeArea.addView(speakButton, micParams);
        content.addView(modeArea, fullWidth());
        View lowerSpace = new View(this);
        content.addView(lowerSpace, new LinearLayout.LayoutParams(1, 0, 1f));
        updateTravelModeUi();

        recognizedText = new TextView(this);
        recognizedText.setText("");
        recognizedText.setTextSize(20);
        recognizedText.setTextColor(0xff303b49);
        recognizedText.setPadding(dp(4), dp(12), dp(4), dp(8));
        recognizedText.setVisibility(View.GONE);
        content.addView(recognizedText);

        destinationText = new TextView(this);
        destinationText.setText("");
        destinationText.setTextSize(22);
        destinationText.setTextColor(0xff173b63);
        destinationText.setTypeface(null, Typeface.BOLD);
        destinationText.setPadding(dp(4), dp(5), dp(4), dp(8));
        destinationText.setVisibility(View.GONE);
        content.addView(destinationText);

        statusText = new TextView(this);
        statusText.setText("");
        statusText.setTextSize(17);
        statusText.setTextColor(0xff465568);
        statusText.setPadding(dp(4), dp(5), dp(4), dp(14));
        statusText.setVisibility(View.GONE);
        content.addView(statusText);

        confirmButton = button("네, 대중교통 경로 열기", 20, 72);
        confirmButton.setVisibility(View.GONE);
        confirmButton.setOnClickListener(v -> openConfirmedRoute());
        content.addView(confirmButton, fullWidth());

        cancelButton = button("아니요, 처음으로", 18, 64);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> resetFlow("취소했어요. 다시 말씀하셔도 돼요."));
        LinearLayout.LayoutParams cancelParams = fullWidth();
        cancelParams.topMargin = dp(8);
        content.addView(cancelButton, cancelParams);

        TextView privacy = new TextView(this);
        privacy.setText("AI 해석 설정을 사용하면 들은 목적지 표현이 인터넷으로 전송될 수 있어요. MA는 목적지를 AutoSync에 보내지 않습니다.");
        privacy.setTextSize(13);
        privacy.setTextColor(0xff647184);
        privacy.setPadding(dp(4), dp(18), dp(4), dp(8));
        privacy.setVisibility(View.GONE);
        content.addView(privacy);

        View divider = new View(this);
        divider.setBackgroundColor(0xffd9dee5);
        divider.setVisibility(View.GONE);
        content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        syncStatus = smallStatus("AutoSync · 로컬 우선");
        syncStatus.setVisibility(View.GONE);
        content.addView(syncStatus);
        updateStatus = smallStatus("AutoUpdate · 설정 필요");
        updateStatus.setVisibility(View.GONE);
        content.addView(updateStatus);
        updateButton = button("업데이트 적용", 15, 52);
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener(v -> applyUpdate());
        content.addView(updateButton, fullWidth());

        LinearLayout taxiFooter = new LinearLayout(this);
        taxiFooter.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        taxiFooter.setPadding(0, dp(2), dp(2), dp(2));
        taxiFooter.addView(taxiButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        content.addView(taxiFooter, new LinearLayout.LayoutParams(-1, dp(50)));

        scroll.addView(content);
        setContentView(scroll);
        getWindow().setStatusBarColor(0xffffffff);
        getWindow().setNavigationBarColor(0xffffffff);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void updateTravelModeUi() {
        if (speakButton != null) {
            speakButton.setBackground(roundedBackground(0xff07877c, 40));
        }
        if (taxiButton != null) {
            taxiButton.setBackground(circleBackground(travelMode == TravelMode.TAXI
                    ? 0xff8f5d00 : 0xff77848b));
        }
    }

    private void selectTravelMode(TravelMode mode) {
        travelMode = mode;
        updateTravelModeUi();
    }

    private void beginTaxiFlow() {
        if (speechActive) {
            resetFlow("음성 입력을 취소했어요.");
            selectTravelMode(TravelMode.TAXI);
            showStatus("택시 목적지를 입력하려면 마이크를 눌러주세요.", true);
            return;
        }
        if (!pendingSpecialistSearchQuery.trim().isEmpty()) {
            cancelPendingAutoListen();
            clearPendingSpecialistConsent();
            destinationClarificationLease.clear();
            awaitingSpecialistVerification = false;
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        }
        if (pendingCandidate != null) {
            selectTravelMode(TravelMode.TAXI);
            showCandidate(pendingCandidate);
            return;
        }
        selectTravelMode(TravelMode.TAXI);
        showStatus("택시 모드예요. 마이크를 누르고 있는 동안 AI에게 말씀해 주세요.", true);
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void applyDisplayInsets() {
        if (Build.VERSION.SDK_INT < 35 || rootScroll == null) return;
        rootScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom);
            return insets;
        });
        rootScroll.requestApplyInsets();
    }

    private TextView smallStatus(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xff657183);
        view.setPadding(dp(4), dp(8), dp(4), 0);
        return view;
    }

    private Button button(String text, int sizeSp, int minHeightDp) {
        Button value = new Button(this);
        value.setText(text);
        value.setTextSize(sizeSp);
        value.setAllCaps(false);
        value.setMinHeight(dp(minHeightDp));
        value.setMinimumHeight(dp(minHeightDp));
        value.setPadding(dp(12), dp(8), dp(12), dp(8));
        return value;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private void initializeTts() {
        tts = new TextToSpeech(getApplicationContext(), result -> {
            if (result == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(new Locale("ko", "KR"));
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override public void onDone(String utteranceId) {
                finishCandidateListSpeech(utteranceId);
                finishConfirmationPrompt(utteranceId, true);
                finishLiveToolTts(utteranceId, true);
            }

            @Override public void onError(String utteranceId) {
                finishCandidateListSpeech(utteranceId);
                finishConfirmationPrompt(utteranceId, false);
                finishLiveToolTts(utteranceId, false);
            }

            @Override public void onStop(String utteranceId, boolean interrupted) {
                finishCandidateListSpeech(utteranceId);
                finishConfirmationPrompt(utteranceId, false);
                finishLiveToolTts(utteranceId, false);
            }
        });
    }

    private void beginGeminiLiveTurn() {
        if (!microphonePressed || isFinishing() || isDestroyed()) return;
        if (settings == null) return;
        if (!hasMicrophonePermission()) {
            permissionSpeechPurpose = SpeechPurpose.DESTINATION;
            permissionPreserveClarificationContext = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            showStatus("Gemini AI와 대화하려면 마이크 권한이 필요해요.", false);
            return;
        }
        String apiKey;
        try {
            apiKey = settings.geminiApiKey();
        } catch (RuntimeException e) {
            showStatus("저장된 Gemini API 키를 읽지 못했어요. 설정에서 다시 저장해 주세요.", true);
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            showStatus("Gemini Live API 키를 설정해 주세요.", true);
            showAdvancedSettings();
            return;
        }
        if (!ensureLiveVoiceSession(apiKey)) return;
        GeminiLiveVoiceSession session = liveVoiceSession;
        boolean started = session != null && session.startUserTurn(liveNavigationGate::beginUserTurn);
        if (!started) {
            microphonePressed = false;
            microphonePressAccepted = false;
            showStatus("AI가 장소를 확인하고 있어요. 끝나면 다시 눌러 말씀해 주세요.", false);
            return;
        }
        showStatus("Gemini AI와 연결해 음성을 듣고 있어요.", false);
        speakButton.setContentDescription("Gemini AI와 대화 중입니다. 손을 떼면 음성을 마칩니다.");
    }

    private void preconnectLiveVoiceSession() {
        if (settings == null || liveVoiceSession != null || !hasMicrophonePermission()
                || isFinishing() || isDestroyed()) return;
        try {
            String apiKey = settings.geminiApiKey();
            if (apiKey != null && !apiKey.trim().isEmpty()) ensureLiveVoiceSession(apiKey);
        } catch (RuntimeException ignored) {
            // Keep launch quiet. The microphone action will show a recoverable settings message.
        }
    }

    private boolean ensureLiveVoiceSession(String apiKey) {
        if (liveVoiceSession != null) return true;
        final long callbackGeneration = ++liveVoiceSessionGeneration;
        try {
            liveVoiceSession = new GeminiLiveVoiceSession(apiKey,
                    new GeminiLiveVoiceSession.Listener() {
                        @Override public void onReady() {
                            handler.post(() -> {
                                if (callbackGeneration == liveVoiceSessionGeneration
                                        && !isFinishing() && !isDestroyed()) {
                                    showStatus(microphonePressed
                                            ? "AI가 듣고 있어요. 손을 떼면 말하기를 마쳐요."
                                            : "AI 연결이 준비됐어요. 마이크를 누르고 말씀해 주세요.", false);
                                }
                            });
                        }

                        @Override public void onStatus(String status) {
                            handler.post(() -> {
                                if (callbackGeneration == liveVoiceSessionGeneration
                                        && !isFinishing() && !isDestroyed()) showStatus(status, false);
                            });
                        }

                        @Override public void onFailure(String message) {
                            handler.post(() -> {
                                if (callbackGeneration != liveVoiceSessionGeneration
                                        || isFinishing() || isDestroyed()) return;
                                boolean cancelToolSpeech = hasPendingLiveAppSpeech();
                                closeLiveVoiceSession();
                                if (cancelToolSpeech && tts != null) {
                                    tts.stop();
                                    speakLiveNotice("연결이 끊겨 안내를 끝까지 듣지 못했어요. 지역과 장소를 다시 말씀해 주세요.");
                                }
                                showStatus(message, true);
                            });
                        }

                        @Override public void onToolCalls(JSONArray calls, long toolUserTurn) {
                            handler.post(() -> {
                                if (callbackGeneration == liveVoiceSessionGeneration) {
                                    handleLiveToolCalls(calls, toolUserTurn);
                                }
                            });
                        }

                        @Override public void onAssistantTurnComplete(boolean includedAudio,
                                                                     String transcript, long playbackToken) {
                            handler.post(() -> {
                                if (callbackGeneration != liveVoiceSessionGeneration) return;
                                GeminiLiveVoiceSession current = liveVoiceSession;
                                if (current != null && !current.isInputBlocked()) {
                                    liveToolBusyNoticeSpoken = false;
                                }
                            });
                        }

                        @Override public void onAssistantInterrupted() {
                            handler.post(() -> {
                                if (callbackGeneration == liveVoiceSessionGeneration) {
                                    showStatus("말씀을 듣고 있어요.", false);
                                }
                            });
                        }

                        @Override public void onAssistantTranscript(String transcript) {
                            handler.post(() -> {
                                if (callbackGeneration == liveVoiceSessionGeneration
                                        && !isFinishing() && !isDestroyed() && recognizedText != null) {
                                    recognizedText.setText("AI: " + transcript);
                                }
                            });
                        }

                        @Override public void onUserTurnAutoEnded() {
                            handler.post(() -> {
                                if (callbackGeneration != liveVoiceSessionGeneration
                                        || isFinishing() || isDestroyed()) return;
                                microphonePressed = false;
                                microphonePressAccepted = false;
                                if (speakButton != null) {
                                    speakButton.setScaleX(1f);
                                    speakButton.setScaleY(1f);
                                }
                                showStatus("말씀이 길어 듣기를 멈췄어요. 손을 뗀 뒤 다시 눌러 말씀해 주세요.", false);
                            });
                        }
                    });
            return true;
        } catch (RuntimeException e) {
            liveVoiceSession = null;
            if (microphonePressed) {
                showStatus("Gemini Live 음성 대화를 준비하지 못했어요. API 키 설정을 확인해 주세요.", true);
            }
            return false;
        }
    }

    private void closeLiveVoiceSession() {
        GeminiLiveVoiceSession active = liveVoiceSession;
        liveVoiceSession = null;
        liveVoiceSessionGeneration++;
        if (active != null) active.close();
        liveNavigationGate.reset();
        liveCandidates.clear();
        liveCandidatePresentationTurns.clear();
        liveToolTtsReplies.clear();
        liveAwaitingAssistantPresentation.clear();
        liveCandidateContextGeneration = -1L;
    }

    private void beginSpeech() {
        beginGeminiLiveTurn();
    }

    private void beginSpeech(SpeechPurpose purpose) {
        beginSpeech(purpose, false);
    }

    private void beginSpeech(SpeechPurpose purpose, boolean preserveClarificationContext) {
        beginGeminiLiveTurn();
    }

    private boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldUseScreenOnlyInteraction() {
        return activeSearchFromTextEntry || !hasMicrophonePermission()
                || !SpeechRecognizer.isRecognitionAvailable(this);
    }

    private boolean isCurrentSpeechCapture(SpeechRecognizer recognizer, long captureSequence) {
        return speechActive && speechRecognizer == recognizer
                && speechCaptureSequence == captureSequence;
    }

    private boolean finishSpeechCapture(SpeechRecognizer recognizer, long captureSequence) {
        if (!isCurrentSpeechCapture(recognizer, captureSequence)) return false;
        speechActive = false;
        speechRecognizer = null;
        activeSpeechPurpose = null;
        try {
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // The callback has already completed; a late callback is rejected by instance/sequence.
        }
        return true;
    }

    private void invalidateSpeechCapture() {
        speechCaptureSequence++;
        speechActive = false;
        SpeechRecognizer previous = speechRecognizer;
        speechRecognizer = null;
        activeSpeechPurpose = null;
        if (previous != null) {
            try {
                previous.cancel();
            } catch (RuntimeException ignored) {
                // Destroy below even if the recognizer has already stopped.
            }
            try {
                previous.destroy();
            } catch (RuntimeException ignored) {
                // The generation still invalidates any queued callback.
            }
        }
    }

    private void stopSpeechCapture() {
        if (liveVoiceSession != null) {
            liveVoiceSession.stopUserTurn();
            showStatus("말하기를 마쳤어요. AI가 답하고 있어요.", false);
        }
    }

    private void handleLiveToolCalls(JSONArray calls, long userTurn) {
        GeminiLiveVoiceSession session = liveVoiceSession;
        if (session == null || calls == null || calls.length() == 0) return;
        liveToolBusyNoticeSpoken = false;
        if (calls.length() != 1) {
            JSONArray responses = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call != null) responses.put(liveToolResponse(call.optString("id", ""),
                        call.optString("name", ""), json("ok", false, "reason", "one_action_per_turn")));
            }
            speakLiveToolResponses(session, responses,
                    "한 번에 한 가지씩 말씀해 주세요. 다시 부탁드릴게요.", "", userTurn);
            return;
        }
        JSONObject call = calls.optJSONObject(0);
        if (call == null) return;
        String callId = call.optString("id", "");
        String name = call.optString("name", "");
        JSONObject args = call.optJSONObject("args");
        if (args == null) args = new JSONObject();
        if (userTurn != liveNavigationGate.currentUserTurn()) {
            sendLiveToolResponse(session, callId, name, json("ok", false, "reason", "stale_user_turn"));
            return;
        }
        switch (name) {
            case "search_places": handleLivePlaceSearch(session, callId, args, userTurn); return;
            case "select_candidate": handleLiveCandidateSelection(session, callId, args, userTurn); return;
            case "reject_candidate": handleLiveCandidateRejection(session, callId, args); return;
            case "start_navigation": handleLiveNavigationStart(session, callId, args); return;
            case "set_travel_mode": handleLiveTravelModeChange(session, callId, args); return;
            default: sendLiveToolResponse(session, callId, name, json("ok", false, "reason", "unsupported_action"));
        }
    }

    private void handleLivePlaceSearch(GeminiLiveVoiceSession session, String callId,
                                       JSONObject args, long userTurn) {
        // A new destination request, even an incomplete one, must invalidate the old route target.
        clearDestinationCandidateContext();
        clearPendingCandidate();
        liveNavigationGate.clearActive();
        liveCandidates.clear();
        liveCandidatePresentationTurns.clear();
        liveAwaitingAssistantPresentation.clear();
        confirmButton.setVisibility(View.GONE);
        LiveDestinationQuery.Result request = LiveDestinationQuery.fromSlots(
                args.optString("region", ""), args.optString("category", ""),
                args.optString("place_name", ""));
        if (!request.isValid()) {
            String reason = request.error == LiveDestinationQuery.Error.MISSING_REGION
                    ? "어느 시나 구인지 말씀해 주세요. 예를 들어 부산 금정구처럼 말씀하시면 돼요."
                    : request.error == LiveDestinationQuery.Error.AMBIGUOUS_REGION
                    ? request.region + "만으로는 지역을 찾기 어려워요. 어느 시나 구인지 말씀해 주세요."
                    : "어떤 장소를 찾을까요? 병원이나 약국처럼 장소 종류 또는 이름을 말씀해 주세요.";
            showStatus(reason, false);
            speakLiveToolReply(session, callId, "search_places", json(
                    "ok", false, "reason", request.error.name(),
                    "destination_slots", liveDestinationSlots(request), "message", reason,
                    "instruction", "앱이 필요한 정보를 직접 물었습니다. 같은 질문을 반복하지 말고 새 음성에 이어 대화하세요."),
                    reason, "", userTurn);
            return;
        }
        String clientId;
        String clientSecret;
        try {
            clientId = settings.naverApiClientId();
            clientSecret = settings.naverApiClientSecret();
        } catch (RuntimeException e) {
            String message = "저장된 NAVER 검색 설정을 읽지 못했어요. 설정을 확인해 주세요.";
            showStatus(message, true);
            speakLiveToolReply(session, callId, "search_places", json("ok", false,
                    "reason", "naver_credentials_unreadable", "message", message), message, "", userTurn);
            return;
        }
        if (!NaverLocalSearchClient.validCredentials(clientId, clientSecret)) {
            String message = "실제 장소를 찾으려면 설정에서 NAVER API HUB Client ID와 Secret을 입력해 주세요.";
            showStatus(message, true);
            speakLiveToolReply(session, callId, "search_places", json("ok", false,
                    "reason", "naver_api_setup_required", "message", message,
                    "instruction", "앱이 필요한 설정을 사용자에게 안내했습니다. 키를 추측하지 마세요."), message, "", userTurn);
            return;
        }
        long requestId = ++activeSearchId;
        showStatus("실제 NAVER 장소 검색을 하고 있어요.", false);
        try {
            resolverWorker.execute(() -> {
                try {
                    NaverLocalSearchClient.Result result = NaverLocalSearchClient.search(
                            clientId, clientSecret, request.query, request.region);
                    handler.post(() -> finishLivePlaceSearch(session, callId, request,
                            result, requestId, userTurn));
                } catch (Exception e) {
                    handler.post(() -> {
                        if (liveVoiceSession != session) return;
                        if (requestId != activeSearchId || userTurn != liveNavigationGate.currentUserTurn()) {
                            sendLiveToolResponse(session, callId, "search_places", json("ok", false,
                                    "reason", "stale_search_cancelled"));
                            return;
                        }
                        String message = "네이버 장소 검색에 연결하지 못했어요. 잠시 뒤 다시 말씀해 주세요.";
                        showStatus(message, true);
                        speakLiveToolReply(session, callId, "search_places", json("ok", false,
                                "reason", "naver_search_failed",
                                "destination_slots", liveDestinationSlots(request),
                                "message", message), message, "", userTurn);
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            String message = "장소 검색이 바빠요. 잠시 뒤 다시 말씀해 주세요.";
            showStatus(message, true);
            speakLiveToolReply(session, callId, "search_places", json("ok", false,
                    "reason", "search_busy", "message", message), message, "", userTurn);
        }
    }

    private void finishLivePlaceSearch(GeminiLiveVoiceSession session, String callId,
                                       LiveDestinationQuery.Result request,
                                       NaverLocalSearchClient.Result result,
                                       long requestId, long userTurn) {
        if (isFinishing() || isDestroyed() || liveVoiceSession != session) return;
        if (requestId != activeSearchId || userTurn != liveNavigationGate.currentUserTurn()) {
            sendLiveToolResponse(session, callId, "search_places", json("ok", false,
                    "reason", "stale_search_cancelled",
                    "instruction", "사용자가 새 발화를 시작했습니다. 검색 결과를 제시하지 말고 새 발화에만 응답하세요."));
            return;
        }
        List<NaverLocalSearchClient.Place> places = result == null ? null : result.places;
        JSONArray candidates = new JSONArray();
        if (places != null) {
            int ordinal = 0;
            for (NaverLocalSearchClient.Place place : places) {
                if (place == null || ordinal >= 3) continue;
                Candidate candidate = new Candidate(place.name, place.address,
                        place.latitude, place.longitude, false);
                String id = "ma_" + UUID.randomUUID().toString().replace("-", "");
                if (!liveNavigationGate.register(id, candidate.name, candidate.address)) continue;
                liveCandidates.put(id, candidate);
                candidates.put(json("candidate_id", id, "name", candidate.name,
                        "address", candidate.address, "ordinal", ++ordinal));
            }
        }
        if (candidates.length() == 0) {
            String message = "네이버에서 조건에 맞는 곳을 찾지 못했어요. 다른 동네나 상호명을 말씀해 주세요.";
            showStatus(message, true);
            speakLiveToolReply(session, callId, "search_places", json("ok", false,
                    "reason", "no_matching_places", "searched_query", request.query,
                    "destination_slots", liveDestinationSlots(request),
                    "provider_total", result == null ? 0 : result.rawResultCount,
                    "message", message), message, "", userTurn);
            return;
        }
        liveCandidateContextGeneration = destinationCandidateContext.begin(SystemClock.elapsedRealtime());
        scheduleDestinationCandidateExpiry(liveCandidateContextGeneration,
                destinationCandidateContext.expiresAtElapsedRealtime());
        if (candidates.length() == 1) {
            String id = candidates.optJSONObject(0).optString("candidate_id", "");
            Candidate candidate = liveCandidates.get(id);
            if (candidate != null && liveNavigationGate.select(id)) {
                liveCandidatePresentationTurns.put(id, userTurn);
                liveAwaitingAssistantPresentation.add(id);
            }
        }
        showStatus("네이버에서 실제 장소를 찾았어요. 이름과 주소를 읽어드릴게요.", false);
        String candidateId = candidates.length() == 1
                ? candidates.optJSONObject(0).optString("candidate_id", "") : "";
        String exactSpeech = candidateId.isEmpty()
                ? liveCandidateOptionsSpeech() : liveCandidateConfirmationSpeech(candidateId);
        speakLiveToolReply(session, callId, "search_places", json("ok", true,
                "searched_query", request.query, "candidates", candidates,
                "destination_slots", liveDestinationSlots(request),
                "instruction", "검색 결과를 사용자에게 소리 내어 말하지 마세요. 앱이 사실 그대로 읽습니다. 한 곳이면 해당 후보를 확인 대상으로 사용하고, 여러 곳이면 사용자가 말한 순번이나 이름에 맞는 candidate_id를 선택하세요. 길 안내는 앱이 후보를 읽고 사용자가 새로 명확히 동의하기 전에는 시작하지 마세요."),
                exactSpeech, candidateId, userTurn);
    }

    private void handleLiveCandidateSelection(GeminiLiveVoiceSession session, String callId,
                                              JSONObject args, long userTurn) {
        String id = args.optString("candidate_id", "").trim();
        Candidate candidate = liveCandidates.get(id);
        if (candidate == null || !liveNavigationGate.select(id)) {
            String message = "그 후보를 다시 확인하지 못했어요. 번호나 장소 이름을 다시 말씀해 주세요.";
            showStatus(message, true);
            speakLiveToolReply(session, callId, "select_candidate", json("ok", false,
                    "reason", "candidate_expired_or_unavailable", "message", message), message, "", userTurn);
            return;
        }
        liveCandidatePresentationTurns.put(id, userTurn);
        liveAwaitingAssistantPresentation.add(id);
        String exactSpeech = liveCandidateConfirmationSpeech(id);
        speakLiveToolReply(session, callId, "select_candidate", json("ok", true,
                "candidate_id", id, "name", candidate.name, "address", candidate.address,
                "instruction", "앱이 실제 결과의 이름과 주소를 읽고 안내 동의를 물었습니다. 이름이나 주소를 다시 말하지 말고 사용자의 다음 발화만 판단하세요. 명확한 새 긍정일 때에만 start_navigation을 호출하세요."),
                exactSpeech, id, userTurn);
    }

    private String liveCandidateConfirmationSpeech(String candidateId) {
        Candidate candidate = liveCandidates.get(candidateId);
        Long presentationTurn = liveCandidatePresentationTurns.get(candidateId);
        if (candidate == null || presentationTurn == null
                || !liveAwaitingAssistantPresentation.contains(candidateId)
                || liveCandidateContextGeneration < 0L
                || !hasCandidateHandoffWindow(liveCandidateContextGeneration)) return "";
        return LiveCandidateSpeechBuilder.confirmation(candidateDisplayName(candidate),
                candidate.address, travelMode == TravelMode.TAXI);
    }

    private String liveCandidateOptionsSpeech() {
        if (liveCandidates.size() < 2) return "";
        List<String> labels = new ArrayList<>();
        for (Candidate candidate : liveCandidates.values()) {
            labels.add(candidateDisplayName(candidate) + ", " + candidate.address);
        }
        return LiveCandidateSpeechBuilder.options(labels);
    }

    private void speakLiveToolReply(GeminiLiveVoiceSession session, String callId, String name,
                                    JSONObject response, String exactSpeech, String candidateId,
                                    long presentationTurn) {
        JSONArray responses = new JSONArray().put(liveToolResponse(callId, name, response));
        speakLiveToolResponses(session, responses, exactSpeech, candidateId, presentationTurn);
    }

    private void speakLiveToolResponses(GeminiLiveVoiceSession session, JSONArray responses,
                                        String exactSpeech, String candidateId,
                                        long presentationTurn) {
        // Every locally spoken tool reply is authoritative; never let a second model voice
        // overlap or contradict the Android TTS presentation.
        if (session != null) session.suppressAssistantAudioUntilTurnComplete();
        String speech = exactSpeech == null ? "" : exactSpeech.trim();
        if (speech.isEmpty() || !ttsReady || tts == null) {
            attachAppPresentation(responses,
                    livePresentation(false, false, "", !candidateId.isEmpty()));
            if (!speech.isEmpty()) showStatus("음성 안내를 재생하지 못했어요. 화면의 내용을 확인해 주세요.", true);
            sendLiveToolResponses(session, responses);
            return;
        }
        String utteranceId = "ma-live-tool-" + UUID.randomUUID();
        liveToolTtsReplies.put(utteranceId, new PendingLiveToolReply(session, responses,
                speech, candidateId, presentationTurn));
        showStatus("검색 결과를 음성으로 확인하고 있어요.", false);
        PendingLiveToolReply pending = liveToolTtsReplies.get(utteranceId);
        pending.timeout = () -> {
            if (liveToolTtsReplies.get(utteranceId) != pending) return;
            if (tts != null && tts.isSpeaking()) tts.stop();
            showStatus("음성 안내가 지연되어 길 안내를 시작하지 않았어요. 다시 말씀해 주세요.", true);
            finishLiveToolTts(utteranceId, false);
        };
        handler.postDelayed(pending.timeout, LiveToolSpeechTimeout.forSpeech(speech));
        if (tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
            finishLiveToolTts(utteranceId, false);
        }
    }

    private void speakLiveNotice(String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (!ttsReady || tts == null) {
            showStatus(message, true);
            return;
        }
        String utteranceId = "ma-live-notice-" + UUID.randomUUID();
        if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
            showStatus(message, true);
        }
    }

    private void finishLiveToolTts(String utteranceId, boolean completed) {
        handler.post(() -> {
            PendingLiveToolReply pending = liveToolTtsReplies.remove(utteranceId);
            if (pending == null || pending.session != liveVoiceSession) return;
            if (pending.timeout != null) handler.removeCallbacks(pending.timeout);
            boolean candidatePresented = false;
            if (completed && !pending.candidateId.isEmpty()) {
                Candidate candidate = liveCandidates.get(pending.candidateId);
                boolean exact = candidate != null
                        && pending.exactSpeech.contains(candidateDisplayName(candidate))
                        && pending.exactSpeech.contains(candidate.address);
                boolean current = liveAwaitingAssistantPresentation.contains(pending.candidateId)
                        && liveCandidateContextGeneration >= 0L
                        && hasCandidateHandoffWindow(liveCandidateContextGeneration);
                candidatePresented = exact && current && liveNavigationGate.markPresented(
                        pending.candidateId, pending.presentationTurn);
                if (candidatePresented) {
                    liveAwaitingAssistantPresentation.remove(pending.candidateId);
                    confirmButton.setVisibility(View.GONE);
                    showStatus("장소 이름과 주소를 들려드렸어요. 맞으면 '네'라고 말씀해 주세요.", false);
                } else {
                    showStatus("후보 확인 상태가 만료되어 안내를 시작하지 않았어요. 다시 검색해 주세요.", true);
                }
            } else if (!completed) {
                showStatus("음성 안내를 끝까지 재생하지 못했어요. 안내를 다시 확인해 주세요.", true);
            }
            attachAppPresentation(pending.responses,
                    livePresentation(completed, candidatePresented, completed ? pending.exactSpeech : "",
                            !pending.candidateId.isEmpty()));
            sendLiveToolResponses(pending.session, pending.responses);
        });
    }

    private static JSONObject livePresentation(boolean completed, boolean candidatePresented,
                                               String exactSpeech, boolean hasCandidate) {
        return GeminiLiveProtocol.appPresentation(completed, exactSpeech,
                hasCandidate && candidatePresented);
    }

    private static JSONObject liveDestinationSlots(LiveDestinationQuery.Result query) {
        return json("region", query == null ? "" : query.region,
                "category", query == null ? "" : query.category,
                "place_name", query == null ? "" : query.placeName);
    }

    private static void attachAppPresentation(JSONArray responses, JSONObject presentation) {
        if (responses == null) return;
        for (int i = 0; i < responses.length(); i++) {
            JSONObject item = responses.optJSONObject(i);
            JSONObject response = item == null ? null : item.optJSONObject("response");
            if (response != null) putJson(response, "app_presentation", presentation);
        }
    }

    private void suppressFactualToolAudio(GeminiLiveVoiceSession session, JSONArray responses) {
        if (session != null && GeminiLiveProtocol.requiresSuppressedAssistantAudio(responses)) {
            session.suppressAssistantAudioUntilTurnComplete();
        }
    }

    private void sendLiveToolResponses(GeminiLiveVoiceSession session, JSONArray responses) {
        if (session != null && session == liveVoiceSession) {
            suppressFactualToolAudio(session, responses);
            session.sendToolResponses(responses);
        }
    }

    private boolean hasPendingLiveAppSpeech() {
        return !liveToolTtsReplies.isEmpty();
    }

    private void handleLiveCandidateRejection(GeminiLiveVoiceSession session, String callId, JSONObject args) {
        String id = args.optString("candidate_id", "").trim();
        Candidate candidate = liveCandidates.get(id);
        boolean rejected = candidate != null && liveNavigationGate.reject(id);
        if (rejected) {
            liveCandidates.remove(id);
            liveCandidatePresentationTurns.remove(id);
            liveAwaitingAssistantPresentation.remove(id);
            if (pendingCandidate == candidate) clearPendingCandidate();
            showStatus("그 장소는 제외했어요. 같은 곳을 다시 권하지 않을게요.", false);
        }
        sendLiveToolResponse(session, callId, "reject_candidate", json("ok", rejected,
                "reason", rejected ? "candidate_rejected" : "candidate_not_current",
                "instruction", "거절된 후보를 반복하지 말고 바꾸고 싶은 조건을 물어보세요."));
    }

    private void handleLiveTravelModeChange(GeminiLiveVoiceSession session, String callId, JSONObject args) {
        String mode = args.optString("mode", "").trim().toUpperCase(Locale.ROOT);
        if ("TAXI".equals(mode)) selectTravelMode(TravelMode.TAXI);
        else if ("TRANSIT".equals(mode) || "PUBLIC_TRANSPORT".equals(mode)) selectTravelMode(TravelMode.TRANSIT);
        else {
            sendLiveToolResponse(session, callId, "set_travel_mode", json("ok", false, "reason", "invalid_mode"));
            return;
        }
        sendLiveToolResponse(session, callId, "set_travel_mode", json("ok", true, "mode", mode));
    }

    private void handleLiveNavigationStart(GeminiLiveVoiceSession session, String callId, JSONObject args) {
        String id = args.optString("candidate_id", "").trim();
        Candidate candidate = liveCandidates.get(id);
        if (candidate == null || liveCandidateContextGeneration < 0L
                || !hasCandidateHandoffWindow(liveCandidateContextGeneration)) {
            sendLiveToolResponse(session, callId, "start_navigation", json("ok", false,
                    "reason", "candidate_expired", "message", "검색 결과가 만료되어 안내를 시작하지 않았습니다."));
            return;
        }
        LiveNavigationGate.Decision decision = liveNavigationGate.consumeForNavigation(id);
        if (decision != LiveNavigationGate.Decision.ALLOWED) {
            sendLiveToolResponse(session, callId, "start_navigation", json("ok", false,
                    "reason", decision.name(),
                    "message", "후보를 들은 다음 새 발화로 명확히 동의하기 전에는 길 안내를 시작할 수 없습니다."));
            return;
        }
        setPendingCandidate(candidate);
        long candidateGenerationAtApproval = candidateGeneration;
        liveCandidates.remove(id);
        liveCandidatePresentationTurns.remove(id);
        liveAwaitingAssistantPresentation.remove(id);
        sendLiveToolResponse(session, callId, "start_navigation", json("ok", true,
                "status", "starting", "destination", candidateDisplayName(candidate),
                "address", candidate.address));
        handler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                openConfirmedRoute(candidate, candidateGenerationAtApproval, UI_APPROVAL_TURN_SEQUENCE);
            }
        }, 350L);
    }

    private static JSONObject json(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) value.put(String.valueOf(pairs[i]), pairs[i + 1]);
        } catch (JSONException ignored) { }
        return value;
    }

    private static void putJson(JSONObject target, String key, Object value) {
        if (target == null || key == null) return;
        try { target.put(key, value); } catch (JSONException ignored) { }
    }

    private static JSONObject liveToolResponse(String id, String name, JSONObject response) {
        return json("id", id == null ? "" : id, "name", name == null ? "" : name,
                "response", response == null ? new JSONObject() : response);
    }

    private void sendLiveToolResponse(GeminiLiveVoiceSession session, String id, String name,
                                      JSONObject response) {
        if (session != null && session == liveVoiceSession) {
            sendLiveToolResponses(session,
                    new JSONArray().put(liveToolResponse(id, name, response)));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (microphonePressed) {
                    beginGeminiLiveTurn();
                } else {
                    showStatus("마이크 권한을 켰어요. 버튼을 누르고 말씀해 주세요.", false);
                }
            } else {
                showStatus("Gemini 음성 대화를 쓰려면 MA의 마이크 권한을 켜 주세요.", true);
                new AlertDialog.Builder(this).setTitle("마이크 권한 필요")
                        .setMessage("MA가 마이크에 들어온 음성을 Gemini Live로 직접 전달해 대화하려면 마이크 권한이 필요해요.")
                        .setPositiveButton("앱 권한 설정", (dialog, which) -> openMicrophoneSettings())
                        .setNegativeButton("닫기", null).show();
            }
        }
    }

    private void showMicrophonePermissionFallback(SpeechPurpose purpose) {
        if (purpose == SpeechPurpose.CANDIDATE_SELECTION
                && pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
            prepareCandidateChoiceScreen(
                    "마이크를 사용할 수 없어요. 화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.",
                    false);
            showCandidateChoiceDialog(true);
            return;
        }
        if (purpose == SpeechPurpose.CONFIRMATION && pendingCandidate != null
                && isCurrentCandidate(pendingCandidate, candidateGeneration)) {
            showStatus("마이크를 사용할 수 없어요. 화면의 큰 확인 버튼을 눌러 주세요.", true);
            return;
        }
        if (purpose == SpeechPurpose.SPECIALIST_CONSENT && isPendingSpecialistConsentCurrent()) {
            showStatus("마이크를 사용할 수 없어요. 검색 여부를 화면 버튼으로 선택해 주세요.", true);
            return;
        }
        showStatus("마이크 권한이 꺼져 있어요. 설정에서 켜거나 글자로 입력해 주세요.", true);
        new AlertDialog.Builder(this)
                .setTitle("음성 입력 권한")
                .setMessage("말씀으로 목적지를 찾으려면 MA의 마이크 권한이 필요해요. 설정에서 권한을 켜거나 글자로 입력할 수 있어요.")
                .setPositiveButton("마이크 설정 열기", (dialog, which) -> openMicrophoneSettings())
                .setNeutralButton("글자로 입력", (dialog, which) -> showTextEntry())
                .setNegativeButton("나중에", null)
                .show();
    }

    private void showSpeechRecognitionUnavailableFallback(SpeechPurpose purpose) {
        if (purpose == SpeechPurpose.CANDIDATE_SELECTION
                && pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
            prepareCandidateChoiceScreen(
                    "음성으로 고르기 어려워요. 화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.",
                    false);
            showCandidateChoiceDialog(true);
            return;
        }
        if (purpose == SpeechPurpose.CONFIRMATION && pendingCandidate != null
                && isCurrentCandidate(pendingCandidate, candidateGeneration)) {
            showStatus("음성 확인을 시작하지 못했어요. 화면의 큰 확인 버튼을 눌러 주세요.", true);
            return;
        }
        if (purpose == SpeechPurpose.SPECIALIST_CONSENT && isPendingSpecialistConsentCurrent()) {
            showStatus("음성 확인을 시작하지 못했어요. 검색 여부를 화면 버튼으로 선택해 주세요.", true);
            return;
        }
        showStatus("이 기기에서 음성 인식을 시작할 수 없어요. 글자로 입력해 주세요.", true);
        new AlertDialog.Builder(this)
                .setTitle("음성 인식 사용 불가")
                .setMessage("지금은 음성으로 목적지를 들을 수 없어요. 글자로 입력하거나 나중에 다시 시도할 수 있어요.")
                .setPositiveButton("글자로 입력", (dialog, which) -> showTextEntry())
                .setNeutralButton("다시 시도", (dialog, which) -> beginSpeech(purpose, true))
                .setNegativeButton("나중에", null)
                .show();
    }

    private void openMicrophoneSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showStatus("설정 화면을 열 수 없어요. 앱 정보에서 마이크 권한을 켜거나 글자로 입력해 주세요.", true);
        }
    }

    private void showTextEntry() {
        cancelPendingAutoListen();
        if (speechRecognizer != null) invalidateSpeechCapture();
        speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setHint("예: 서울역에 가고 싶어요");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("목적지를 입력해 주세요")
                .setView(input)
                .setPositiveButton("확인", (dialog, which) -> handleRequest(input.getText().toString(), true))
                .setNegativeButton("취소", null)
                .show();
    }

    private void handleRequest(String utterance) {
        handleRequest(utterance, false);
    }

    private boolean handlePendingConditionAnswer(String utterance) {
        long now = SystemClock.elapsedRealtime();
        PendingActionGate.Type actionType = pendingActionGate.pendingType(now);
        if (actionType == PendingActionGate.Type.TOPIC_SELECTION) {
            ConditionTopicResolver.Resolution selection =
                    pendingActionGate.previewTopicSelection(utterance, now);
            if (selection == null || selection.kind == ConditionTopicResolver.Kind.UNCLEAR) {
                if (DestinationIntentClassifier.isNewDestination(utterance)) {
                    clearPendingConditionFlow();
                    return false;
                }
            }
        } else if (actionType != null && DestinationIntentClassifier.isNewDestination(utterance)) {
            clearPendingConditionFlow();
            return false;
        }
        PendingActionGate.Answer answer = pendingActionGate.consumePending(utterance, now);
        if (answer == null) return false;
        if (answer.expired) {
            clearPendingConditionFlow();
            String message = "확인 시간이 지나 조건을 바꾸지 않았어요. 새 목적지를 말씀해 주세요.";
            showStatus(message, true);
            speakThenListen(message, SpeechPurpose.DESTINATION);
            return true;
        }
        if (answer.type == PendingActionGate.Type.TOPIC_SELECTION) {
            return handleConditionTopicSelection(answer);
        }
        if (answer.type != PendingActionGate.Type.CONDITION_ANSWER) return false;

        String topic = answer.scopeKey;
        String label = displayConditionTopic(topic);
        if (answer.polarity == ConditionCancellationMatcher.AnswerPolarity.UNCLEAR
                && conditionAnswerRounds.shouldReask(
                PendingActionGate.Type.CONDITION_ANSWER, topic)) {
            askConditionRemovalQuestion(topic, true, "");
            return true;
        }

        conditionAnswerRounds.resolve(PendingActionGate.Type.CONDITION_ANSWER, topic);
        if (answer.polarity == ConditionCancellationMatcher.AnswerPolarity.YES) {
            LinkedHashSet<String> activeAtTurnStart =
                    new LinkedHashSet<>(activeConditionDetailsForCurrentFlow.keySet());
            if (!pendingBulkConditionRemovalTopics.isEmpty()) {
                pendingBulkCascadeRemovedTopics.addAll(
                        cascadeChildrenStillActive(topic, activeAtTurnStart));
            }
            applyConditionAction(ConditionAction.cancel(topic), activeAtTurnStart);
            hasUnverifiedConditionsForCurrentFlow = !activeConditionDetailsForCurrentFlow.isEmpty();
            if ("specialist".equals(topic)) {
                clearPendingSpecialistConsent();
                awaitingSpecialistVerification = false;
                specialistConditionForCurrentFlow = false;
                specialistConditionRequestId = -1L;
                specialistStatusUnverifiedForCandidate = false;
            }
        }

        if (answer.polarity == ConditionCancellationMatcher.AnswerPolarity.UNCLEAR
                && !pendingBulkConditionRemovalTopics.isEmpty()) {
            if (askNextBulkConditionRemoval()) return true;
        }
        if (answer.polarity != ConditionCancellationMatcher.AnswerPolarity.UNCLEAR
                && askNextBulkConditionRemoval()) return true;

        if (pendingCandidate != null && answer.polarity == ConditionCancellationMatcher.AnswerPolarity.YES
                && isCurrentCandidate(pendingCandidate, candidateGeneration)) {
            showCandidate(pendingCandidate);
            return true;
        }

        String message = answer.polarity == ConditionCancellationMatcher.AnswerPolarity.YES
                ? "알겠어요. " + label + " 조건은 뺐어요. 목적지를 다시 말씀해 주세요."
                : answer.polarity == ConditionCancellationMatcher.AnswerPolarity.NO
                ? "알겠어요. " + label + " 조건은 그대로 둘게요. 목적지를 다시 말씀해 주세요."
                : label + " 조건은 그대로 두었어요. 목적지를 다시 말씀해 주세요.";
        if (!pendingBulkSkippedByCascade.isEmpty()) {
            String together = displayConditionTopicList(pendingBulkSkippedByCascade);
            message = message.replace(" 목적지를 다시 말씀해 주세요.",
                    " " + together + " 조건도 함께 정리되어 있어요. 목적지를 다시 말씀해 주세요.");
        }
        if (!pendingBulkSkippedWhileInactive.isEmpty()) {
            String inactive = displayConditionTopicList(pendingBulkSkippedWhileInactive);
            message = message.replace(" 목적지를 다시 말씀해 주세요.",
                    " " + inactive + " 조건은 현재 활성 상태가 아니어서 확인을 건너뛰었어요. 목적지를 다시 말씀해 주세요.");
        }
        pendingBulkConditionRemovalTopics.clear();
        pendingBulkCascadeRemovedTopics.clear();
        pendingBulkSkippedByCascade.clear();
        pendingBulkSkippedWhileInactive.clear();
        showStatus(message, answer.polarity == ConditionCancellationMatcher.AnswerPolarity.UNCLEAR);
        speakThenListen(message, SpeechPurpose.DESTINATION);
        return true;
    }

    private boolean handleConditionTopicSelection(PendingActionGate.Answer answer) {
        if (answer.selectionKind == ConditionTopicResolver.Kind.UNCLEAR) {
            if (conditionAnswerRounds.shouldReask(PendingActionGate.Type.TOPIC_SELECTION,
                    answer.scopeKey)) {
                askTopicSelectionQuestion(answer.allowedTopicKeys, true);
                return true;
            }
            conditionAnswerRounds.resolve(PendingActionGate.Type.TOPIC_SELECTION, answer.scopeKey);
            activeTopicSelectionScope = "";
            pendingBulkConditionRemovalTopics.clear();
            String message = "조건은 바꾸지 않았어요. 목적지를 다시 말씀해 주세요.";
            showStatus(message, true);
            speakThenListen(message, SpeechPurpose.DESTINATION);
            return true;
        }

        conditionAnswerRounds.resolve(PendingActionGate.Type.TOPIC_SELECTION, answer.scopeKey);
        activeTopicSelectionScope = "";
        if (answer.selectionKind == ConditionTopicResolver.Kind.MULTIPLE) {
            pendingBulkConditionRemovalTopics.clear();
            pendingBulkCascadeRemovedTopics.clear();
            pendingBulkSkippedByCascade.clear();
            pendingBulkSkippedWhileInactive.clear();
            pendingBulkConditionRemovalTopics.addAll(answer.selectedTopicKeys);
            if (pendingBulkConditionRemovalTopics.isEmpty()) return false;
            return askNextBulkConditionRemoval("한 번에 하나씩 확인할게요. 먼저 ");
        }
        askConditionRemovalQuestion(answer.selectedTopicKey, false, "");
        return true;
    }

    private boolean askBeforeChangingAmbiguousCondition(String utterance) {
        LinkedHashSet<String> mentioned = conditionTopicsMentioned(utterance);
        boolean neutral = mentioned.isEmpty()
                ? ConditionCancellationMatcher.isStandaloneNeutralOkayReply(utterance)
                : ConditionCancellationMatcher.isNeutralOkayEvidence(utterance);
        if (!neutral) return false;

        LinkedHashSet<String> eligible = new LinkedHashSet<>();
        if (mentioned.isEmpty()) {
            eligible.addAll(activeConditionDetailsForCurrentFlow.keySet());
        } else {
            for (String topic : mentioned) {
                if (activeConditionDetailsForCurrentFlow.containsKey(topic)) eligible.add(topic);
            }
        }
        if (eligible.isEmpty()) return false;
        if (eligible.size() == 1) {
            askConditionRemovalQuestion(eligible.iterator().next(), false, "");
        } else {
            askTopicSelectionQuestion(eligible, false);
        }
        return true;
    }

    private void askTopicSelectionQuestion(java.util.Set<String> topics, boolean reask) {
        if (topics == null || topics.isEmpty()) return;
        prepareConditionClarification();
        LinkedHashSet<String> allowed = new LinkedHashSet<>(topics);
        if (!reask) {
            activeTopicSelectionScope = "topic-selection-" + (++topicSelectionRoundCounter);
            conditionAnswerRounds.beginQuestion(PendingActionGate.Type.TOPIC_SELECTION,
                    activeTopicSelectionScope);
        } else if (activeTopicSelectionScope.isEmpty()) {
            activeTopicSelectionScope = "topic-selection-" + (++topicSelectionRoundCounter);
            conditionAnswerRounds.beginQuestion(PendingActionGate.Type.TOPIC_SELECTION,
                    activeTopicSelectionScope);
        }
        StringBuilder labels = new StringBuilder();
        for (String topic : allowed) {
            if (labels.length() > 0) labels.append(" 또는 ");
            labels.append(displayConditionTopic(topic));
        }
        String question = reask
                ? "잘 듣지 못했어요. " + labels + " 중 한 가지를 말씀해 주세요."
                : labels + " 중 어떤 조건을 말씀하시는 걸까요? 한 가지를 말씀해 주세요.";
        showStatus(question, false);
        speakThenListen(question, SpeechPurpose.DESTINATION, null, allowed,
                activeTopicSelectionScope);
    }

    private boolean askNextBulkConditionRemoval() {
        return askNextBulkConditionRemoval("");
    }

    private boolean askNextBulkConditionRemoval(String prefix) {
        ConditionTopicQueue.Next next = ConditionTopicQueue.takeNextActive(
                pendingBulkConditionRemovalTopics,
                activeConditionDetailsForCurrentFlow.keySet(), pendingBulkCascadeRemovedTopics);
        pendingBulkSkippedByCascade.addAll(next.skippedByCascade);
        pendingBulkSkippedWhileInactive.addAll(next.skippedWhileInactive);
        if (next.topicKey == null) return false;
        String topic = next.topicKey;
        String lead = prefix;
        if (lead.isEmpty() && pendingBulkConditionRemovalTopics.size() < 2) {
            lead = "다음은 ";
        } else if (lead.isEmpty()) {
            lead = "이어서 ";
        }
        askConditionRemovalQuestion(topic, false, lead);
        return true;
    }

    private void askConditionRemovalQuestion(String topic, boolean reask, String prefix) {
        prepareConditionClarification();
        if (!reask) {
            conditionAnswerRounds.beginQuestion(PendingActionGate.Type.CONDITION_ANSWER, topic);
        }
        String question = (prefix == null ? "" : prefix) + displayConditionTopic(topic)
                + " 조건을 뺄까요? 네 또는 아니요로 말씀해 주세요.";
        showStatus(question, false);
        speakThenListen(question, SpeechPurpose.DESTINATION, topic);
    }

    private void prepareConditionClarification() {
        // Invalidate any earlier asynchronous search before installing a new answer consumer.
        activeSearchId++;
        if (activeSpeechPurpose == SpeechPurpose.CONFIRMATION) invalidateSpeechCapture();
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        confirmationCandidateChoices = null;
        confirmButton.setVisibility(View.GONE);
    }

    private void clearPendingConditionFlow() {
        pendingActionGate.clear();
        conditionAnswerRounds.clear();
        pendingBulkConditionRemovalTopics.clear();
        pendingBulkCascadeRemovedTopics.clear();
        pendingBulkSkippedByCascade.clear();
        pendingBulkSkippedWhileInactive.clear();
        activeTopicSelectionScope = "";
    }

    private LinkedHashSet<String> cascadeChildrenStillActive(String topic,
                                                              LinkedHashSet<String> activeBefore) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        requested.add(topic);
        LinkedHashSet<String> expanded = expandConditionCancellations(requested);
        expanded.remove(topic);
        expanded.retainAll(activeBefore);
        return expanded;
    }

    private String displayConditionTopicList(java.util.Set<String> topics) {
        StringBuilder labels = new StringBuilder();
        for (String topic : topics) {
            if (labels.length() > 0) labels.append(", ");
            labels.append(displayConditionTopic(topic));
        }
        return labels.toString();
    }

    private String displayConditionTopic(String topic) {
        ConditionState active = activeConditionDetailsForCurrentFlow.get(topic);
        if (active != null && !active.label.isEmpty()) {
            return active.label.replaceAll("\\s*조건$", "");
        }
        switch (topic == null ? "" : topic) {
            case "parking": return "주차";
            case "specialist": return "전문의";
            case "stairs": return "계단";
            case "ramp": return "경사로";
            case "slope": return "경사";
            case "elevator": return "엘리베이터";
            case "wheelchair": return "휠체어";
            case "accessible_facility": return "장애인 화장실";
            case "accessibility": return "장애인 편의";
            case "mobility": return "걷기";
            case "hours": return "진료 시간";
            case "distance": return "거리";
            default: return "해당";
        }
    }

    private void handleRequest(String utterance, boolean fromTextEntry) {
        if (fromTextEntry && pendingCandidate != null
                && !DestinationIntentClassifier.isExplicitReplacementDuringConfirmation(utterance)) {
            // Every typed response belongs to the currently displayed candidate prompt.
            // In particular, a bare "네" or another unclear answer must not fall through
            // into destination parsing. A phrase that explicitly corrects the destination
            // is intentionally allowed to start a replacement search below.
            handleDestinationConfirmation(utterance, pendingCandidate, candidateGeneration,
                    UI_APPROVAL_TURN_SEQUENCE, travelMode);
            return;
        }
        if (handlePendingConditionAnswer(utterance)) return;
        if (destinationClarificationLease.isActive(SystemClock.elapsedRealtime())
                && DestinationClarificationCancellation.isCancellation(utterance)) {
            destinationClarificationLease.clear();
            destinationCorrectionContext.clear();
            destinationHypothesisRepeatGate.reset();
            destinationAreaOnlyPromptGate.reset();
            clearPendingSpecialistConsent();
            awaitingSpecialistVerification = false;
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            showStatus("알겠어요. 장소 찾기를 취소했어요.", true);
            return;
        }
        if (askBeforeChangingAmbiguousCondition(utterance)) return;
        if (fromTextEntry
                && repeatedAmbiguousDestination.shouldHandleTextAsDistrictAnswer(utterance)) {
            long requestId = ++activeSearchId;
            activeSearchFromTextEntry = true;
            clearPendingSpecialistConsent();
            clearPendingCandidate();
            pendingCandidateChoices = null;
            voiceCandidateChoices = null;
            handleRepeatedDistrictAnswer(utterance, requestId);
            return;
        }
        long requestId = ++activeSearchId;
        activeSearchFromTextEntry = fromTextEntry;
        if (fromTextEntry) destinationSearchFailureCount = 0;
        clearDestinationCandidateContext();
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        confirmButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        recognizedText.setText("들은 말: " + utterance.trim());
        destinationText.setText("");
        String previousDestinationForCorrection = destinationCorrectionContext
                .current(SystemClock.elapsedRealtime());
        DestinationPhraseParser.Result literalParsed = DestinationPhraseParser.parse(utterance);
        DestinationPhraseParser.Result parsed = DestinationPhraseParser
                .parseWithPreviousDestinationCorrection(utterance, previousDestinationForCorrection);
        if (parsed.modeSpecified) {
            selectTravelMode(parsed.taxiMode ? TravelMode.TAXI : TravelMode.TRANSIT);
        }
        long clarificationNow = SystemClock.elapsedRealtime();
        String clarificationContext = destinationClarificationLease.phrase(clarificationNow);
        if (!parsed.destination.equals(literalParsed.destination)
                && !previousDestinationForCorrection.trim().isEmpty()) {
            clarificationContext = mergeClarificationContext(clarificationContext,
                    previousDestinationForCorrection);
        }
        boolean continuesClarification = !clarificationContext.trim().isEmpty()
                || destinationClarificationLease.isAwaitingReply(clarificationNow);
        if (DestinationQueryGuard.hasIncompleteCorrection(utterance)) {
            destinationClarificationLease.clear();
            destinationCorrectionContext.clear();
            String repeatMessage = "교정하신 뒤 목적지를 다 듣지 못했어요. 동네와 찾으시는 장소를 함께 다시 말씀해 주세요.";
            if (destinationHypothesisRepeatGate.mayRepeatAutomatically()) {
                askForDestinationAgain(repeatMessage);
            } else {
                showStatus("목적지를 정하지 못했어요. 마이크를 눌러 목적지 전체를 다시 말씀해 주세요.", true);
            }
            return;
        }
        if (DestinationQueryGuard.hasUnresolvedPlaceTypeConflict(clarificationContext, utterance)) {
            // A missed ASR correction marker can leave two categories in one otherwise usable
            // phrase (for example, "dermatology ... [말고] pharmacy"). Never let AI choose one.
            destinationClarificationLease.clear();
            destinationCorrectionContext.clear();
            String repeatMessage = "장소 종류를 여러 가지로 들었어요. 동네와 찾으시는 장소 한 가지만 함께 다시 말씀해 주세요.";
            if (destinationHypothesisRepeatGate.mayRepeatAutomatically()) {
                askForDestinationAgain(repeatMessage);
            } else {
                showStatus("장소 종류를 하나로 정하지 못했어요. 마이크를 눌러 동네와 장소 종류 한 가지만 다시 말씀해 주세요.", true);
            }
            return;
        }
        if (!continuesClarification
                && DestinationSpeechHypothesisPolicy.isNeighborhoodOnlyDestination(parsed.destination)) {
            String prompt = "동네 이름만으로는 정확한 장소를 찾기 어려워요. 동네와 찾으시는 병원이나 가게 종류를 함께 말씀해 주세요.";
            if (destinationAreaOnlyPromptGate.mayRepeatAutomatically()) {
                askForDestinationAgain(prompt);
            } else {
                showStatus("정확한 장소가 필요해요. 예를 들어 ‘서동 피부과’처럼 동네와 장소 종류를 함께 말씀해 주세요.", true);
            }
            return;
        }
        if (!continuesClarification) {
            usedLiteralAiFallbackForCurrentFlow = false;
            literalFallbackContextNoticeForCurrentFlow = "";
        }
        boolean requiresSpecialistVerification = (continuesClarification
                && (awaitingSpecialistVerification || specialistConditionForCurrentFlow
                && specialistConditionRequestId >= 0L))
                || hasUnverifiedSpecialistCondition(utterance)
                || hasUnverifiedSpecialistCondition(clarificationContext);
        if (!parsed.isUsable()) {
            awaitingSpecialistVerification = requiresSpecialistVerification;
            specialistConditionForCurrentFlow = requiresSpecialistVerification;
            specialistConditionRequestId = requiresSpecialistVerification ? requestId : -1L;
            if (continuesClarification || requiresSpecialistVerification) {
                destinationClarificationLease.remember(
                        mergeClarificationContext(clarificationContext, utterance), clarificationNow);
            }
            if (destinationHypothesisRepeatGate.hasUsedAutomaticRepeat()
                    || destinationAreaOnlyPromptGate.hasUsedAutomaticRepeat()) {
                showStatus(parsed.message
                        + " 화면에서 직접 입력하거나 마이크를 눌러 다시 말씀해 주세요.", true);
            } else {
                askForDestinationAgain(parsed.message);
            }
            return;
        }
        destinationHypothesisRepeatGate.reset();
        destinationAreaOnlyPromptGate.reset();
        destinationClarificationLease.clear();
        destinationCorrectionContext.remember(parsed.destination, SystemClock.elapsedRealtime());
        awaitingSpecialistVerification = requiresSpecialistVerification;
        specialistConditionForCurrentFlow = requiresSpecialistVerification;
        specialistConditionRequestId = requiresSpecialistVerification ? requestId : -1L;
        specialistStatusUnverifiedForCandidate = false;
        showStatus("말씀하신 목적지를 AI가 이해하고 있어요.", false);
        // Keep the spoken place phrase available as a local geocoder fallback. The AI service
        // improves colloquial interpretation, but a temporary proxy outage must not dead-end
        // a destination the user has already spoken.
        interpretDestination(utterance.trim(), clarificationContext, parsed.destination, requestId,
                requiresSpecialistVerification);
    }

    private void interpretDestination(String utterance, String clarificationContext,
                                      String localFallbackQuery, long requestId,
                                      boolean requiresSpecialistVerification) {
        LinkedHashSet<String> cancelledTopics = cancelledConditionTopics(utterance);
        boolean specialistExplicitlyRequested = false;
        for (String condition : detectedConditionLabels(utterance)) {
            if ("specialist".equals(conditionTopic(condition))) specialistExplicitlyRequested = true;
        }
        boolean specialistCancelledInUtterance = cancelledTopics.contains("specialist")
                && !specialistExplicitlyRequested;
        boolean effectiveRequiresSpecialistVerification = requiresSpecialistVerification
                && !specialistCancelledInUtterance;
        try {
            resolverWorker.execute(() -> {
                DestinationAiClient.Result interpreted;
                try {
                    String proxyUrl = settings.geminiProxyUrl();
                    String apiKey = settings.geminiApiKey();
                    String clovaApiKey = settings.clovaStudioApiKey();
                    if (!DestinationAiClient.isConfigured(proxyUrl, apiKey)
                            && !DestinationAiClient.isClovaConfigured(clovaApiKey)) {
                        handler.post(() -> handleDestinationAiUnavailable(requestId, utterance,
                                clarificationContext, localFallbackQuery,
                                requiresSpecialistVerification, null));
                        return;
                    }
                    interpreted = DestinationAiClient.isClovaConfigured(clovaApiKey)
                            ? DestinationAiClient.interpretWithClova(clovaApiKey, utterance,
                                    clarificationContext)
                            : DestinationAiClient.interpret(getApplicationContext(), proxyUrl,
                                    apiKey, utterance, clarificationContext);
                } catch (Exception e) {
                    handler.post(() -> handleDestinationAiUnavailable(requestId, utterance,
                            clarificationContext, localFallbackQuery,
                            effectiveRequiresSpecialistVerification,
                            "AI가 목적지 뜻을 확인하지 못했어요. 연결을 확인하고 다시 말씀해 주세요."));
                    return;
                }
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
                    updateUnverifiedConditionState(interpreted, utterance);
                    boolean specialistWasCleared = specialistCancelledInUtterance
                            || (requiresSpecialistVerification
                            && refersToPreviousCondition(utterance)
                            && hasConditionCancellation(compactConditionText(utterance)));
                    if (specialistWasCleared) {
                        specialistConditionForCurrentFlow = false;
                        specialistConditionRequestId = -1L;
                        specialistStatusUnverifiedForCandidate = false;
                    }
                    if (interpreted.needsClarification) {
                        destinationCorrectionContext.clear();
                        if (destinationClarificationAttempts >= 1) {
                            destinationClarificationLease.remember(mergeClarificationContext(
                                    clarificationContext, utterance), SystemClock.elapsedRealtime());
                            awaitingSpecialistVerification = effectiveRequiresSpecialistVerification
                                    && !specialistWasCleared;
                            showStatus("장소를 정하기 어려워요. 동네와 장소 이름을 함께 말씀하시거나 ‘글자로 입력’을 눌러 주세요.", true);
                            recognizedText.setText("장소를 더 자세히 알려 주세요.");
                            return;
                        }
                        destinationClarificationAttempts++;
                        destinationClarificationLease.remember(mergeClarificationContext(
                                clarificationContext, utterance), SystemClock.elapsedRealtime());
                        awaitingSpecialistVerification = effectiveRequiresSpecialistVerification
                                && !specialistWasCleared;
                        showStatus(interpreted.clarificationQuestion, false);
                        recognizedText.setText("들은 말: " + utterance + "\n장소를 더 확인하고 있어요.");
                        if (activeSearchFromTextEntry) speak(interpreted.clarificationQuestion);
                        else speakThenListen(interpreted.clarificationQuestion, SpeechPurpose.DESTINATION);
                        return;
                    }
                    destinationClarificationLease.clear();
                    awaitingSpecialistVerification = false;
                    String guardedQuery = DestinationQueryGuard.preserveExplicitIntent(
                            clarificationContext, utterance, interpreted.searchQuery);
                    if (guardedQuery.isEmpty()) {
                        showDestinationServiceFailure(requestId, "지역과 장소 종류를 유지한 검색어를 만들지 못했어요. 다시 말씀해 주세요.");
                        return;
                    }
                    java.util.LinkedHashSet<String> explicitlyRequestedAreas =
                            DestinationQueryGuard.effectiveAreas(clarificationContext, utterance);
                    boolean hasArea = !explicitlyRequestedAreas.isEmpty();
                    boolean hasPlaceType = !DestinationQueryGuard.findTypes(guardedQuery).isEmpty();
                    boolean nearbyNeedsArea = DestinationLocationRequirement.requiresNeighborhood(
                            clarificationContext, utterance) && !hasArea;
                    boolean genericPlaceNeedsArea = !hasArea
                            && DestinationLocationRequirement.requiresAreaForGenericPlaceType(guardedQuery);
                    if (nearbyNeedsArea || genericPlaceNeedsArea) {
                        String locationPrompt = nearbyNeedsArea
                                ? "현재 위치는 확인하지 않았어요. 가까운 장소를 찾으려면 동네를 말씀해 주세요. 예를 들어 부산 금정구 서동처럼 말씀해 주세요."
                                : "어느 동네의 " + guardedQuery + "를 찾을까요? 부산 금정구 서동처럼 말씀해 주세요.";
                        destinationClarificationLease.remember(mergeClarificationContext(
                                clarificationContext, utterance), SystemClock.elapsedRealtime());
                        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
                        if (destinationClarificationAttempts >= 1) {
                            if (genericPlaceNeedsArea && !nearbyNeedsArea) {
                                showRegionlessSearchChoice(guardedQuery,
                                        "동네를 정하지 못했어요. 동네를 다시 말하거나 지역 없이 넓게 찾을 수 있어요.",
                                        requestId);
                                return;
                            }
                            String retryPrompt = locationPrompt + (nearbyNeedsArea
                                    ? " 동네를 정해야 근처 장소를 찾을 수 있어요."
                                    : "");
                            showStatus(retryPrompt, true);
                            recognizedText.setText(nearbyNeedsArea
                                    ? "가까운 장소 검색에는 동네 정보가 필요해요."
                                    : "장소 종류만으로는 동네를 확인할 수 없어요.");
                            confirmButton.setText("동네 다시 말하기");
                            confirmButton.setOnClickListener(v -> {
                                confirmButton.setVisibility(View.GONE);
                                beginSpeech(SpeechPurpose.DESTINATION, true);
                            });
                            confirmButton.setVisibility(View.VISIBLE);
                            cancelButton.setText("처음으로");
                            cancelButton.setOnClickListener(v -> resetFlow(
                                    "처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
                            cancelButton.setVisibility(View.VISIBLE);
                            return;
                        }
                        destinationClarificationAttempts++;
                        showStatus(locationPrompt, false);
                        recognizedText.setText("가까운 장소를 찾으려면 동네를 확인해 주세요.");
                        if (activeSearchFromTextEntry) speak(locationPrompt);
                        else speakThenListen(locationPrompt, SpeechPurpose.DESTINATION);
                        return;
                    }
                    if (!interpreted.hasNamedPlace && (!hasArea || !hasPlaceType)) {
                        String missingSlotPrompt = !hasArea && !hasPlaceType
                                ? "어느 동네의 어떤 장소를 찾을까요?"
                                : !hasArea ? "어느 동네에서 찾을까요?"
                                : "어떤 장소를 찾을까요?";
                        destinationClarificationLease.remember(mergeClarificationContext(
                                clarificationContext, utterance), SystemClock.elapsedRealtime());
                        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
                        if (destinationClarificationAttempts >= 1) {
                            if (!hasArea) {
                                showRegionlessSearchChoice(guardedQuery,
                                        "동네를 정하지 못했어요. 동네를 다시 말하거나 지역 없이 넓게 찾을 수 있어요.",
                                        requestId);
                                return;
                            }
                            showStatus("동네와 장소 종류를 확인하지 못했어요. 두 가지를 함께 말씀해 주세요.", true);
                            recognizedText.setText("동네와 장소 종류를 다시 확인해 주세요.");
                            return;
                        }
                        destinationClarificationAttempts++;
                        showStatus(missingSlotPrompt, false);
                        recognizedText.setText("들은 말: " + utterance + "\n장소 정보를 더 확인하고 있어요.");
                        if (activeSearchFromTextEntry) speak(missingSlotPrompt);
                        else speakThenListen(missingSlotPrompt, SpeechPurpose.DESTINATION);
                        return;
                    }
                    if (DestinationQueryGuard.hasConflictingAreas(explicitlyRequestedAreas)) {
                        if (regionConflictAttempts >= 1) {
                            String queryWithoutAreas = DestinationQueryGuard.removeAreaTokens(
                                    guardedQuery, explicitlyRequestedAreas);
                            destinationClarificationLease.remember(queryWithoutAreas,
                                    SystemClock.elapsedRealtime());
                            destinationClarificationLease.setAwaitingReply(true,
                                    SystemClock.elapsedRealtime());
                            showRegionlessSearchChoice(queryWithoutAreas,
                                    "한 지역으로 정하지 못했어요. 지역 없이 넓게 찾거나 한 동네를 다시 말해 주세요.",
                                    requestId);
                            return;
                        }
                        regionConflictAttempts++;
                        String clarificationContextWithoutAreas = DestinationQueryGuard.removeAreaTokens(
                                guardedQuery, explicitlyRequestedAreas);
                        destinationClarificationLease.remember(clarificationContextWithoutAreas,
                                SystemClock.elapsedRealtime());
                        destinationClarificationLease.setAwaitingReply(true,
                                SystemClock.elapsedRealtime());
                        String areaChoicePrompt = "서로 다른 동네가 함께 들렸어요. 찾을 지역 한 곳을 말씀해 주세요.";
                        showStatus(areaChoicePrompt, false);
                        recognizedText.setText("찾을 지역을 한 곳만 다시 확인해 주세요.");
                        if (activeSearchFromTextEntry) speak(areaChoicePrompt);
                        else speakThenListen(areaChoicePrompt, SpeechPurpose.DESTINATION);
                        return;
                    }
                    regionConflictAttempts = 0;
                    destinationClarificationAttempts = 0;
                    requestedAreaConstraintForCurrentFlow = String.join(" ", explicitlyRequestedAreas);
                    RepeatedAmbiguousDestinationGuard.QueryAction repeatAction =
                            repeatedAmbiguousDestination.onDestinationQuery(
                                    guardedQuery, requestedAreaConstraintForCurrentFlow);
                    if (repeatAction == RepeatedAmbiguousDestinationGuard.QueryAction.ASK_KNOWN_DISTRICTS) {
                        askForPreviouslyObservedDistrict(requestId);
                        return;
                    }
                    if (repeatAction == RepeatedAmbiguousDestinationGuard.QueryAction.STOP) {
                        stopRepeatedAmbiguousDestination(requestId, false);
                        return;
                    }
                    if (repeatAction == RepeatedAmbiguousDestinationGuard.QueryAction.USE_OBSERVED_DISTRICT) {
                        narrowRememberedCandidatesToDistrict(
                                repeatedAmbiguousDestination.selectedDistrict(), requestId);
                        return;
                    }
                    if (repeatAction == RepeatedAmbiguousDestinationGuard.QueryAction.SEARCH_EXPLICIT_DISTRICT) {
                        searchWithExplicitDistrict(
                                repeatedAmbiguousDestination.selectedDistrict(), requestId);
                        return;
                    }
                    if (repeatAction == RepeatedAmbiguousDestinationGuard.QueryAction.NEW_QUERY) {
                        rememberedAmbiguousDestinationCandidates.clear();
                    }
                    awaitingRepeatedDistrictClarification = false;
                    destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
                    recognizedText.setText("들은 말: " + utterance + "\n장소 검색: " + guardedQuery);
                    showStatus("실제 장소 정보를 확인하고 있어요.", false);
                    resolveInterpretedDestination(guardedQuery, localFallbackQuery,
                            clarificationContext, requestId,
                            effectiveRequiresSpecialistVerification && !specialistWasCleared);
                });
            });
        } catch (RejectedExecutionException e) {
            destinationCorrectionContext.clear();
            showStatus("잠시 뒤 다시 말씀해 주세요.", true);
        }
    }

    private void resolveInterpretedDestination(String query, String localFallbackQuery,
                                              String clarificationContext, long requestId,
                                              boolean requiresSpecialistVerification) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        if (requiresSpecialistVerification) {
            askSpecialistConsent(query, localFallbackQuery, clarificationContext, requestId);
        } else {
            searchPlaces(query, requestId);
        }
    }

    private void showRegionlessSearchChoice(String query, String prompt, long requestId) {
        destinationClarificationLease.remember(query, SystemClock.elapsedRealtime());
        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
        showStatus(prompt, false);
        recognizedText.setText("지역을 지정하지 않으면 다른 동네의 장소도 나올 수 있어요.");
        confirmButton.setText("지역 없이 넓게 찾기");
        confirmButton.setOnClickListener(v -> {
            if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
            confirmButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
            destinationClarificationAttempts = 0;
            requestedAreaConstraintForCurrentFlow = "";
            showStatus("지역을 지정하지 않고 검색할게요.", false);
            String unexpiredQuery = destinationClarificationLease.phrase(SystemClock.elapsedRealtime());
            if (unexpiredQuery.isEmpty()) {
                showStatus("확인 시간이 지났어요. 새 목적지를 다시 말씀해 주세요.", true);
                return;
            }
            searchPlaces(unexpiredQuery, requestId);
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("동네 다시 말하기");
        cancelButton.setOnClickListener(v -> {
            if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
            confirmButton.setVisibility(View.GONE);
            destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
            destinationClarificationAttempts = 0;
            beginSpeech(SpeechPurpose.DESTINATION, true);
        });
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void showDestinationSetupRequired(long requestId) {
        if (isFinishing() || isDestroyed()
                || !SearchGenerationGuard.isCurrent(requestId, activeSearchId)) return;
        destinationCorrectionContext.clear();
        showStatus("목적지를 추측하지 않았어요. 설정에서 CLOVA Studio 또는 Gemini와 NAVER 장소 검색을 연결해 주세요.", true);
        recognizedText.setText("AI 해석과 실제 장소 검색 설정이 필요합니다.");
        confirmButton.setText("AI 검색 설정 열기");
        confirmButton.setOnClickListener(v -> showSettings());
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void handleDestinationAiUnavailable(long requestId, String utterance,
                                               String clarificationContext,
                                               String localFallbackQuery,
                                               boolean requiresSpecialistVerification,
                                               String failureMessage) {
        if (isFinishing() || isDestroyed()
                || !SearchGenerationGuard.isCurrent(requestId, activeSearchId)) return;
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                clarificationContext, utterance, localFallbackQuery);
        if (fallback == null) {
            if (failureMessage == null) showDestinationSetupRequired(requestId);
            else {
                destinationClarificationLease.remember(mergeClarificationContext(
                        clarificationContext, utterance), SystemClock.elapsedRealtime());
                destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
                showDestinationServiceFailure(requestId,
                        DestinationAiFallbackQuery.blockedPrompt(
                                clarificationContext, utterance, localFallbackQuery));
            }
            return;
        }

        usedLiteralAiFallbackForCurrentFlow = true;
        literalFallbackContextNoticeForCurrentFlow = fallback.contextNotice;
        requestedAreaConstraintForCurrentFlow = fallback.requestedArea;
        updateUnverifiedConditionState(null, utterance);
        recognizedText.setText("AI 해석 없이 말씀하신 동네와 장소 종류로 검색해요: " + fallback.query);
        showStatus("AI 해석 연결이 안 돼 말씀하신 표현으로 검색해요. 추가 조건은 확인하지 못했어요. 찾은 장소와 주소를 확인해 주세요.", false);
        resolveInterpretedDestination(fallback.query, localFallbackQuery, clarificationContext,
                requestId, requiresSpecialistVerification);
    }

    private void showDestinationServiceFailure(long requestId, String message) {
        if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
        destinationCorrectionContext.clear();
        showStatus(consumeConditionCancellationNote() + message, true);
        confirmButton.setText("다시 말하기");
        confirmButton.setOnClickListener(v -> {
            confirmButton.setVisibility(View.GONE);
            beginSpeech(SpeechPurpose.DESTINATION, true);
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private boolean hasUnverifiedSpecialistCondition(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String compact = text.toLowerCase(Locale.KOREAN).replaceAll("\\s+", "");
        return compact.contains("전문의") || compact.contains("전문 의료진")
                || compact.contains("전문의료진");
    }

    private boolean hasExplicitUnverifiedPlaceCondition(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String compact = text.toLowerCase(Locale.KOREAN).replaceAll("[\\p{P}\\s]", "");
        return compact.contains("주차가되는") || compact.contains("주차되는")
                || compact.contains("주차가능") || compact.contains("주차편한")
                || compact.contains("주차돼") || compact.contains("주차장있는")
                || compact.contains("주차장편한") || compact.contains("야간진료")
                || compact.contains("오늘진료") || compact.contains("지금진료")
                || compact.contains("진료중") || compact.contains("24시간")
                || compact.contains("휠체어") || compact.contains("장애인편의")
                || compact.contains("계단") || compact.contains("오르막")
                || compact.contains("경사로") || compact.contains("엘리베이터")
                || compact.contains("오래걷") || compact.contains("많이걷")
                || compact.contains("걷기힘들") || compact.contains("걷기어렵")
                || compact.contains("걷기편한") || compact.contains("가까운곳")
                || compact.contains("발이아파") || compact.contains("다리가아파")
                || compact.contains("무릎이아파") || compact.contains("오래못걸")
                || compact.contains("멀리못걸") || compact.contains("계단오르기힘들");
    }

    private void updateUnverifiedConditionState(DestinationAiClient.Result interpreted,
                                                String utterance) {
        LinkedHashSet<String> activeAtTurnStart =
                new LinkedHashSet<>(activeConditionDetailsForCurrentFlow.keySet());
        ArrayList<String> clauses = conditionClauses(utterance);
        ArrayList<ConditionAction> actions = new ArrayList<>();
        for (int clauseIndex = 0; clauseIndex < clauses.size(); clauseIndex++) {
            String clause = clauses.get(clauseIndex);
            LinkedHashSet<String> topics = conditionTopicsMentioned(clause);

            // Cancellation belongs to the topic span that contains it. A comma or a second
            // condition must not make one topic's cancellation erase every topic in the turn.
            for (String topic : topics) {
                if (hasTopicCancellation(clause, topic)) {
                    actions.add(ConditionAction.cancel(topic));
                }
            }

            for (String condition : detectedConditionLabels(clause)) {
                String topic = conditionTopic(condition);
                if (!hasTopicCancellation(clause, topic)) {
                    actions.add(ConditionAction.set(topic,
                            new ConditionState(condition, inferConditionPolarity(clause, topic))));
                }
            }

            if (interpreted == null || interpreted.conditions == null) continue;
            for (int i = 0; i < interpreted.conditions.size(); i++) {
                String condition = interpreted.conditions.get(i);
                String evidence = interpreted.conditionEvidence != null
                        && i < interpreted.conditionEvidence.size()
                        ? interpreted.conditionEvidence.get(i) : "";
                if (condition == null || condition.trim().isEmpty()
                        || evidence.isEmpty()
                        || !compactConditionText(clause).contains(compactConditionText(evidence))) continue;
                String topic = conditionTopic(condition);
                if (hasTopicCancellation(clause, topic)) continue;
                String polarity = interpreted.conditionPolarities != null
                        && i < interpreted.conditionPolarities.size()
                        ? interpreted.conditionPolarities.get(i) : "REQUIRE";
                actions.add(ConditionAction.set(topic,
                        new ConditionState(condition.trim(), polarity)));
            }
        }

        LinkedHashMap<String, ConditionAction> lastActions = new LinkedHashMap<>();
        for (ConditionAction action : actions) {
            applyConditionAction(action, activeAtTurnStart);
            lastActions.put(action.topic, action);
        }
        if (actions.isEmpty() && hasConditionCancellation(compactConditionText(utterance))
                && refersToPreviousCondition(utterance)
                && activeConditionDetailsForCurrentFlow.size() == 1) {
            String topic = activeConditionDetailsForCurrentFlow.keySet().iterator().next();
            ConditionAction action = ConditionAction.cancel(topic);
            applyConditionAction(action, activeAtTurnStart);
            lastActions.put(topic, action);
        }

        if (interpreted != null && interpreted.unverifiedConditions != null) {
            for (String condition : interpreted.unverifiedConditions) {
                String topic = conditionTopic(condition);
                ConditionAction latest = lastActions.get(topic);
                if (latest != null || activeConditionDetailsForCurrentFlow.containsKey(topic)
                        || cancelledConditionTopicsForCurrentFlow.contains(topic)) continue;
                hasUngroundedAiConditionForCurrentFlow = true;
            }
        }
        hasUnverifiedConditionsForCurrentFlow = !activeConditionDetailsForCurrentFlow.isEmpty();
        clearPendingConditionFlow();
    }

    private void applyConditionAction(ConditionAction action, LinkedHashSet<String> activeAtTurnStart) {
        if (action == null) return;
        if (action.cancelled) {
            LinkedHashSet<String> cancelled = new LinkedHashSet<>();
            cancelled.add(action.topic);
            for (String topic : expandConditionCancellations(cancelled)) {
                activeConditionDetailsForCurrentFlow.remove(topic);
                cancelledConditionTopicsForCurrentFlow.add(topic);
                if (activeAtTurnStart.contains(topic)) {
                    recentlyCancelledConditionTopicsForCurrentFlow.add(topic);
                }
            }
            if ("accessibility".equals(action.topic)) {
                for (String child : accessibilityChildTopics()) {
                    if (activeAtTurnStart.contains(child)) {
                        recentlyCancelledConditionTopicsForCurrentFlow.add("accessibility");
                        break;
                    }
                }
            }
            return;
        }
        cancelledConditionTopicsForCurrentFlow.remove(action.topic);
        recentlyCancelledConditionTopicsForCurrentFlow.remove(action.topic);
        if (accessibilityChildTopics().contains(action.topic)) {
            recentlyCancelledConditionTopicsForCurrentFlow.remove("accessibility");
        }
        if ("accessibility".equals(action.topic)) {
            cancelledConditionTopicsForCurrentFlow.removeAll(accessibilityChildTopics());
            recentlyCancelledConditionTopicsForCurrentFlow.removeAll(accessibilityChildTopics());
        }
        activeConditionDetailsForCurrentFlow.put(action.topic, action.state);
    }

    private String inferConditionPolarity(String text, String topic) {
        for (String clause : conditionClauses(text)) {
            if (hasTopicCancellation(clause, topic)) continue;
            if (!conditionTopicsMentioned(clause).contains(topic)) continue;
            String compact = compactConditionText(clause);
            if (hasPositiveDoubleNegativeCondition(compact)) return "REQUIRE";
            if (compact.contains("없는") || compact.contains("없고") || compact.contains("없어")
                    || compact.contains("피하") || compact.contains("싫")
                    || compact.contains("불편") || compact.contains("힘들")
                    || compact.contains("어렵") || compact.contains("무리")
                    || compact.contains("못") || compact.contains("아파")) return "AVOID";
            return "REQUIRE";
        }
        return "REQUIRE";
    }

    private LinkedHashSet<String> expandConditionCancellations(LinkedHashSet<String> requested) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(requested);
        if (expanded.contains("accessibility")) {
            expanded.remove("accessibility");
            expanded.addAll(accessibilityChildTopics());
            expanded.add("accessibility");
        }
        return expanded;
    }

    private LinkedHashSet<String> accessibilityChildTopics() {
        LinkedHashSet<String> children = new LinkedHashSet<>();
        children.add("wheelchair");
        children.add("elevator");
        children.add("accessible_facility");
        children.add("ramp");
        children.add("slope");
        children.add("stairs");
        children.add("mobility");
        return children;
    }

    private LinkedHashSet<String> cancelledConditionTopics(String text) {
        LinkedHashSet<String> cancelled = new LinkedHashSet<>();
        for (String clause : conditionClauses(text)) {
            for (String topic : conditionTopicsMentioned(clause)) {
                if (hasTopicCancellation(clause, topic)) cancelled.add(topic);
            }
        }
        return cancelled;
    }

    private boolean hasTopicCancellation(String clause, String topic) {
        return ConditionCancellationMatcher.hasTopicCancellation(clause, topic);
    }

    private boolean hasPositiveDoubleNegativeCondition(String compact) {
        return compact.contains("없으면안돼") || compact.contains("없으면안되")
                || compact.contains("없어서는안돼") || compact.contains("없어서는안되")
                || compact.contains("빼면안돼") || compact.contains("빼면안되")
                || compact.contains("안되면곤란") || compact.contains("없으면곤란")
                || compact.contains("없으면안됨") || compact.contains("없으면안됩니다")
                || compact.contains("없어선안돼") || compact.contains("없어선안되");
    }

    private boolean hasConditionCancellation(String compact) {
        return ConditionCancellationMatcher.hasCancellationText(compact);
    }

    private boolean refersToPreviousCondition(String text) {
        String compact = compactConditionText(text);
        return compact.contains("그건") || compact.contains("그거")
                || compact.contains("그조건") || compact.contains("그런조건");
    }

    private String compactConditionText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }

    private String conditionTopic(String condition) {
        String compact = compactConditionText(condition);
        if (compact.contains("주차") || compact.contains("발렛") || compact.contains("차댈")
                || compact.contains("세워둘")) return "parking";
        if (compact.contains("전문의")) return "specialist";
        if (compact.contains("계단") || compact.contains("층계")) return "stairs";
        if (compact.contains("경사로") || compact.contains("경사")) return "ramp";
        if (compact.contains("오르막")) return "slope";
        if (compact.contains("휠체어")) return "wheelchair";
        if (compact.contains("엘리베이터")) return "elevator";
        if (compact.contains("장애인화장실") || compact.contains("장애인전용화장실")) {
            return "accessible_facility";
        }
        if (compact.contains("장애인편의") || compact.contains("이동편의")
                || compact.contains("접근성")) return "accessibility";
        if (hasMobilityCondition(compact)) return "mobility";
        if (hasClinicHoursCondition(compact)) return "hours";
        if (compact.contains("가까운") || compact.contains("멀리")) return "distance";
        return "other:" + compact;
    }

    private ArrayList<String> detectedConditionLabels(String text) {
        ArrayList<String> labels = new ArrayList<>();
        for (String clause : conditionClauses(text)) {
            String compact = compactConditionText(clause);
            if (hasParkingCondition(compact) && shouldCreateCondition(clause, "parking")) {
                labels.add("주차 조건");
            }
            if (hasSpecialistCondition(compact) && shouldCreateCondition(clause, "specialist")) {
                labels.add("전문의 조건");
            }
            if (hasStairCondition(compact) && shouldCreateCondition(clause, "stairs")) {
                labels.add("계단 조건");
            }
            if (hasRampCondition(compact) && shouldCreateCondition(clause, "ramp")) {
                labels.add("경사로 조건");
            }
            if (hasSlopeCondition(compact) && shouldCreateCondition(clause, "slope")) {
                labels.add("오르막 조건");
            }
            if (hasWheelchairCondition(compact) && shouldCreateCondition(clause, "wheelchair")) {
                labels.add("휠체어 편의 조건");
            }
            if (hasElevatorCondition(compact) && shouldCreateCondition(clause, "elevator")) {
                labels.add("엘리베이터 조건");
            }
            if (hasAccessibleFacilityCondition(compact)) {
                String topic = compact.contains("장애인화장실")
                        || compact.contains("장애인전용화장실")
                        ? "accessible_facility" : "accessibility";
                if (shouldCreateCondition(clause, topic)) {
                    labels.add("accessible_facility".equals(topic)
                            ? "장애인 화장실 조건" : "장애인 편의 조건");
                }
            }
            if (hasMobilityCondition(compact) && shouldCreateCondition(clause, "mobility")) {
                labels.add("보행 조건");
            }
            if (hasClinicHoursCondition(compact) && shouldCreateCondition(clause, "hours")) {
                labels.add("진료 시간 조건");
            }
            if (hasDistanceCondition(compact) && shouldCreateCondition(clause, "distance")) {
                labels.add("거리 조건");
            }
        }
        return labels;
    }

    private boolean shouldCreateCondition(String clause, String topic) {
        return !hasTopicCancellation(clause, topic)
                && !ConditionCancellationMatcher.isNeutralOkayExpression(clause, topic);
    }

    private LinkedHashSet<String> conditionTopicsMentioned(String clause) {
        String compact = compactConditionText(clause);
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        if (compact.contains("주차") || compact.contains("발렛") || compact.contains("차댈")
                || compact.contains("세워둘")) {
            topics.add("parking");
        }
        if (compact.contains("전문의")) topics.add("specialist");
        if (compact.contains("계단") || compact.contains("층계")) topics.add("stairs");
        if (compact.contains("경사로")) topics.add("ramp");
        if (compact.contains("오르막")
                || (compact.contains("경사") && !compact.contains("경사로"))) topics.add("slope");
        if (compact.contains("휠체어")) topics.add("wheelchair");
        if (compact.contains("엘리베이터")) topics.add("elevator");
        if (compact.contains("장애인화장실") || compact.contains("장애인전용화장실")) {
            topics.add("accessible_facility");
        }
        if (compact.contains("장애인편의") || compact.contains("이동편의")
                || compact.contains("접근성")) topics.add("accessibility");
        if (hasMobilityCondition(compact)) topics.add("mobility");
        if (hasClinicHoursCondition(compact)) topics.add("hours");
        if (hasDistanceCondition(compact)) topics.add("distance");
        return topics;
    }

    private ArrayList<String> conditionClauses(String text) {
        ArrayList<String> clauses = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return clauses;
        String normalized = text.toLowerCase(Locale.KOREAN)
                .replaceAll("[,，、;；.!?。！？\\r\\n]+", " | ")
                .replaceAll("[\\p{P}&&[^|]]", " ").trim();
        for (String clause : normalized.split("\\s*\\|\\s*|\\s+(?:그리고|그런데|하지만|대신|반면|아니|아니고|아니라)\\s+")) {
            if (!clause.trim().isEmpty()) clauses.add(clause.trim());
        }
        return clauses;
    }

    private LinkedHashSet<String> conditionTopicsMentionedInText(String text) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (String clause : conditionClauses(text)) topics.addAll(conditionTopicsMentioned(clause));
        return topics;
    }

    private boolean hasParkingCondition(String compact) {
        return compact.contains("주차가능") || compact.contains("주차편한")
                || compact.contains("주차돼야") || compact.contains("주차되면좋")
                || compact.contains("주차돼있는곳") || compact.contains("주차가되는곳")
                || compact.contains("주차되는곳") || compact.contains("주차장있는곳")
                || compact.contains("주차장이있는곳") || compact.contains("주차장있는병원")
                || compact.contains("주차장이있는병원") || compact.contains("주차장있는의원")
                || compact.contains("주차장이있는의원") || compact.contains("주차장있으면")
                || compact.contains("주차장편한") || compact.contains("주차되어야")
                || compact.contains("주차공간이필요") || compact.contains("주차공간필요")
                || compact.contains("주차공간이있으면") || compact.contains("주차공간있으면")
                || compact.contains("주차공간있는곳") || compact.contains("주차공간이있는곳")
                || compact.contains("주차공간있는병원") || compact.contains("주차공간을찾")
                || compact.contains("주차자리있으면") || compact.contains("주차자리있는곳")
                || compact.contains("주차장에공간이있으면") || compact.contains("차댈수있어야")
                || compact.contains("차댈수있으면") || compact.contains("차댈수있는곳")
                || compact.contains("차댈수있는병원") || compact.contains("세워둘데가있어야")
                || compact.contains("세워둘데필요") || compact.contains("세워둘공간필요")
                || compact.contains("발렛가능") || compact.contains("발렛되는")
                || compact.contains("발렛있는") || compact.contains("주차대행")
                || compact.contains("주차필요") || compact.contains("주차필수")
                || compact.contains("주차가필요")
                || (compact.contains("주차") && hasPositiveDoubleNegativeCondition(compact))
                || compact.contains("주차없는곳") || compact.contains("주차장이없는곳")
                || compact.contains("주차가없는곳") || compact.contains("주차없는병원")
                || compact.contains("주차장이없는병원");
    }

    private boolean hasSpecialistCondition(String compact) {
        return compact.contains("전문의있는") || compact.contains("전문의가있는")
                || compact.contains("전문의계신") || compact.contains("전문의필요")
                || compact.contains("전문병원");
    }

    private boolean hasStairCondition(String compact) {
        return compact.contains("계단없") || compact.contains("계단피하")
                || compact.contains("계단이힘") || compact.contains("계단오르기힘")
                || compact.contains("계단오르기어렵") || compact.contains("계단적은")
                || compact.contains("층계없") || compact.contains("층계힘들")
                || (compact.contains("계단") && (compact.contains("싫")
                || compact.contains("불편") || compact.contains("힘들")
                || compact.contains("어렵") || compact.contains("피하")));
    }

    private boolean hasRampCondition(String compact) {
        return compact.contains("경사로있") || compact.contains("경사로필요")
                || compact.contains("경사로가능") || compact.contains("경사로없")
                || compact.contains("경사로편한");
    }

    private boolean hasSlopeCondition(String compact) {
        return compact.contains("오르막없") || compact.contains("오르막힘들")
                || compact.contains("경사완만")
                || (!compact.contains("경사로") && compact.contains("경사없"));
    }

    private boolean hasWheelchairCondition(String compact) {
        return compact.contains("휠체어가능") || compact.contains("휠체어이용")
                || compact.contains("휠체어편한") || compact.contains("휠체어접근")
                || compact.contains("휠체어필요");
    }

    private boolean hasElevatorCondition(String compact) {
        return compact.contains("엘리베이터있") || compact.contains("엘리베이터필요")
                || compact.contains("엘리베이터있는") || compact.contains("엘리베이터편한");
    }

    private boolean hasAccessibleFacilityCondition(String compact) {
        return compact.contains("장애인편의") || compact.contains("이동편의")
                || compact.contains("접근성") || compact.contains("장애인시설")
                || compact.contains("장애인화장실") || compact.contains("장애인전용화장실");
    }

    private boolean hasDistanceCondition(String compact) {
        return compact.contains("가까운곳") || compact.contains("가까운병원")
                || compact.contains("가까운피부과") || compact.contains("가까운약국")
                || compact.contains("가까운시장") || compact.contains("멀리못걸");
    }

    private boolean hasMobilityCondition(String compact) {
        return compact.contains("보행") || compact.contains("무릎") || compact.contains("발이아")
                || compact.contains("다리가아") || compact.contains("다리가불편")
                || compact.contains("다리불편")
                || compact.contains("오래걷") || compact.contains("많이걷")
                || compact.contains("걷기힘들") || compact.contains("걷기어렵")
                || compact.contains("오래못걸") || compact.contains("멀리못걸");
    }

    private boolean hasClinicHoursCondition(String compact) {
        return compact.contains("야간진료") || compact.contains("오늘진료")
                || compact.contains("지금진료") || compact.contains("진료중")
                || compact.contains("24시간") || compact.contains("영업시간");
    }

    private String withoutSpecialistCondition(String query) {
        if (query == null) return "";
        String value = query.replaceAll("전문\\s*의료진(?:이)?\\s*(?:(?:있는|계신|근무하는|진료하는)\\s*)?", " ")
                .replaceAll("전문의\\s*(?:가\\s*)?(?:(?:있는|계신|근무하는|진료하는)\\s*)?", " ")
                .replaceAll("(?:있는|계신)\\s*(?:곳|병원|의원)", " ")
                .replaceAll("\\s+", " ").trim();
        return value;
    }

    private void askSpecialistConsent(String query, String localFallbackQuery,
                                      String clarificationContext, long requestId) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        String safeQuery = buildSpecialistSearchQuery(query, localFallbackQuery, clarificationContext);
        if (safeQuery.length() < 2) {
            awaitingSpecialistVerification = true;
            destinationClarificationLease.remember(localFallbackQuery,
                    SystemClock.elapsedRealtime());
            askForDestinationAgain("전문의 여부를 확인할 수 있는 자료가 없어요. 찾을 장소나 동네를 다시 말씀해 주세요.");
            return;
        }
        pendingSpecialistSearchQuery = safeQuery;
        pendingSpecialistSearchRequestId = requestId;
        pendingSpecialistConsentExpiresAtElapsedRealtime = SystemClock.elapsedRealtime()
                + SPECIALIST_CONSENT_TTL_MS;
        pendingSpecialistConsentExpiryRunnable = () -> {
            if (pendingSpecialistSearchRequestId == requestId
                    && pendingSpecialistConsentExpiresAtElapsedRealtime <= SystemClock.elapsedRealtime()) {
                cancelPendingAutoListen();
                if (speechActive) invalidateSpeechCapture();
                clearPendingSpecialistConsent();
                confirmButton.setVisibility(View.GONE);
                cancelButton.setVisibility(View.GONE);
                specialistConditionForCurrentFlow = false;
                specialistConditionRequestId = -1L;
                specialistStatusUnverifiedForCandidate = false;
                awaitingSpecialistVerification = false;
                destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
                showStatus("확인 시간이 지났어요. 목적지를 다시 말씀해 주세요.", true);
            }
        };
        handler.postDelayed(pendingSpecialistConsentExpiryRunnable, SPECIALIST_CONSENT_TTL_MS);
        specialistConsentAttempts = 0;
        String originalIntent = withoutSpecialistCondition(DestinationFallbackQuery.combine("",
                originalSpecialistConstraint(localFallbackQuery, clarificationContext)));
        ArrayList<String> originallyRequestedTypes = findClinicalTypes(originalIntent);
        ArrayList<String> interpretedTypes = findClinicalTypes(withoutSpecialistCondition(query));
        boolean needsTypeConfirmation = originallyRequestedTypes.isEmpty() && !interpretedTypes.isEmpty();
        String prompt = needsTypeConfirmation
                ? "AI가 " + interpretedTypes.get(0) + "로 이해했어요. " + interpretedTypes.get(0)
                    + "로 찾아볼까요? 전문의 여부는 확인되지 않았어요."
                : "전문의 여부는 확인할 수 없어요. 확인 없이 " + safeQuery + " 장소만 찾아볼까요?";
        pendingSpecialistConsentPrompt = prompt;
        pendingSpecialistConsentButtonText = needsTypeConfirmation
                ? interpretedTypes.get(0) + "로 검색" : "전문의 확인 없이 검색";
        recognizedText.setText(needsTypeConfirmation
                ? "AI가 해석한 진료과: " + interpretedTypes.get(0) + " · 전문의 여부 미확인 · 검색어: " + safeQuery
                : "전문의 여부는 확인되지 않았어요. 검색어: " + safeQuery);
        destinationText.setText("");
        showStatus(prompt, false);
        confirmButton.setText(pendingSpecialistConsentButtonText);
        confirmButton.setOnClickListener(v -> resolveSpecialistConsent(requestId, true));
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("검색 취소");
        cancelButton.setOnClickListener(v -> resolveSpecialistConsent(requestId, false));
        cancelButton.setVisibility(View.VISIBLE);
        String spokenPrompt = prompt + " 괜찮으면 네, 원하지 않으면 아니요라고 말씀해 주세요.";
        if (shouldUseScreenOnlyInteraction()) speak(spokenPrompt);
        else speakThenListen(spokenPrompt, SpeechPurpose.SPECIALIST_CONSENT);
    }

    private void handleSpecialistConsent(long requestId, String utterance) {
        if (requestId != activeSearchId || pendingSpecialistSearchRequestId != requestId
                || !isPendingSpecialistConsentCurrent()) return;
        String compact = utterance == null ? "" : utterance.replaceAll("[\\p{P}\\s]", "");
        boolean asksQuestion = utterance != null
                && (utterance.contains("?") || utterance.contains("？"));
        if (compact.matches(".*(?:검색하지마|검색하지말|찾지마|찾지말|취소|그만|원하지않|싫어).*")) {
            resolveSpecialistConsent(requestId, false);
            return;
        }
        if (!asksQuestion && compact.matches("^(?:(?:네|예|응|어)?(?:전문의(?:는)?(?:없어도|상관없)(?:돼|돼요|됩니다|괜찮아|괜찮아요)?|전문의확인(?:은)?안해도(?:돼|돼요|됩니다)?|전문의확인없이(?:검색)?|(?:그냥|그래도)검색(?:해|해줘|해주세요|해요|하세요|해도돼|해도돼요)?))$")) {
            resolveSpecialistConsent(requestId, true);
            return;
        }
        DestinationConfirmationParser.Decision decision = DestinationConfirmationParser
                .classifyActiveYesNoAnswer(utterance);
        if (decision == DestinationConfirmationParser.Decision.YES) {
            resolveSpecialistConsent(requestId, true);
        } else if (decision == DestinationConfirmationParser.Decision.NO) {
            resolveSpecialistConsent(requestId, false);
        } else if (specialistConsentAttempts++ == 0) {
            String prompt = pendingSpecialistConsentPrompt
                    + " 괜찮으면 네, 원하지 않으면 아니요라고 말씀해 주세요.";
            showStatus(prompt, false);
            speakThenListen(prompt, SpeechPurpose.SPECIALIST_CONSENT);
        } else {
            showStatus("음성으로 확인하기 어려워요. 아래 ‘전문의 확인 없이 검색’ 또는 ‘검색 취소’를 눌러 주세요.", true);
        }
    }

    private void resolveSpecialistConsent(long expectedRequestId, boolean accepted) {
        if (expectedRequestId != activeSearchId || pendingSpecialistSearchRequestId != expectedRequestId) return;
        if (!isPendingSpecialistConsentCurrent()) {
            if (!pendingSpecialistSearchQuery.trim().isEmpty()) {
                clearPendingSpecialistConsent();
                confirmButton.setVisibility(View.GONE);
                cancelButton.setVisibility(View.GONE);
                showStatus("확인 시간이 지났어요. 목적지를 다시 말씀해 주세요.", true);
            }
            return;
        }
        String query = pendingSpecialistSearchQuery;
        long requestId = pendingSpecialistSearchRequestId;
        cancelPendingAutoListen();
        if (speechActive) invalidateSpeechCapture();
        clearPendingSpecialistConsent();
        confirmButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        if (!accepted) {
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
            awaitingSpecialistVerification = false;
            confirmButton.setText("조건을 바꿔 다시 말하기");
            confirmButton.setOnClickListener(v -> {
                confirmButton.setVisibility(View.GONE);
                beginSpeech();
            });
            confirmButton.setVisibility(View.VISIBLE);
            cancelButton.setText("처음으로");
            cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요."));
            cancelButton.setVisibility(View.VISIBLE);
            showStatus("전문의 여부를 확인하지 않는 검색은 하지 않았어요. 조건을 바꿔 다시 말씀하시거나 처음으로 돌아가세요.", true);
            return;
        }
        specialistConditionForCurrentFlow = true;
        specialistStatusUnverifiedForCandidate = true;
        showStatus("전문의 여부는 확인되지 않았어요. " + query + " 장소를 검색할게요.", true);
        searchPlaces(query, requestId);
    }

    private void clearPendingSpecialistConsent() {
        if (pendingSpecialistConsentExpiryRunnable != null) {
            handler.removeCallbacks(pendingSpecialistConsentExpiryRunnable);
            pendingSpecialistConsentExpiryRunnable = null;
        }
        pendingSpecialistSearchQuery = "";
        pendingSpecialistConsentPrompt = "";
        pendingSpecialistConsentButtonText = "";
        pendingSpecialistSearchRequestId = -1L;
        pendingSpecialistConsentExpiresAtElapsedRealtime = 0L;
        specialistConsentAttempts = 0;
        confirmButton.setOnClickListener(v -> openConfirmedRoute());
        cancelButton.setOnClickListener(v -> resetFlow("취소했어요. 다시 말씀하셔도 돼요."));
    }

    private boolean isPendingSpecialistConsentCurrent() {
        return pendingSpecialistSearchRequestId == activeSearchId
                && !pendingSpecialistSearchQuery.trim().isEmpty()
                && pendingSpecialistConsentExpiresAtElapsedRealtime > SystemClock.elapsedRealtime();
    }

    private String buildSpecialistSearchQuery(String query, String localFallbackQuery,
                                              String clarificationContext) {
        String originalConstraint = originalSpecialistConstraint(localFallbackQuery, clarificationContext);
        String intent = withoutSpecialistCondition(
                DestinationFallbackQuery.combine("", originalConstraint));
        ArrayList<String> allowedTypes = findClinicalTypes(intent);
        boolean aiAddedClinicalType = allowedTypes.isEmpty()
                && !findClinicalTypes(withoutSpecialistCondition(query)).isEmpty();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addFilteredSearchTerms(terms, withoutSpecialistCondition(query), allowedTypes);
        if (!aiAddedClinicalType) addFilteredSearchTerms(terms, intent, allowedTypes);
        addAdministrativeTerms(terms, clarificationContext);
        addAdministrativeTerms(terms, localFallbackQuery);
        return String.join(" ", terms);
    }

    private String originalSpecialistConstraint(String localFallbackQuery, String clarificationContext) {
        if (clarificationContext != null && !clarificationContext.trim().isEmpty()) {
            for (String line : clarificationContext.split("[\\r\\n]+")) {
                if (hasUnverifiedSpecialistCondition(line)) return line;
            }
        }
        return localFallbackQuery == null ? "" : localFallbackQuery;
    }

    private ArrayList<String> findClinicalTypes(String text) {
        ArrayList<String> found = new ArrayList<>();
        String remaining = text == null ? "" : text;
        for (String type : CLINICAL_TYPES) {
            if (remaining.contains(type)) {
                found.add(type);
                remaining = remaining.replace(type, " ");
            }
        }
        return found;
    }

    private void addFilteredSearchTerms(LinkedHashSet<String> terms, String phrase,
                                        List<String> allowedTypes) {
        if (phrase == null) return;
        for (String term : phrase.trim().split("\\s+")) {
            String normalized = term.replaceAll("^[,，、.!?？]+|[,，、.!?？]+$", "");
            if (normalized.length() < 2 || normalized.matches("(?:있는|계신|쪽|근처|주변|가까운|가까이|찾아|주세요|해주세요|곳|데)")) continue;
            boolean conflictingClinicalType = false;
            for (String type : CLINICAL_TYPES) {
                boolean compatible = allowedTypes.isEmpty();
                for (String allowed : allowedTypes) {
                    if (allowed.contains(type)) {
                        compatible = true;
                        break;
                    }
                }
                if (compatible) continue;
                if (normalized.contains(type)) {
                    conflictingClinicalType = true;
                    break;
                }
            }
            if (!conflictingClinicalType) terms.add(normalized);
        }
    }

    private void addAdministrativeTerms(LinkedHashSet<String> terms, String phrase) {
        if (phrase == null) return;
        for (String term : phrase.trim().split("\\s+")) {
            if (term.matches("(?:서울|부산|대구|인천|광주|대전|울산|세종|제주|경기|강원|충북|충남|전북|전남|경북|경남)"
                    + "|[가-힣]{1,10}(?:특별시|광역시|자치시|도|시|구|군|읍|면|동|리)")) {
                terms.add(term);
            }
        }
    }

    private String mergeClarificationContext(String previous, String current) {
        String left = previous == null ? "" : previous.trim();
        String right = current == null ? "" : current.trim();
        String merged = left.isEmpty() ? right : right.isEmpty() ? left : left + "\n" + right;
        if (merged.length() <= 400) return merged;
        return merged.substring(merged.length() - 400);
    }

    private void searchPlaces(String query, long requestId) {
        clearDestinationCandidateContext();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        String naverClientId = settings.naverApiClientId();
        String naverClientSecret = settings.naverApiClientSecret();
        String requestedAreaQuery = requestedAreaConstraintForCurrentFlow;
        someSearchResultsExcludedForCurrentFlow = false;
        excludedByAreaForCurrentFlow = 0;
        excludedByCategoryForCurrentFlow = 0;
        searchUsedNoExplicitAreaForCurrentFlow = false;
        if (naverClientId.trim().isEmpty() || naverClientSecret.trim().isEmpty()) {
            showDestinationSetupRequired(requestId);
            return;
        }
        try {
            resolverWorker.execute(() -> {
                try {
                    NaverLocalSearchClient.Result result = NaverLocalSearchClient.search(
                            naverClientId, naverClientSecret, query, requestedAreaQuery);
                    long resultReceivedAt = SystemClock.elapsedRealtime();
                    handler.post(() -> postPlaceCandidates(query, result,
                            requestedAreaQuery, requestId, resultReceivedAt));
                } catch (Exception e) {
                    handler.post(() -> showDestinationServiceFailure(requestId,
                            "네이버 장소 검색에 실패했어요. 네트워크와 API 설정을 확인해 주세요."));
                }
            });
        } catch (RejectedExecutionException e) {
            destinationCorrectionContext.clear();
            showStatus("잠시 뒤 다시 말씀해 주세요.", true);
        }
    }

    private void postPlaceCandidates(String query, NaverLocalSearchClient.Result result,
                                     String requestedAreaQuery, long requestId,
                                     long resultReceivedAtElapsedRealtime) {
        if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
        ArrayList<Candidate> candidates = new ArrayList<>();
        List<NaverLocalSearchClient.Place> places = result == null ? null : result.places;
        if (places != null) {
            for (NaverLocalSearchClient.Place place : places) {
                if (place == null) continue;
                boolean duplicate = false;
                for (Candidate existing : candidates) {
                    if (existing.name.equals(place.name)
                            && existing.address.equals(place.address)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) candidates.add(new Candidate(place.name, place.address,
                        place.latitude, place.longitude, false));
            }
        }
        someSearchResultsExcludedForCurrentFlow = result != null
                && result.rejectedByRequestedConstraints > 0 && !candidates.isEmpty();
        excludedByAreaForCurrentFlow = result == null ? 0 : result.rejectedByArea;
        excludedByCategoryForCurrentFlow = result == null ? 0 : result.rejectedByCategory;
        searchUsedNoExplicitAreaForCurrentFlow = requestedAreaQuery == null
                || requestedAreaQuery.trim().isEmpty();
        if (candidates.isEmpty()) {
            clearDestinationCandidateContext();
            destinationCorrectionContext.clear();
            String failure = DestinationSearchFailurePolicy.messageFor(result);
            handleDestinationSearchFailure(failure);
            return;
        }
        long resultGeneration = destinationCandidateContext.begin(resultReceivedAtElapsedRealtime);
        scheduleDestinationCandidateExpiry(resultGeneration,
                destinationCandidateContext.expiresAtElapsedRealtime());
        if (candidates.size() == 1) {
            if (!hasCandidatePresentationWindow(resultGeneration)) return;
        } else if (!hasCandidateListPresentationWindow(resultGeneration)) {
            return;
        }
        String activeCorrectionContext = destinationCorrectionContext.current(
                resultReceivedAtElapsedRealtime);
        if (!activeCorrectionContext.isEmpty()) {
            destinationCorrectionContext.remember(activeCorrectionContext,
                    resultReceivedAtElapsedRealtime);
        }
        destinationSearchFailureCount = 0;
        confirmationCandidateChoices = null;
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        if (candidates.size() == 1) {
            repeatedAmbiguousDestination.clear();
            rememberedAmbiguousDestinationCandidates.clear();
            showCandidate(candidates.get(0), requestId);
            return;
        }
        pendingCandidateChoices = candidates;
        voiceCandidateChoices = buildVoiceCandidateChoices(candidates);
        candidateSelectionAttempts = 0;
        ArrayList<String> candidateAddresses = new ArrayList<>();
        for (Candidate candidate : candidates) candidateAddresses.add(candidate.address);
        String districtReminder = DestinationDistrictAmbiguity.reminder(
                requestedAreaQuery, candidateAddresses);
        if (!districtReminder.isEmpty()) {
            districtClarification.begin(candidateAddresses);
            if (repeatedAmbiguousDestination.remember(query, requestedAreaQuery, candidateAddresses)) {
                rememberedAmbiguousDestinationCandidates.clear();
                rememberedAmbiguousDestinationCandidates.addAll(candidates);
            } else {
                rememberedAmbiguousDestinationCandidates.clear();
            }
        } else {
            districtClarification.clear();
            repeatedAmbiguousDestination.clear();
            rememberedAmbiguousDestinationCandidates.clear();
        }
        askForCandidateByVoice(districtReminder);
    }

    private void handleDestinationSearchFailure(String message) {
        destinationCorrectionContext.clear();
        destinationSearchFailureCount++;
        if (specialistConditionForCurrentFlow) {
            destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
            awaitingSpecialistVerification = true;
        }
        if (!activeSearchFromTextEntry
                && DestinationSearchFailurePolicy.shouldRetryVoice(destinationSearchFailureCount)) {
            String prompt = consumeConditionCancellationNote()
                    + message + " 동네와 장소 이름을 다시 말씀해 주세요.";
            showStatus(prompt, false);
            speakThenListen(prompt, SpeechPurpose.DESTINATION);
            return;
        }
        String failurePrompt = consumeConditionCancellationNote() + (activeSearchFromTextEntry
                ? "장소를 찾지 못했어요. 네트워크를 확인하거나 다른 동네를 글자로 입력해 주세요."
                : "지금은 장소를 찾기 어려워요. 네트워크를 확인하거나 다른 동네를 말씀해 주세요. 글자로 입력할 수도 있어요.");
        showStatus(failurePrompt, true);
        recognizedText.setText(activeSearchFromTextEntry
                ? "입력한 장소를 찾지 못했어요. 다른 장소를 글자로 입력해 주세요."
                : "장소 검색이 두 번 실패했어요. 다른 동네나 장소를 입력해 주세요.");
        confirmButton.setText(activeSearchFromTextEntry ? "다른 장소를 글자로 입력" : "다른 동네로 다시 말하기");
        confirmButton.setOnClickListener(v -> {
            confirmButton.setVisibility(View.GONE);
            if (activeSearchFromTextEntry) {
                showTextEntry();
            } else {
                destinationSearchFailureCount = 0;
                beginSpeech(SpeechPurpose.DESTINATION, true);
            }
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void showCandidate(Candidate candidate) {
        showCandidate(candidate, activeSearchId);
    }

    private void showCandidate(Candidate candidate, long originatingSearchId) {
        if (!SearchGenerationGuard.isCurrent(originatingSearchId, activeSearchId)) return;
        if (!ensureCandidatePresentationWindow()) return;
        long presentationGeneration = destinationCandidateContext.generation();
        repeatedAmbiguousDestination.clear();
        rememberedAmbiguousDestinationCandidates.clear();
        awaitingRepeatedDistrictClarification = false;
        // Candidate confirmation becomes the sole answer consumer; an unanswered condition
        // round is safely treated as retained before the candidate prompt is shown.
        if (pendingActionGate.isPending(SystemClock.elapsedRealtime())
                || pendingActionGate.hasExpiredAction()
                || !pendingBulkConditionRemovalTopics.isEmpty()) {
            clearPendingConditionFlow();
        }
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        if (pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
            confirmationCandidateChoices = new ArrayList<>(pendingCandidateChoices);
        }
        setPendingCandidate(candidate);
        cancelButton.setOnClickListener(v -> resetFlow("취소했어요. 다시 목적지를 말씀해 주세요."));
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        confirmationListenAttempts = 0;
        cancelButton.setText("처음으로");
        destinationText.setText(candidateDisplayName(candidate) + "\n" + candidate.address);
        String specialistNote = specialistStatusUnverifiedForCandidate
                ? "전문의 여부는 확인되지 않았어요. " : "";
        String conditionNote = consumeConditionCancellationNote() + buildUnverifiedConditionNote();
        if (hasUngroundedAiConditionForCurrentFlow) {
            conditionNote += "조건으로 말씀하신 내용이 확인되지 않아 검색에 넣지 않았어요. 필요하면 다시 말씀해 주세요. ";
        }
        String filterNote = buildFilterExclusionNote();
        String areaNote = searchUsedNoExplicitAreaForCurrentFlow
                ? "행정구역을 따로 지정하지 않고 찾은 결과예요. " : "";
        areaNote += DestinationDistrictAmbiguity.confirmationNote(
                requestedAreaConstraintForCurrentFlow, candidate.address);
        districtClarification.clear();
        awaitingDistrictClarification = false;
        String fallbackAddress = candidateVoiceAddress(candidate);
        String spokenAddress = fallbackAddress.isEmpty() ? candidate.address : fallbackAddress;
        String literalFallbackNote = usedLiteralAiFallbackForCurrentFlow
                ? (literalFallbackContextNoticeForCurrentFlow == null
                ? "" : literalFallbackContextNoticeForCurrentFlow)
                + "AI 해석 없이 검색했어요. 추가 조건은 확인되지 않았어요. " : "";
        String prompt = candidate.nameIsFallback
                ? literalFallbackNote + specialistNote + conditionNote + filterNote + areaNote + "장소 이름은 확인되지 않았어요. 확인된 주소는 "
                        + (fallbackAddress.isEmpty() ? candidate.address : fallbackAddress)
                        + "예요. " + (travelMode == TravelMode.TAXI
                        ? "이 주소가 목적지 맞나요? 맞으면 카카오 T에서 택시를 호출할게요."
                        : "이 주소로 대중교통 길을 안내할까요?")
                : travelMode == TravelMode.TAXI
                        ? literalFallbackNote + specialistNote + conditionNote + filterNote + areaNote + candidate.name + " " + candidate.address + "이 목적지 맞나요? 맞으면 카카오 T에서 택시를 호출할게요."
                        : literalFallbackNote + specialistNote + conditionNote + filterNote + areaNote + candidate.name + ", " + spokenAddress + " 주소예요. 이 주소로 대중교통 길을 안내할까요?";
        confirmButton.setText(travelMode == TravelMode.TAXI ? "확인 후 택시 호출" : "네, 대중교통 경로 열기");
        showStatus(prompt, false);
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        String spokenPrefix = travelMode == TravelMode.TAXI && !candidate.nameIsFallback
                ? candidate.name + " " + candidate.address + ". " : "";
        String spokenPrompt = spokenPrefix + prompt + " 맞으면 맞다고 말씀해 주세요.";
        if (!hasCandidatePresentationWindow(presentationGeneration)) return;
        confirmationPromptText = spokenPrompt;
        confirmationPromptGate.openForDisplay();
        if (!hasCandidatePresentationWindow(presentationGeneration)) return;
        if (shouldUseScreenOnlyInteraction()) speakAudio(spokenPrompt, true);
        else speakThenListen(spokenPrompt, SpeechPurpose.CONFIRMATION);
    }

    private String buildUnverifiedConditionNote() {
        ArrayList<String> descriptions = new ArrayList<>();
        for (java.util.Map.Entry<String, ConditionState> entry
                : activeConditionDetailsForCurrentFlow.entrySet()) {
            if ("specialist".equals(entry.getKey())) continue;
            descriptions.add(describeCondition(entry.getKey(), entry.getValue()));
        }
        if (descriptions.isEmpty()) return "";
        if (descriptions.size() > 2) {
            return "말씀하신 추가 조건 " + descriptions.size()
                    + "가지는 실제 장소 정보에서 확인하지 못했어요. ";
        }
        return "말씀하신 " + String.join(" 그리고 ", descriptions)
                + "은 실제 장소 정보에서 확인하지 못했어요. ";
    }

    private String buildConditionCancellationNote() {
        if (recentlyCancelledConditionTopicsForCurrentFlow.isEmpty()) return "";
        if (recentlyCancelledConditionTopicsForCurrentFlow.contains("accessibility")) {
            return "장애인 편의 조건을 뺐어요. 필요하면 다시 말씀해 주세요. ";
        }
        ArrayList<String> names = new ArrayList<>();
        for (String topic : recentlyCancelledConditionTopicsForCurrentFlow) {
            String name = conditionCancellationName(topic);
            if (!names.contains(name)) names.add(name);
        }
        if (names.isEmpty()) return "";
        int accessibilityCount = 0;
        for (String topic : recentlyCancelledConditionTopicsForCurrentFlow) {
            if (accessibilityChildTopics().contains(topic)) accessibilityCount++;
        }
        if (accessibilityCount >= 3) {
            return "일부 이동 편의 조건을 뺐어요. 필요하면 다시 말씀해 주세요. ";
        }
        if (names.size() == 1) {
            return names.get(0) + " 조건을 뺐어요. 필요하면 다시 말씀해 주세요. ";
        }
        return String.join(", ", names) + " 조건을 뺐어요. 필요하면 다시 말씀해 주세요. ";
    }

    private String consumeConditionCancellationNote() {
        String note = buildConditionCancellationNote();
        recentlyCancelledConditionTopicsForCurrentFlow.clear();
        return note;
    }

    private String conditionCancellationName(String topic) {
        if ("parking".equals(topic)) return "주차";
        if ("specialist".equals(topic)) return "전문의";
        if ("stairs".equals(topic)) return "계단";
        if ("ramp".equals(topic)) return "경사로";
        if ("slope".equals(topic)) return "오르막";
        if ("wheelchair".equals(topic)) return "휠체어 이용";
        if ("elevator".equals(topic)) return "엘리베이터";
        if ("accessible_facility".equals(topic)) return "장애인 편의시설";
        if ("accessibility".equals(topic)) return "장애인 편의";
        if ("mobility".equals(topic)) return "보행";
        if ("hours".equals(topic)) return "진료 시간";
        if ("distance".equals(topic)) return "거리";
        return "추가";
    }

    private String describeCondition(String topic, ConditionState state) {
        boolean avoid = state != null && "AVOID".equals(state.polarity);
        if ("parking".equals(topic)) return avoid ? "주차가 없는 곳" : "주차가 가능한 곳";
        if ("specialist".equals(topic)) return avoid ? "전문의가 없는 곳" : "전문의가 있는 곳";
        if ("stairs".equals(topic)) return avoid ? "계단이 없는 곳" : "계단이 있는 곳";
        if ("ramp".equals(topic)) return avoid ? "경사로가 없는 곳" : "경사로가 있는 곳";
        if ("slope".equals(topic)) return avoid ? "오르막이 적은 곳" : "오르막이 있는 곳";
        if ("wheelchair".equals(topic)) return avoid ? "휠체어 이용이 어려운 곳" : "휠체어 이용이 가능한 곳";
        if ("elevator".equals(topic)) return avoid ? "엘리베이터가 없는 곳" : "엘리베이터가 있는 곳";
        if ("accessible_facility".equals(topic)) return avoid ? "장애인 편의시설이 부족한 곳" : "장애인 편의시설이 있는 곳";
        if ("accessibility".equals(topic)) return avoid ? "이동이 불편한 곳" : "이동 편의시설이 있는 곳";
        if ("mobility".equals(topic)) return avoid ? "걷기 불편한 곳" : "걷기 편한 곳";
        if ("hours".equals(topic)) return avoid ? "원하지 않는 진료 시간의 곳" : "원하시는 시간에 진료하는 곳";
        if ("distance".equals(topic)) return avoid ? "먼 곳" : "가까운 곳";
        String label = state == null ? "추가" : state.label.replaceAll("\\s*조건\\s*", "").trim();
        return label.isEmpty() ? "추가 조건" : label + " 조건";
    }

    private String buildFilterExclusionNote() {
        if (!someSearchResultsExcludedForCurrentFlow) return "";
        if (excludedByAreaForCurrentFlow > 0 && excludedByCategoryForCurrentFlow > 0) {
            return "동네 기준에서 " + excludedByAreaForCurrentFlow + "곳, 장소 종류 기준에서 "
                    + excludedByCategoryForCurrentFlow + "곳을 각각 제외했어요. ";
        }
        if (excludedByAreaForCurrentFlow > 0) {
            return "요청하신 동네와 맞지 않는 " + excludedByAreaForCurrentFlow + "곳은 제외했어요. ";
        }
        if (excludedByCategoryForCurrentFlow > 0) {
            return "요청하신 장소 종류가 확인되지 않는 " + excludedByCategoryForCurrentFlow
                    + "곳은 제외했어요. ";
        }
        return "일부 검색 결과는 요청하신 조건과 맞지 않아 제외했어요. ";
    }

    private String shortArea(String address) {
        if (address == null || address.trim().isEmpty()) return "주소 확인 필요";
        String district = "";
        String neighborhood = "";
        String[] parts = address.trim().split("\\s+");
        for (String part : parts) {
            if (part.endsWith("구") || part.endsWith("군")) district = part;
            if (part.endsWith("동") || part.endsWith("읍")
                    || part.endsWith("면") || part.endsWith("리")) neighborhood = part;
        }
        if (!district.isEmpty() && !neighborhood.isEmpty()) return district + " " + neighborhood;
        if (!neighborhood.isEmpty()) return neighborhood;
        if (!district.isEmpty()) return district;
        return parts.length > 1 ? parts[0] + " " + parts[1] : parts[0];
    }

    private ArrayList<Candidate> buildVoiceCandidateChoices(List<Candidate> candidates) {
        ArrayList<Candidate> voiceChoices = new ArrayList<>();
        boolean hasFallback = false;
        ArrayList<CandidateVoiceLabelBuilder.Place> labelInputs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            candidate.voiceOrdinal = i + 1;
            hasFallback |= candidate.nameIsFallback;
            labelInputs.add(new CandidateVoiceLabelBuilder.Place(candidate.name,
                    shortArea(candidate.address),
                    DestinationAddressMatcher.voiceRoadAndNumber(candidate.address),
                    candidate.address, candidate.nameIsFallback));
        }
        List<String> labels = CandidateVoiceLabelBuilder.build(labelInputs);
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            candidate.voiceLabel = labels.get(i);
            String label = candidate.voiceLabel;
            if (label.isEmpty()) continue;
            String key = CandidateSelectionParser.normalize(label);
            int matches = 0;
            for (int j = 0; j < candidates.size(); j++) {
                String otherLabel = labels.get(j);
                if (key.equals(CandidateSelectionParser.normalize(otherLabel))) matches++;
            }
            if (matches == 1 && (!hasFallback || voiceChoices.size() < 3)) voiceChoices.add(candidate);
        }
        return voiceChoices;
    }

    private String candidateOrdinalText(int ordinal) {
        String[] ordinals = {"첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째"};
        if (ordinal >= 1 && ordinal <= ordinals.length) return ordinals[ordinal - 1];
        return ordinal + "번째";
    }

    private String candidateDisplayName(Candidate candidate) {
        return candidate.nameIsFallback
                ? "장소 이름 미확인 · 검색어: " + candidate.name
                : candidate.name;
    }

    private String candidateVoiceAddress(Candidate candidate) {
        String roadAndNumber = DestinationAddressMatcher.voiceRoadAndNumber(candidate.address);
        if (roadAndNumber.isEmpty()) return "";
        String area = shortArea(candidate.address);
        return area.isEmpty() ? roadAndNumber : area + ", " + roadAndNumber;
    }

    private String voiceCandidateLabel(Candidate candidate) {
        if (candidate.voiceLabel != null && !candidate.voiceLabel.trim().isEmpty()) {
            return candidate.voiceLabel;
        }
        if (candidate.nameIsFallback) return candidateVoiceAddress(candidate);
        String area = shortArea(candidate.address);
        return area.isEmpty() ? candidate.name : candidate.name + ", " + area;
    }

    private void askForCandidateByVoice() {
        askForCandidateByVoice("");
    }

    private void askForCandidateByVoice(String openingMessage) {
        if (!ensureCandidateListPresentationWindow()
                || pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) return;
        long presentationGeneration = destinationCandidateContext.generation();
        boolean screenOnly = shouldUseScreenOnlyInteraction();
        confirmButton.setText("후보 선택하기");
        confirmButton.setOnClickListener(v -> showCandidateChoiceDialog());
        confirmButton.setEnabled(true);
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
        destinationText.setText("");
        for (int i = 0; i < pendingCandidateChoices.size(); i++) {
            Candidate item = pendingCandidateChoices.get(i);
            destinationText.append((i + 1) + ". " + candidateDisplayName(item) + "\n" + item.address
                    + (i + 1 < pendingCandidateChoices.size() ? "\n\n" : ""));
        }
        if (screenOnly || voiceCandidateChoices == null || voiceCandidateChoices.isEmpty()) {
            String notice = openingMessage
                    + (voiceCandidateChoices == null || voiceCandidateChoices.isEmpty()
                    ? "주소로 구별하기 어려워요. 전체 주소를 확인하고 ‘후보 선택하기’를 눌러 주세요."
                    : "화면에서 전체 주소를 확인하고 ‘후보 선택하기’를 눌러 주세요.");
            prepareCandidateChoiceScreen(notice, false);
            showCandidateChoiceDialog(true);
            return;
        }

        boolean hasFallback = false;
        for (Candidate candidate : pendingCandidateChoices) hasFallback |= candidate.nameIsFallback;
        StringBuilder list = new StringBuilder(openingMessage);
        if (!openingMessage.isEmpty()) list.append(" ");
        list.append("여러 장소를 찾았어요. ");
        if (hasFallback) list.append("장소 이름을 확인하지 못한 곳은 주소로 읽어드릴게요. ");
        for (int i = 0; i < voiceCandidateChoices.size(); i++) {
            Candidate item = voiceCandidateChoices.get(i);
            if (i > 0) list.append(" ");
            list.append(candidateOrdinalText(item.voiceOrdinal)).append(", ")
                    .append(voiceCandidateLabel(item)).append(".");
        }
        if (voiceCandidateChoices.size() < pendingCandidateChoices.size()) {
            list.append(" 나머지 결과는 화면 목록에서 전체 주소를 확인해 주세요.");
        }
        list.append(" 원하는 곳의 번호나 이름을 말씀해 주세요.");
        if (!hasCandidateListPresentationWindow(presentationGeneration)) return;
        recognizedText.setText("장소 후보를 찾았어요. 음성으로 선택해 주세요.");
        showStatus("여러 장소 중 맞는 곳을 말씀해 주세요.", false);
        if (!hasCandidateListPresentationWindow(presentationGeneration)) return;
        speakThenListen(list.toString(), SpeechPurpose.CANDIDATE_SELECTION);
    }

    private boolean isCurrentCandidateSelectionCapture(long expectedSearchId,
                                                       long expectedContextGeneration) {
        return SearchGenerationGuard.isCurrent(expectedSearchId, activeSearchId)
                && destinationCandidateContext.isCurrent(expectedContextGeneration,
                SystemClock.elapsedRealtime());
    }

    private void handleCandidateSelection(String utterance, long expectedSearchId,
                                          long expectedContextGeneration) {
        if (!isCurrentCandidateSelectionCapture(expectedSearchId, expectedContextGeneration)
                || !ensureCandidatePresentationWindow()) return;
        if (awaitingRepeatedDistrictClarification) {
            handleRepeatedDistrictAnswer(utterance, activeSearchId);
            return;
        }
        if (pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) {
            showStatus("장소 후보가 없어졌어요. 목적지를 다시 말씀해 주세요.", true);
            return;
        }
        if (awaitingDistrictClarification) {
            String district = districtClarification.matchDistrict(utterance);
            if (district.isEmpty()) stopUnresolvedDistrictClarification();
            else narrowCandidatesToDistrict(district);
            return;
        }
        boolean restartRequested = isNewDestinationSelectionCommand(utterance);
        boolean alternativesRequested = isOtherCandidateListRequest(utterance);
        int commandOrdinal = CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase(utterance), pendingCandidateChoices.size());
        if ((restartRequested || alternativesRequested)
                && commandOrdinal != CandidateSelectionParser.NO_ORDINAL) {
            clarifyCandidateCommandOrdinal(restartRequested);
            return;
        }
        if (restartRequested) {
            resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요.");
            speakThenListen("새 목적지를 말씀해 주세요.", SpeechPurpose.DESTINATION);
            return;
        }
        if (alternativesRequested) {
            prepareCandidateChoiceScreen("다른 후보를 보여드릴게요. 목록에서 선택해 주세요.", false);
            showCandidateChoiceDialog(true);
            return;
        }
        if (shouldUseScreenOnlyInteraction()) {
            prepareCandidateChoiceScreen(
                    "화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.", false);
            showCandidateChoiceDialog(true);
            return;
        }
        if (voiceCandidateChoices == null || voiceCandidateChoices.isEmpty()) {
            prepareCandidateChoiceScreen(
                    "주소로 구별하기 어려워 음성으로 고를 수 없어요. 화면에서 전체 주소를 확인해 주세요.",
                    false);
            showCandidateChoiceDialog(true);
            return;
        }
        List<Candidate> selectable = voiceCandidateChoices;
        int selected = findCandidateIndex(utterance, selectable);
        if (selected >= 0) {
            showCandidate(selectable.get(selected));
            return;
        }
        if (districtClarification.isActive()) {
            String district = districtClarification.matchDistrict(utterance);
            if (!district.isEmpty()) {
                narrowCandidatesToDistrict(district);
                return;
            }
            if (districtClarification.onUnmatchedAnswer()
                    == DestinationDistrictClarification.UnmatchedAction.ASK_ONCE) {
                askForDistrictClarification();
            } else {
                stopUnresolvedDistrictClarification();
            }
            return;
        }
        candidateSelectionAttempts++;
        if (candidateSelectionAttempts < 3) {
            // Repeating only the instruction makes the user remember a list they may
            // not have heard. Re-read the same verified candidates before falling back
            // to the full-address screen; candidate selection still requires one match.
            askForCandidateByVoice("잘 못 들었어요. 후보를 다시 말씀드릴게요. ");
        } else {
            prepareCandidateChoiceScreen(
                    "음성으로 고르기 어려워요. 화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.",
                    false);
            showCandidateChoiceDialog(true);
        }
    }

    private void askForDistrictClarification() {
        awaitingDistrictClarification = true;
        String prompt = DestinationDistrictClarification.question(
                requestedAreaConstraintForCurrentFlow, districtClarification.districts());
        showStatus(prompt, false);
        speakThenListen(prompt, SpeechPurpose.CANDIDATE_SELECTION);
    }

    private void askForPreviouslyObservedDistrict(long requestId) {
        askForPreviouslyObservedDistrict(requestId, false);
    }

    private void askForPreviouslyObservedDistrict(long requestId, boolean recognitionRetry) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        awaitingRepeatedDistrictClarification = true;
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        String prompt = (recognitionRetry
                ? "구 이름을 잘 듣지 못했어요. "
                : "같은 검색은 반복하지 않을게요. ")
                + repeatedAmbiguousDestination.districtQuestion();
        showStatus(prompt, false);
        recognizedText.setText("앞서 찾은 지역 후보: "
                + String.join(", ", repeatedAmbiguousDestination.districts()));
        if (shouldUseScreenOnlyInteraction()) {
            ArrayList<String> districts = new ArrayList<>(repeatedAmbiguousDestination.districts());
            new AlertDialog.Builder(this)
                    .setTitle("어느 구로 찾을까요?")
                    .setItems(districts.toArray(new String[0]), (dialog, which) -> {
                        if (which >= 0 && which < districts.size()) {
                            handleRepeatedDistrictAnswer(districts.get(which), requestId);
                        }
                    })
                    .setNegativeButton("다시 말하기", (dialog, which) ->
                            stopRepeatedAmbiguousDestination(requestId, false))
                    .show();
            return;
        }
        speakThenListen(prompt, SpeechPurpose.CANDIDATE_SELECTION);
    }

    private void handleRepeatedDistrictAnswer(String utterance, long requestId) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        String district = repeatedAmbiguousDestination.districtFromAnswer(utterance);
        if (district.isEmpty()) {
            if (repeatedAmbiguousDestination.isMeaningfulUnresolvedDistrictAnswer(utterance)) {
                stopRepeatedAmbiguousDestination(requestId, true);
            } else if (repeatedAmbiguousDestination.noteUnrecognizedDistrictAnswer()) {
                askForPreviouslyObservedDistrict(requestId, true);
            } else {
                stopRepeatedAmbiguousDestination(requestId, false);
            }
            return;
        }
        if (repeatedAmbiguousDestination.isObservedDistrict(district)) {
            narrowRememberedCandidatesToDistrict(district, requestId);
        } else {
            searchWithExplicitDistrict(district, requestId);
        }
    }

    private void narrowRememberedCandidatesToDistrict(String district, long requestId) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        ArrayList<Candidate> narrowed = new ArrayList<>();
        for (Candidate candidate : rememberedAmbiguousDestinationCandidates) {
            if (DestinationDistrictClarification.addressMatchesDistrict(candidate.address, district)) {
                narrowed.add(candidate);
            }
        }
        if (narrowed.isEmpty()) {
            stopRepeatedAmbiguousDestination(requestId, false);
            return;
        }
        String areaQuery = repeatedAmbiguousDestination.areaQueryWithDistrict(district);
        repeatedAmbiguousDestination.clear();
        rememberedAmbiguousDestinationCandidates.clear();
        awaitingRepeatedDistrictClarification = false;
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        requestedAreaConstraintForCurrentFlow = areaQuery;
        pendingCandidateChoices = narrowed;
        voiceCandidateChoices = buildVoiceCandidateChoices(narrowed);
        candidateSelectionAttempts = 0;
        recognizedText.setText("선택한 구: " + district + "\n앞서 확인한 후보만 보여드려요.");
        showStatus(district + "에서 확인된 후보예요.", false);
        if (narrowed.size() == 1) showCandidate(narrowed.get(0), requestId);
        else askForCandidateByVoice(district + "에서 확인된 후보예요. ");
    }

    private void searchWithExplicitDistrict(String district, long requestId) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        String query = repeatedAmbiguousDestination.queryWithDistrict(district);
        String areaQuery = repeatedAmbiguousDestination.areaQueryWithDistrict(district);
        repeatedAmbiguousDestination.clear();
        rememberedAmbiguousDestinationCandidates.clear();
        awaitingRepeatedDistrictClarification = false;
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        requestedAreaConstraintForCurrentFlow = areaQuery;
        recognizedText.setText("선택한 구: " + district + "\n지역을 포함해 장소를 다시 확인하고 있어요.");
        showStatus(district + "에서 장소를 다시 찾을게요.", false);
        searchPlaces(query, requestId);
    }

    private void stopRepeatedAmbiguousDestination(long requestId, boolean unresolvedAnswer) {
        if (requestId != activeSearchId || isFinishing() || isDestroyed()) return;
        if (unresolvedAnswer) {
            repeatedAmbiguousDestination.noteMeaningfulUnresolvedDistrictAnswer();
        }
        awaitingRepeatedDistrictClarification = false;
        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
        boolean fixedNotice = repeatedAmbiguousDestination.isClarificationExhausted();
        String message = fixedNotice
                ? "구를 확인하지 못해 이 검색은 멈춰 있어요. 검색을 반복하지 않습니다. 다른 동네나 구 이름을 포함해 다시 말씀해 주세요."
                : "구를 정하지 못해 검색을 멈췄어요. "
                + String.join(" 또는 ", repeatedAmbiguousDestination.districts())
                + " 중 하나를 말씀하시거나, 구 이름을 포함해 다시 말씀해 주세요.";
        recognizedText.setText("구를 확인하지 못해 같은 검색을 반복하지 않았어요.");
        showStatus(message, !fixedNotice || repeatedAmbiguousDestination.shouldAnnounceFixedNotice());
        confirmButton.setText("구 이름을 포함해 다시 말하기");
        confirmButton.setOnClickListener(v -> {
            confirmButton.setVisibility(View.GONE);
            beginSpeech(SpeechPurpose.DESTINATION, true);
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void narrowCandidatesToDistrict(String district) {
        ArrayList<Candidate> narrowed = new ArrayList<>();
        if (pendingCandidateChoices != null) {
            for (Candidate candidate : pendingCandidateChoices) {
                if (DestinationDistrictClarification.addressMatchesDistrict(
                        candidate.address, district)) narrowed.add(candidate);
            }
        }
        if (narrowed.isEmpty()) {
            stopUnresolvedDistrictClarification();
            return;
        }
        districtClarification.clear();
        repeatedAmbiguousDestination.clear();
        rememberedAmbiguousDestinationCandidates.clear();
        awaitingDistrictClarification = false;
        pendingCandidateChoices = narrowed;
        voiceCandidateChoices = buildVoiceCandidateChoices(narrowed);
        candidateSelectionAttempts = 0;
        if (narrowed.size() == 1) showCandidate(narrowed.get(0));
        else askForCandidateByVoice(district + "에 있는 후보예요. ");
    }

    private void stopUnresolvedDistrictClarification() {
        districtClarification.clear();
        awaitingDistrictClarification = false;
        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        confirmationCandidateChoices = null;
        destinationText.setText("");
        recognizedText.setText("구를 확인할 수 없어 목적지 선택을 멈췄어요.");
        String message = "구를 확인하지 못해 안내를 멈췄어요. 다른 동네를 말씀해 주세요.";
        showStatus(message, true);
        confirmButton.setText("다른 동네 다시 말하기");
        confirmButton.setOnClickListener(v -> {
            confirmButton.setVisibility(View.GONE);
            beginSpeech(SpeechPurpose.DESTINATION, true);
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private Candidate findUnambiguousAlternativeCandidate(List<String> hypotheses) {
        if (hypotheses == null || hypotheses.size() < 2
                || voiceCandidateChoices == null || voiceCandidateChoices.isEmpty()
                || pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) return null;
        String primary = hypotheses.get(0);
        if (isNewDestinationSelectionCommand(primary) || isOtherCandidateListRequest(primary)) return null;
        int primaryOrdinal = CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase(primary), pendingCandidateChoices.size());
        if (primaryOrdinal != CandidateSelectionParser.NO_ORDINAL) return null;
        if (findCandidateIndex(primary, voiceCandidateChoices) >= 0) return null;

        ArrayList<Integer> alternateIndexes = new ArrayList<>();
        for (int i = 1; i < hypotheses.size(); i++) {
            String hypothesis = hypotheses.get(i);
            if (isNewDestinationSelectionCommand(hypothesis)
                    || isOtherCandidateListRequest(hypothesis)) return null;
            alternateIndexes.add(findCandidateIndex(hypothesis, voiceCandidateChoices));
        }
        int selected = CandidateHypothesisResolver.resolve(alternateIndexes);
        return selected >= 0 && selected < voiceCandidateChoices.size()
                ? voiceCandidateChoices.get(selected) : null;
    }

    private boolean isNewDestinationSelectionCommand(String utterance) {
        String value = CandidateSelectionParser.normalize(utterance);
        if (value.isEmpty()) return false;
        return value.equals("처음") || value.startsWith("처음으로") || value.startsWith("처음부터")
                || value.startsWith("새목적지") || value.startsWith("새로검색")
                || value.startsWith("다시검색") || value.startsWith("새로찾아");
    }

    private boolean isOtherCandidateListRequest(String utterance) {
        String value = CandidateSelectionParser.normalize(utterance);
        if (value.isEmpty()) return false;
        // In the candidate-list state, any explicit "different/another" wording
        // stays in the safe list path instead of being name-matched as a candidate.
        return value.contains("다른");
    }

    private void clarifyCandidateCommandOrdinal(boolean restartRequested) {
        String message = restartRequested
                ? "처음으로 돌아가 새 목적지를 찾을지, 현재 후보 번호를 고를지 헷갈려요. ‘처음으로’ 또는 번호만 다시 말씀해 주세요."
                : "다른 후보 목록을 볼지, 현재 후보 번호를 고를지 헷갈려요. ‘다른 후보’ 또는 번호만 다시 말씀해 주세요.";
        candidateSelectionAttempts++;
        if (shouldUseScreenOnlyInteraction() || candidateSelectionAttempts >= 2) {
            prepareCandidateChoiceScreen(message, false);
            showCandidateChoiceDialog(true);
            return;
        }
        showStatus(message, false);
        speakThenListen(message, SpeechPurpose.CANDIDATE_SELECTION);
    }

    private int findCandidateIndex(String utterance, List<Candidate> choices) {
        String value = CandidateSelectionParser.relevantPhrase(utterance);
        if (value.isEmpty()) return -1;
        int ordinal = CandidateSelectionParser.findExplicitOrdinal(value, pendingCandidateChoices.size());
        if (ordinal == CandidateSelectionParser.AMBIGUOUS) return -1;
        if (ordinal >= 0) {
            Candidate numbered = pendingCandidateChoices.get(ordinal);
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i) == numbered) return i;
            }
            return -1;
        }
        value = CandidateSelectionIntentGuard.discriminativePhrase(
                utterance, requestedAreaConstraintForCurrentFlow);
        if (value.isEmpty()) return -1;
        if (value.startsWith("맨위") || value.startsWith("제일위")
                || value.startsWith("가장위")) return 0;
        if (value.startsWith("맨아래") || value.startsWith("맨밑")
                || value.startsWith("마지막") || value.startsWith("맨마지막")
                || value.startsWith("제일마지막") || value.startsWith("제일아래")
                || value.startsWith("가장아래") || value.startsWith("제일끝")
                || value.startsWith("가장끝")) return choices.size() - 1;
        int match = -1;
        for (int i = 0; i < choices.size(); i++) {
            Candidate item = choices.get(i);
            String name = item.nameIsFallback ? "" : item.name;
            if (CandidateSelectionMatcher.matches(value, name,
                    voiceCandidateLabel(item), item.address)) {
                if (match >= 0) return -1;
                match = i;
            }
        }
        return match;
    }

    private void showCandidateChoiceDialog() {
        showCandidateChoiceDialog(false);
    }

    private void showCandidateChoiceDialog(boolean requireFreshListWindow) {
        // Normal taps select from the list already rendered on screen; reopening a
        // changed/rebuilt list is a fresh presentation and must reserve its full budget.
        boolean hasWindow = requireFreshListWindow
                ? ensureCandidateListPresentationWindow() : ensureCandidatePresentationWindow();
        if (!hasWindow
                || pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) return;
        dismissCandidateChoiceDialog();
        if (queuedMicPurpose == SpeechPurpose.CANDIDATE_SELECTION) {
            cancelPendingAutoListen();
        }
        if (activeSpeechPurpose == SpeechPurpose.CANDIDATE_SELECTION) {
            invalidateSpeechCapture();
            speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
            showStatus("화면에서 후보를 선택해 주세요.", false);
        }
        long searchIdAtDisplay = activeSearchId;
        long candidateGenerationAtDisplay = candidateGeneration;
        long candidateContextGenerationAtDisplay = destinationCandidateContext.generation();
        ArrayList<Candidate> choicesSnapshot = new ArrayList<>(pendingCandidateChoices);
        String[] choices = new String[choicesSnapshot.size()];
        for (int i = 0; i < choicesSnapshot.size(); i++) {
            Candidate item = choicesSnapshot.get(i);
            boolean voiceSelectable = voiceCandidateChoices != null
                    && voiceCandidateChoices.contains(item);
            choices[i] = (i + 1) + ". " + candidateDisplayName(item) + "\n" + item.address
                    + (voiceSelectable ? "" : "\n음성으로 구별하기 어려워 화면에서 선택");
        }
        if (!hasCandidateChoiceDialogWindow(candidateContextGenerationAtDisplay,
                requireFreshListWindow)) return;
        AlertDialog choiceDialog = new AlertDialog.Builder(this)
                .setTitle("맞는 장소를 선택해 주세요")
                .setItems(choices, (dialog, which) -> {
                    if (!destinationCandidateContext.isCurrent(candidateContextGenerationAtDisplay,
                            SystemClock.elapsedRealtime())) {
                        // A queued click from a dismissed old-generation dialog must not
                        // reopen or overwrite the choices belonging to a newer search.
                        if (destinationCandidateContext.generation()
                                != candidateContextGenerationAtDisplay) return;
                        if (!ensureDestinationCandidateContextCurrent()) return;
                        showStatus("후보 목록이 바뀌었어요. 현재 목록에서 골라 주세요.", true);
                        if (pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
                            showCandidateChoiceDialog(true);
                        }
                        return;
                    }
                    if (searchIdAtDisplay != activeSearchId
                            || candidateGenerationAtDisplay != candidateGeneration) return;
                    if (!isSameCandidateChoiceList(choicesSnapshot)
                            || which < 0 || which >= choicesSnapshot.size()) {
                        showStatus("후보 목록이 바뀌었어요. 현재 검색 결과를 확인해 주세요.", true);
                        // Reopening after the underlying list changed is a new list
                        // presentation, not merely selecting from the list already shown.
                        if (pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()
                                && ensureCandidateListPresentationWindow()) {
                            showCandidateChoiceDialog(true);
                        }
                        return;
                    }
                    showCandidate(choicesSnapshot.get(which));
                })
                .setNegativeButton("처음으로", (dialog, which) ->
                        resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."))
                .create();
        if (!hasCandidateChoiceDialogWindow(candidateContextGenerationAtDisplay,
                requireFreshListWindow)) return;
        candidateChoiceDialog = choiceDialog;
        choiceDialog.setOnDismissListener(ignored -> {
            if (candidateChoiceDialog == choiceDialog) candidateChoiceDialog = null;
        });
        choiceDialog.show();
    }

    private void handleDestinationConfirmation(String utterance, Candidate candidateAtCapture,
                                               long candidateGenerationAtCapture,
                                               long approvingTurnSequence,
                                               TravelMode modeAtCapture) {
        if (modeAtCapture != travelMode
                || !isCurrentCandidate(candidateAtCapture, candidateGenerationAtCapture)) {
            if (candidateAtCapture == pendingCandidate
                    && candidateGenerationAtCapture == candidateGeneration) {
                if (!ensureDestinationCandidateContextCurrent()) return;
                clearPendingCandidate();
                destinationCorrectionContext.clear();
                confirmButton.setVisibility(View.GONE);
                destinationText.setText("");
                showStatus("목적지 확인 시간이 지났어요. 다시 말씀해 주세요.", true);
            }
            return;
        }
        boolean textApproval = approvingTurnSequence == UI_APPROVAL_TURN_SEQUENCE;
        if (textApproval ? !confirmationPromptGate.isReadyForTextApproval()
                : !confirmationPromptGate.isReadyForVoiceApproval(SystemClock.elapsedRealtime())) {
            showStatus(textApproval
                    ? "먼저 확인할 목적지가 화면에 표시된 뒤 답해 주세요."
                    : "목적지 확인 안내를 끝까지 들은 뒤 말씀해 주세요.", false);
            return;
        }
        DestinationConfirmationParser.Decision decision = DestinationConfirmationParser
                .classifyActiveYesNoAnswer(utterance);
        if (decision == DestinationConfirmationParser.Decision.YES) {
            confirmationListenAttempts = 0;
            openConfirmedRoute(candidateAtCapture, candidateGenerationAtCapture, approvingTurnSequence);
        } else if (decision == DestinationConfirmationParser.Decision.NO) {
            if (switchCandidateFromConfirmation(utterance)) return;
            if (restoreCandidateChoicesAfterRejection()) return;
            clearPendingCandidate();
            clearDestinationCandidateContext();
            destinationCorrectionContext.clear();
            pendingCandidateChoices = null;
            voiceCandidateChoices = null;
            destinationClarificationLease.clear();
            awaitingSpecialistVerification = false;
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
            confirmButton.setVisibility(View.GONE);
            destinationText.setText("");
            recognizedText.setText("들은 말: " + utterance);
            showStatus("알겠어요. 다른 목적지를 말씀해 주세요.", false);
            speakThenListen("다른 목적지를 말씀해 주세요.", SpeechPurpose.DESTINATION);
        } else {
            if (decision == DestinationConfirmationParser.Decision.UNCLEAR
                    && switchCandidateFromConfirmation(utterance)) return;
            confirmationListenAttempts++;
            if (confirmationListenAttempts < 3) {
                if (!hasCandidatePresentationWindow(pendingCandidateContextGeneration)) return;
                showStatus("목적지가 맞으면 ‘맞아요’, 아니면 ‘아니요’라고 말씀해 주세요.", false);
                speakThenListen("목적지가 맞으면 맞아요, 아니면 아니요라고 말씀해 주세요.",
                        SpeechPurpose.CONFIRMATION);
            } else {
                showStatus("목적지를 확인하지 못했어요. 아래 버튼을 눌러 선택해 주세요.", true);
                confirmationListenAttempts = 0;
            }
        }
    }

    private boolean restoreCandidateChoicesAfterRejection() {
        if (!ensureDestinationCandidateContextCurrent()) return false;
        if (confirmationCandidateChoices == null || confirmationCandidateChoices.size() < 2) return false;
        ArrayList<Candidate> remainingChoices = new ArrayList<>();
        for (Candidate choice : confirmationCandidateChoices) {
            if (choice != pendingCandidate) remainingChoices.add(choice);
        }
        if (remainingChoices.isEmpty()) return false;
        clearPendingCandidate();
        pendingCandidateChoices = remainingChoices;
        voiceCandidateChoices = buildVoiceCandidateChoices(remainingChoices);
        candidateSelectionAttempts = 0;
        confirmationListenAttempts = 0;
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        if (remainingChoices.size() == 1) {
            showCandidate(remainingChoices.get(0));
        } else {
            askForCandidateByVoice("이 후보가 아니면 다른 후보를 선택해 주세요.");
        }
        return true;
    }

    private boolean switchCandidateFromConfirmation(String utterance) {
        if (!ensureDestinationCandidateContextCurrent()) return false;
        if (confirmationCandidateChoices == null || confirmationCandidateChoices.size() < 2) return false;
        String value = CandidateSelectionParser.relevantPhrase(utterance);
        int ordinal = CandidateSelectionParser.findExplicitOrdinal(
                value, confirmationCandidateChoices.size());
        if (ordinal < 0) return false;

        pendingCandidateChoices = new ArrayList<>(confirmationCandidateChoices);
        voiceCandidateChoices = buildVoiceCandidateChoices(pendingCandidateChoices);
        candidateSelectionAttempts = 0;
        Candidate target = pendingCandidateChoices.get(ordinal);
        if (voiceCandidateChoices == null || !voiceCandidateChoices.contains(target)) {
            prepareCandidateChoiceScreen(
                    "그 번호는 음성으로 구별하기 어려워요. 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.",
                    true);
            showCandidateChoiceDialog();
            return true;
        }
        showCandidate(target);
        return true;
    }

    private void prepareCandidateChoiceScreen(String message, boolean invalidateCurrentCandidate) {
        if (!ensureCandidateListPresentationWindow()) return;
        long presentationGeneration = destinationCandidateContext.generation();
        if (invalidateCurrentCandidate) {
            candidateGeneration++;
            pendingCandidate = null;
            pendingCandidateExpiresAtElapsedRealtime = 0L;
            pendingCandidateContextGeneration = -1L;
        }
        destinationText.setText("");
        for (int i = 0; i < pendingCandidateChoices.size(); i++) {
            Candidate item = pendingCandidateChoices.get(i);
            boolean voiceSelectable = voiceCandidateChoices != null
                    && voiceCandidateChoices.contains(item);
            destinationText.append((i + 1) + ". " + candidateDisplayName(item) + "\n"
                    + item.address + (voiceSelectable ? ""
                    : "\n음성으로 구별하기 어려워 화면에서 선택")
                    + (i + 1 < pendingCandidateChoices.size() ? "\n\n" : ""));
        }
        confirmButton.setText("후보 선택하기");
        confirmButton.setOnClickListener(v -> showCandidateChoiceDialog());
        confirmButton.setEnabled(true);
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
        if (!hasCandidateListPresentationWindow(presentationGeneration)) return;
        recognizedText.setText("장소 후보를 찾았어요. 화면 목록에서 선택해 주세요.");
        showStatus(message, false);
        if (!hasCandidateListPresentationWindow(presentationGeneration)) return;
        speak(message);
    }

    private void speakThenListen(String message, SpeechPurpose purpose) {
        speakThenListen(message, purpose, null, null);
    }

    private void speakThenListen(String message, SpeechPurpose purpose,
                                 String conditionTopicKey) {
        speakThenListen(message, purpose, conditionTopicKey, null);
    }

    private void speakThenListen(String message, SpeechPurpose purpose,
                                 String conditionTopicKey, java.util.Set<String> topicSelectionKeys) {
        speakThenListen(message, purpose, conditionTopicKey, topicSelectionKeys, null);
    }

    private void speakThenListen(String message, SpeechPurpose purpose,
                                 String conditionTopicKey, java.util.Set<String> topicSelectionKeys,
                                 String topicSelectionScope) {
        cancelPendingAutoListen();
        if (purpose == SpeechPurpose.CONFIRMATION) {
            confirmationPromptText = message;
            confirmationPromptGate.openForDisplay();
        }
        long now = SystemClock.elapsedRealtime();
        if (conditionTopicKey != null && !conditionTopicKey.trim().isEmpty()) {
            pendingActionGate.replaceConditionAnswer(conditionTopicKey, now);
        } else if (topicSelectionKeys != null && !topicSelectionKeys.isEmpty()) {
            pendingActionGate.replaceTopicSelection(topicSelectionKeys,
                    topicSelectionScope, now);
        } else {
            clearPendingConditionFlow();
        }
        if (conditionTopicKey != null && !conditionTopicKey.trim().isEmpty()
                || topicSelectionKeys != null && !topicSelectionKeys.isEmpty()) {
            clearPendingSpecialistConsent();
            clearPendingCandidate();
            pendingCandidateChoices = null;
            voiceCandidateChoices = null;
            confirmButton.setVisibility(View.GONE);
        }
        if (shouldUseScreenOnlyInteraction()) {
            speakAudio(message);
            return;
        }
        queuedMicPurpose = purpose;
        queuedMicPreserveClarificationContext = true;
        speakAudio(message, purpose == SpeechPurpose.CONFIRMATION);
    }

    private void cancelPendingAutoListen() {
        queuedMicPurpose = null;
        queuedMicPreserveClarificationContext = false;
        cancelCandidateListSpeechTimeout();
        confirmationPromptGate.cancelActive();
        if (tts != null) tts.stop();
    }

    private void openConfirmedRoute() {
        Candidate candidate = pendingCandidate;
        openConfirmedRoute(candidate, candidateGeneration, UI_APPROVAL_TURN_SEQUENCE);
    }

    private void openConfirmedRoute(Candidate candidate, long expectedCandidateGeneration,
                                    long approvingTurnSequence) {
        if (!isCurrentCandidate(candidate, expectedCandidateGeneration)) {
            if (candidate != null && candidate == pendingCandidate
                    && expectedCandidateGeneration == candidateGeneration) {
                if (!ensureDestinationCandidateContextCurrent()) return;
                clearPendingCandidate();
                destinationCorrectionContext.clear();
                confirmButton.setVisibility(View.GONE);
                destinationText.setText("");
                showStatus("목적지 확인 시간이 지났어요. 다시 찾아 드릴게요.", true);
            }
            return;
        }
        if (approvingTurnSequence != UI_APPROVAL_TURN_SEQUENCE
                && approvingTurnSequence != speechCaptureSequence) return;
        long expectedResultGeneration = pendingCandidateContextGeneration;
        if (!hasCandidateHandoffWindow(expectedResultGeneration)) return;
        invalidateSpeechCapture();
        cancelPendingAutoListen();
        if (travelMode == TravelMode.TAXI) {
            if (isKakaoTaxiServiceEnabled()) launchTaxiHandoff(candidate, true,
                    expectedResultGeneration, expectedCandidateGeneration);
            else showKakaoTaxiAccessibilityPrompt(candidate);
            return;
        }
        if (!isNaverGuideServiceEnabled()) {
            showNaverAccessibilityPrompt(candidate);
            return;
        }
        launchNaverTransitRoute(candidate, expectedResultGeneration, expectedCandidateGeneration);
    }

    private void setPendingCandidate(Candidate candidate) {
        confirmationPromptGate.reset();
        confirmationPromptText = "";
        pendingCandidate = candidate;
        long expectedGeneration = ++candidateGeneration;
        pendingCandidateContextGeneration = destinationCandidateContext.generation();
        pendingCandidateExpiresAtElapsedRealtime = destinationCandidateContext.isCurrent(
                SystemClock.elapsedRealtime())
                ? destinationCandidateContext.expiresAtElapsedRealtime() : 0L;
        confirmButton.setOnClickListener(v -> openConfirmedRoute(candidate,
                expectedGeneration, UI_APPROVAL_TURN_SEQUENCE));
    }

    private void clearPendingCandidate() {
        candidateGeneration++;
        pendingCandidate = null;
        pendingCandidateExpiresAtElapsedRealtime = 0L;
        pendingCandidateContextGeneration = -1L;
        confirmationCandidateChoices = null;
        confirmationPromptGate.reset();
        confirmationPromptText = "";
    }

    private boolean isCurrentCandidate(Candidate candidate, long expectedGeneration) {
        return candidate != null && pendingCandidate == candidate
                && candidateGeneration == expectedGeneration
                && pendingCandidateExpiresAtElapsedRealtime > SystemClock.elapsedRealtime()
                && destinationCandidateContext.isCurrent(pendingCandidateContextGeneration,
                SystemClock.elapsedRealtime());
    }

    private boolean hasCandidateHandoffWindow(long expectedResultGeneration) {
        if (expectedResultGeneration < 0L
                || destinationCandidateContext.generation() != expectedResultGeneration) return false;
        long now = SystemClock.elapsedRealtime();
        if (destinationCandidateContext.hasMinimumRemaining(expectedResultGeneration, now,
                MIN_CANDIDATE_HANDOFF_WINDOW_MS)) return true;
        discardDestinationCandidateContext("확인 시간이 지나 목적지를 다시 찾아야 해요.");
        return false;
    }

    private boolean ensureDestinationCandidateContextCurrent() {
        long now = SystemClock.elapsedRealtime();
        if (destinationCandidateContext.isCurrent(now)) return true;
        if (destinationCandidateContext.hasLease() || pendingCandidate != null
                || pendingCandidateChoices != null || confirmationCandidateChoices != null) {
            expireDestinationCandidateContextIfNeeded();
        }
        return false;
    }

    private boolean ensureCandidatePresentationWindow() {
        if (!ensureDestinationCandidateContextCurrent()) return false;
        return hasCandidatePresentationWindow(destinationCandidateContext.generation());
    }

    private boolean ensureCandidateListPresentationWindow() {
        if (!ensureDestinationCandidateContextCurrent()) return false;
        return hasCandidateListPresentationWindow(destinationCandidateContext.generation());
    }

    private boolean hasCandidateChoiceDialogWindow(long expectedGeneration,
                                                   boolean requireFreshListWindow) {
        return requireFreshListWindow
                ? hasCandidateListPresentationWindow(expectedGeneration)
                : hasCandidatePresentationWindow(expectedGeneration);
    }

    private boolean hasCandidatePresentationWindow(long expectedGeneration) {
        if (expectedGeneration < 0L
                || destinationCandidateContext.generation() != expectedGeneration) return false;
        if (destinationCandidateContext.hasMinimumRemaining(expectedGeneration,
                SystemClock.elapsedRealtime(), MIN_CANDIDATE_CONFIRMATION_WINDOW_MS)) return true;
        discardDestinationCandidateContext("검색 결과가 오래되어요. 목적지를 다시 말씀해 주세요.");
        return false;
    }

    private boolean hasCandidateListPresentationWindow(long expectedGeneration) {
        if (expectedGeneration < 0L
                || destinationCandidateContext.generation() != expectedGeneration) return false;
        if (destinationCandidateContext.hasMinimumRemaining(expectedGeneration,
                SystemClock.elapsedRealtime(),
                DestinationCandidateContext.MIN_CANDIDATE_LIST_REMAINING_MS)) return true;
        discardDestinationCandidateContext("검색 결과가 오래되어요. 목적지를 다시 말씀해 주세요.");
        return false;
    }

    private void expireDestinationCandidateContextIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (destinationCandidateContext.isCurrent(now)) return;
        discardDestinationCandidateContext("검색 결과가 오래됐어요. 목적지를 다시 말씀해 주세요.");
    }

    private void scheduleDestinationCandidateExpiry(long expectedGeneration, long expiresAt) {
        if (destinationCandidateExpiryTask != null) {
            handler.removeCallbacks(destinationCandidateExpiryTask);
        }
        Runnable expiryTask = new Runnable() {
            @Override public void run() {
                if (destinationCandidateExpiryTask != this) return;
                if (destinationCandidateContext.generation() != expectedGeneration) {
                    destinationCandidateExpiryTask = null;
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (destinationCandidateContext.isCurrent(expectedGeneration, now)) {
                    handler.postDelayed(this, Math.max(1L, expiresAt - now));
                    return;
                }
                destinationCandidateExpiryTask = null;
                expireDestinationCandidateContextIfNeeded();
            }
        };
        destinationCandidateExpiryTask = expiryTask;
        handler.postDelayed(expiryTask,
                Math.max(1L, expiresAt - SystemClock.elapsedRealtime()));
    }

    private void clearDestinationCandidateContext() {
        cancelPendingAutoListen();
        dismissCandidateChoiceDialog();
        if (destinationCandidateExpiryTask != null) {
            handler.removeCallbacks(destinationCandidateExpiryTask);
            destinationCandidateExpiryTask = null;
        }
        destinationCandidateContext.clear();
    }

    private void dismissCandidateChoiceDialog() {
        AlertDialog dialog = candidateChoiceDialog;
        candidateChoiceDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void discardDestinationCandidateContext(String message) {
        clearDestinationCandidateContext();
        waitingForGuideAccessibilitySettings = false;
        waitingForTaxiAccessibilitySettings = false;
        destinationCorrectionContext.clear();
        activeSearchId++;
        cancelPendingAutoListen();
        invalidateSpeechCapture();
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        candidateSelectionAttempts = 0;
        destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
        destinationText.setText("");
        recognizedText.setText("검색 결과가 오래되어 새로 확인해야 해요.");
        showStatus(message, true);
        confirmButton.setText("다시 찾기");
        confirmButton.setOnClickListener(v -> {
            confirmButton.setVisibility(View.GONE);
            beginSpeech(SpeechPurpose.DESTINATION, true);
        });
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private boolean isSameCandidateChoiceList(List<Candidate> expectedChoices) {
        if (expectedChoices == null || pendingCandidateChoices == null
                || expectedChoices.size() != pendingCandidateChoices.size()) return false;
        for (int i = 0; i < expectedChoices.size(); i++) {
            if (expectedChoices.get(i) != pendingCandidateChoices.get(i)) return false;
        }
        return true;
    }

    private void showNaverAccessibilityPrompt(Candidate candidate) {
        new AlertDialog.Builder(this)
                .setTitle("자동 안내 시작을 한 번 설정해 주세요")
                .setMessage("처음 한 번만 켜면 다음부터 목적지를 확인한 뒤 네이버 지도에서 대중교통 경로의 ‘안내 시작’을 자동으로 눌러요.\n\nAndroid 접근성 설정에서 ‘MA 네이버 안내 시작’을 켜고 MA로 돌아오면, 방금 확인한 목적지로 이어서 진행합니다. MA는 네이버 지도에서 목적지와 대중교통 경로가 확인된 경우에만 안내 시작을 한 번 누르며, 화면 내용을 저장하거나 전송하지 않아요.")
                .setPositiveButton("한 번만 설정하기", (dialog, which) -> {
                    pendingCandidate = candidate;
                    waitingForGuideAccessibilitySettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        waitingForGuideAccessibilitySettings = false;
                        showStatus("접근성 설정을 열지 못했어요. Android 설정에서 MA 네이버 안내 시작을 켜주세요.", true);
                    }
                })
                .setNegativeButton("이번엔 직접 시작", (dialog, which) -> launchNaverTransitRoute(candidate))
                .show();
    }

    private boolean isNaverGuideServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(this, NaverGuideAccessibilityService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo resolveInfo = info.getResolveInfo();
            if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                ComponentName enabled = new ComponentName(resolveInfo.serviceInfo.packageName,
                        resolveInfo.serviceInfo.name);
                if (expected.equals(enabled)) return true;
            }
        }
        return false;
    }

    private void showKakaoTaxiAccessibilityPrompt(Candidate candidate) {
        new AlertDialog.Builder(this)
                .setTitle("카카오 T 자동 조작 설정")
                .setMessage("MA가 ‘맞으면 카카오 T에서 택시를 부른다’고 안내하고 목적지 확인을 받습니다. 접근성을 켜면 확인한 주소를 입력하고 이름·주소가 일치하는 결과를 선택한 뒤, 카카오 T 화면에 그 목적지가 보이고 정확히 일치하는 호출 버튼이 하나일 때만 누릅니다. 출발지·차량·요금·결제는 선택하거나 변경하지 않아요.")
                .setPositiveButton("설정 열기", (dialog, which) -> {
                    pendingCandidate = candidate;
                    waitingForTaxiAccessibilitySettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        waitingForTaxiAccessibilitySettings = false;
                        launchTaxiHandoff(candidate, false);
                    }
                })
                .setNegativeButton("주소 복사해서 직접", (dialog, which) -> launchTaxiHandoff(candidate, false))
                .show();
    }

    private boolean isKakaoTaxiServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(this, KakaoTaxiDestinationService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo resolveInfo = info.getResolveInfo();
            if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                ComponentName enabled = new ComponentName(resolveInfo.serviceInfo.packageName,
                        resolveInfo.serviceInfo.name);
                if (expected.equals(enabled)) return true;
            }
        }
        return false;
    }

    private void launchNaverTransitRoute(Candidate candidate) {
        launchNaverTransitRoute(candidate, pendingCandidateContextGeneration, candidateGeneration);
    }

    private void launchNaverTransitRoute(Candidate candidate, long expectedResultGeneration,
                                         long expectedCandidateGeneration) {
        if (!isCurrentCandidate(candidate, expectedCandidateGeneration)
                || pendingCandidateContextGeneration != expectedResultGeneration) return;
        if (!hasCandidateHandoffWindow(expectedResultGeneration)) return;
        clearPendingCandidate();
        confirmButton.setEnabled(false);
        showStatus("NAVER 지도에 경로를 전달하고 있어요.", false);
        boolean guideAutomationEnabled = isNaverGuideServiceEnabled();
        try {
            String encodedName = URLEncoder.encode(candidate.nameIsFallback
                    ? candidate.address : candidate.name, StandardCharsets.UTF_8.name());
            String uri = "nmap://route/public?dlat=" + candidate.lat
                    + "&dlng=" + candidate.lng
                    + "&dname=" + encodedName
                    + "&appname=" + Uri.encode(getPackageName());
            Intent route = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            route.addCategory(Intent.CATEGORY_BROWSABLE);
            route.setPackage("com.nhn.android.nmap");
            if (route.resolveActivity(getPackageManager()) == null) {
                confirmButton.setEnabled(true);
                showStatus("NAVER 지도가 설치되어 있지 않아요. 설치 후 다시 시도해 주세요.", true);
                new AlertDialog.Builder(this)
                        .setTitle("NAVER 지도 설치")
                        .setMessage("NAVER 지도 앱을 설치해야 경로를 열 수 있어요.")
                        .setPositiveButton("설치 화면 열기", (dialog, which) -> openNaverStore())
                        .setNegativeButton("취소", null)
                        .show();
                setPendingCandidate(candidate);
                return;
            }
            if (!hasCandidateHandoffWindow(expectedResultGeneration)) return;
            if (guideAutomationEnabled) NaverGuideAutomation.arm(this,
                    candidate.nameIsFallback ? "" : candidate.name, candidate.address);
            else NaverGuideAutomation.disarm(this, "manual_handoff");
            if (!hasCandidateHandoffWindow(expectedResultGeneration)) {
                NaverGuideAutomation.disarm(this, "candidate_expired");
                return;
            }
            startActivity(route);
            clearDestinationCandidateContext();
            destinationCorrectionContext.clear();
            waitingForMapReturn = true;
            confirmButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            destinationText.setText(candidateDisplayName(candidate) + "\n" + candidate.address);
            String handoffStatus = guideAutomationEnabled
                    ? "주소와 경로가 확인될 때만 안내 시작을 도와드릴게요. 확인이 안 되면 파란 시작 버튼을 눌러주세요."
                    : "네이버 지도 경로 화면을 열었어요. 화면에서 경로를 확인하고 안내 시작을 눌러주세요.";
            showStatus(handoffStatus, false);
            speak(handoffStatus);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            NaverGuideAutomation.disarm(this, "launch_failed");
            waitingForMapReturn = false;
            setPendingCandidate(candidate);
            confirmButton.setEnabled(true);
            showStatus("NAVER 지도로 연결하지 못했어요. 설치 상태를 확인해 주세요.", true);
        } catch (Exception e) {
            NaverGuideAutomation.disarm(this, "launch_failed");
            waitingForMapReturn = false;
            setPendingCandidate(candidate);
            confirmButton.setEnabled(true);
            showStatus("경로를 열지 못했어요. 다시 시도해 주세요.", true);
        }
    }

    private void launchTaxiHandoff(Candidate candidate, boolean automateDestinationInput) {
        launchTaxiHandoff(candidate, automateDestinationInput,
                pendingCandidateContextGeneration, candidateGeneration);
    }

    private void launchTaxiHandoff(Candidate candidate, boolean automateDestinationInput,
                                  long expectedResultGeneration,
                                  long expectedCandidateGeneration) {
        if (!isCurrentCandidate(candidate, expectedCandidateGeneration)
                || pendingCandidateContextGeneration != expectedResultGeneration) return;
        if (!hasCandidateHandoffWindow(expectedResultGeneration)) return;
        Intent taxiApp = getPackageManager().getLaunchIntentForPackage("com.kakao.taxi");
        if (taxiApp == null) {
            new AlertDialog.Builder(this)
                    .setTitle("카카오 T 설치")
                    .setMessage("택시를 부르려면 카카오 T 앱이 필요해요.")
                    .setPositiveButton("설치 화면 열기", (dialog, which) -> openKakaoTStore())
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        try {
            String taxiQuery = taxiSearchText(candidate);
            boolean copied = copyTaxiDestination(taxiQuery);
            if (!hasCandidateHandoffWindow(expectedResultGeneration)) {
                KakaoTaxiAutomation.finish(this, "candidate_expired");
                return;
            }
            boolean specificAddress = DestinationAddressMatcher.hasStreetNumber(candidate.address);
            boolean automationArmed = automateDestinationInput && specificAddress;
            taxiDestinationCopied = copied && specificAddress;
            if (automationArmed) KakaoTaxiAutomation.arm(this,
                    candidate.nameIsFallback ? candidate.address : candidate.name, candidate.address);
            else KakaoTaxiAutomation.finish(this, "manual_handoff");
            if (!hasCandidateHandoffWindow(expectedResultGeneration)) {
                KakaoTaxiAutomation.finish(this, "candidate_expired");
                return;
            }
            startActivity(taxiApp);
            clearDestinationCandidateContext();
            destinationCorrectionContext.clear();
            pendingCandidate = candidate;
            selectTravelMode(TravelMode.TRANSIT);
            confirmButton.setText("네, 대중교통 경로 열기");
            confirmButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            waitingForTaxiReturn = true;
            destinationText.setText(candidateDisplayName(candidate) + "\n" + candidate.address);
            if (automationArmed) {
                String handoff = "확인한 목적지를 카카오 T에 입력하고, 같은 목적지가 보일 때 택시 호출을 진행할게요.";
                showStatus(handoff, false);
                speak(handoff);
            } else if (!specificAddress) {
                String handoff = "정확한 도로명 주소가 없어 자동 호출을 멈췄어요. 카카오 T에서 목적지와 주소를 직접 확인해 주세요.";
                showStatus(handoff, true);
                speak(handoff);
            } else if (copied) {
                showStatus("카카오 T를 열었어요. 복사된 주소를 목적지 칸에 붙여넣고 출발지·차종·요금을 확인한 뒤 호출해 주세요.", false);
                speak("카카오 티를 열었어요. 복사된 주소를 목적지 칸에 붙여넣고, 출발지와 요금을 확인한 뒤 호출해 주세요.");
            } else {
                showStatus("주소를 자동 복사하지 못했어요. 카카오 T에서 목적지를 직접 입력하고 출발지와 요금을 확인해 주세요.", false);
                speak("주소를 자동 복사하지 못했어요. 카카오 티에서 목적지를 직접 입력해 주세요.");
            }
        } catch (ActivityNotFoundException e) {
            KakaoTaxiAutomation.finish(this, "launch_failed");
            waitingForTaxiReturn = false;
            showStatus("카카오 T를 열지 못했어요. 설치 상태를 확인해 주세요.", true);
        } catch (RuntimeException e) {
            KakaoTaxiAutomation.finish(this, "launch_failed");
            waitingForTaxiReturn = false;
            showStatus("카카오 T를 열지 못했어요. 다시 시도해 주세요.", true);
        }
    }

    private boolean copyTaxiDestination(String destinationAddress) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || destinationAddress == null || destinationAddress.trim().isEmpty()) return false;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("MA 택시 목적지", destinationAddress.trim()));
            return clipboard.hasPrimaryClip();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String taxiSearchText(Candidate candidate) {
        if (candidate.address == null || candidate.address.trim().isEmpty()
                || "주소 정보 없음".equals(candidate.address.trim())) return candidate.name;
        return candidate.address.trim();
    }

    private void openKakaoTStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.kakao.taxi")));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.kakao.taxi")));
        }
    }

    private void openNaverStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.nhn.android.nmap")));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.nhn.android.nmap")));
        }
    }

    private void resetFlow(String message) {
        activeSearchId++;
        clearDestinationCandidateContext();
        cancelPendingAutoListen();
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        candidateSelectionAttempts = 0;
        destinationSpeechRetryCount = 0;
        destinationHypothesisRepeatGate.reset();
        destinationAreaOnlyPromptGate.reset();
        destinationSearchFailureCount = 0;
        activeSearchFromTextEntry = false;
        destinationClarificationLease.clear();
        destinationCorrectionContext.clear();
        hasUnverifiedConditionsForCurrentFlow = false;
        hasUngroundedAiConditionForCurrentFlow = false;
        usedLiteralAiFallbackForCurrentFlow = false;
        literalFallbackContextNoticeForCurrentFlow = "";
        activeConditionDetailsForCurrentFlow.clear();
        cancelledConditionTopicsForCurrentFlow.clear();
        recentlyCancelledConditionTopicsForCurrentFlow.clear();
        clearPendingConditionFlow();
        requestedAreaConstraintForCurrentFlow = "";
        someSearchResultsExcludedForCurrentFlow = false;
        excludedByAreaForCurrentFlow = 0;
        excludedByCategoryForCurrentFlow = 0;
        searchUsedNoExplicitAreaForCurrentFlow = false;
        regionConflictAttempts = 0;
        awaitingSpecialistVerification = false;
        specialistConditionForCurrentFlow = false;
        specialistConditionRequestId = -1L;
        specialistStatusUnverifiedForCandidate = false;
        destinationClarificationLease.setAwaitingReply(false, SystemClock.elapsedRealtime());
        destinationClarificationAttempts = 0;
        districtClarification.clear();
        awaitingDistrictClarification = false;
        repeatedAmbiguousDestination.clear();
        rememberedAmbiguousDestinationCandidates.clear();
        awaitingRepeatedDistrictClarification = false;
        invalidateSpeechCapture();
        selectTravelMode(TravelMode.TRANSIT);
        confirmButton.setText("네, 대중교통 경로 열기");
        speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
        cancelButton.setText("아니요, 처음으로");
        recognizedText.setText("말씀하신 내용이 여기에 표시됩니다.");
        destinationText.setText("");
        confirmButton.setVisibility(View.GONE);
        confirmButton.setEnabled(true);
        cancelButton.setVisibility(View.GONE);
        showStatus(message, false);
    }

    private void showStatus(String message, boolean announce) {
        CharSequence recognized = recognizedText == null ? null : recognizedText.getText();
        CharSequence destination = destinationText == null ? null : destinationText.getText();
        if (recognizedText != null) {
            recognizedText.setVisibility(recognized != null && recognized.length() > 0 ? View.VISIBLE : View.GONE);
        }
        if (destinationText != null) {
            destinationText.setVisibility(destination != null && destination.length() > 0 ? View.VISIBLE : View.GONE);
        }
        statusText.setText(message);
        statusText.setVisibility(message == null || message.trim().isEmpty() ? View.GONE : View.VISIBLE);
        if (announce) speak(message);
    }

    private void askForDestinationAgain(String message) {
        if (specialistConditionForCurrentFlow) {
            destinationClarificationLease.setAwaitingReply(true, SystemClock.elapsedRealtime());
            awaitingSpecialistVerification = true;
        }
        if (activeSearchFromTextEntry) {
            recognizedText.setText("장소를 글자로 다시 입력해 주세요.");
            showStatus(message + " 글자로 다시 입력해 주세요.", true);
            return;
        }
        showStatus(message, false);
        speakThenListen(message, SpeechPurpose.DESTINATION);
    }

    private void retryDestinationSpeech() {
        if (destinationSpeechRetryCount == 0) {
            destinationSpeechRetryCount = 1;
            askForDestinationAgain("잘 듣지 못했어요. 천천히 한 번 더 말씀해 주세요.");
            return;
        }
        destinationSpeechRetryCount = 0;
        showStatus("말씀을 듣지 못했어요. 마이크를 눌러 다시 말하거나 글자로 입력해 주세요.", true);
    }

    private void speak(String message) {
        clearPendingConditionFlow();
        speakAudio(message);
    }

    private void speakAudio(String message) {
        speakAudio(message, false);
    }

    private void speakAudio(String message, boolean confirmationPrompt) {
        if (!ttsReady || tts == null) return;
        String utteranceId = "ma-" + System.currentTimeMillis() + "-" + (++speechUtteranceSequence);
        if (confirmationPrompt) confirmationPromptGate.start(utteranceId);
        else if (queuedMicPurpose == SpeechPurpose.CANDIDATE_SELECTION
                && destinationCandidateContext.hasLease()) {
            scheduleCandidateListSpeechTimeout(utteranceId,
                    destinationCandidateContext.generation());
        }
        int result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (confirmationPrompt && result != TextToSpeech.SUCCESS) {
            finishConfirmationPrompt(utteranceId, false);
        } else if (result != TextToSpeech.SUCCESS) {
            finishCandidateListSpeech(utteranceId);
        }
    }

    private void scheduleCandidateListSpeechTimeout(String utteranceId, long expectedGeneration) {
        cancelCandidateListSpeechTimeout();
        candidateListSpeechUtteranceId = utteranceId;
        candidateListSpeechGeneration = expectedGeneration;
        Runnable timeout = new Runnable() {
            @Override public void run() {
                if (candidateListSpeechTimeoutTask != this
                        || !utteranceId.equals(candidateListSpeechUtteranceId)
                        || candidateListSpeechGeneration != expectedGeneration) return;
                boolean narrationStillRunning = tts != null && tts.isSpeaking();
                candidateListSpeechTimeoutTask = null;
                candidateListSpeechUtteranceId = "";
                candidateListSpeechGeneration = -1L;
                if (destinationCandidateContext.generation() != expectedGeneration) return;
                if (pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()
                        || !destinationCandidateContext.hasMinimumRemaining(expectedGeneration,
                        SystemClock.elapsedRealtime(), MIN_CANDIDATE_CONFIRMATION_WINDOW_MS)) {
                    discardDestinationCandidateContext("후보 안내가 길어져 목적지를 다시 찾아야 해요.");
                    return;
                }
                queuedMicPurpose = null;
                queuedMicPreserveClarificationContext = false;
                if (narrationStillRunning && tts != null) tts.stop();
                showStatus(narrationStillRunning
                        ? "후보를 모두 읽어드리지 못했어요. 화면 목록에서 확인하거나 번호를 말씀해 주세요."
                        : "화면 목록을 확인하거나 원하는 후보 번호를 말씀해 주세요.", narrationStillRunning);
            }
        };
        candidateListSpeechTimeoutTask = timeout;
        handler.postDelayed(timeout, DestinationCandidateContext.MAX_CANDIDATE_LIST_SPEECH_MS);
    }

    private void finishCandidateListSpeech(String utteranceId) {
        handler.post(() -> {
            if (!utteranceId.equals(candidateListSpeechUtteranceId)) return;
            cancelCandidateListSpeechTimeout();
        });
    }

    private void cancelCandidateListSpeechTimeout() {
        if (candidateListSpeechTimeoutTask != null) {
            handler.removeCallbacks(candidateListSpeechTimeoutTask);
            candidateListSpeechTimeoutTask = null;
        }
        candidateListSpeechUtteranceId = "";
        candidateListSpeechGeneration = -1L;
    }

    private void finishConfirmationPrompt(String utteranceId, boolean completed) {
        handler.post(() -> {
            if (!confirmationPromptGate.finish(utteranceId, completed,
                    SystemClock.elapsedRealtime())) return;
            Candidate candidate = pendingCandidate;
            if (!completed) {
                if (isCurrentCandidate(candidate, candidateGeneration)) {
                    showStatus("목적지 확인 안내를 끝까지 읽지 못했어요. 화면의 목적지를 확인한 뒤 아래 확인 버튼을 눌러 주세요.", true);
                }
                return;
            }
            if (microphonePressed && isCurrentCandidate(candidate, candidateGeneration)) {
                scheduleConfirmationSpeechAfterEchoGuard(candidate, candidateGeneration,
                        confirmationPromptGate.voiceApprovalDelayRemaining(SystemClock.elapsedRealtime()));
            }
        });
    }

    private void scheduleConfirmationSpeechAfterEchoGuard(Candidate candidate, long generation,
                                                          long delayMillis) {
        handler.postDelayed(() -> {
            if (microphonePressed && isCurrentCandidate(candidate, generation)
                    && confirmationPromptGate.isReadyForVoiceApproval(SystemClock.elapsedRealtime())) {
                beginSpeech();
            }
        }, Math.max(0L, delayMillis));
    }

    private void runSync() {
        if (!moduleFailure.isEmpty() || !syncRunning.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                AutoSyncModule.Result result = null;
                String failure = null;
                try { result = autosync.syncOnce(); }
                catch (RuntimeException e) { failure = "AutoSync · 설정을 확인하세요."; }
                finally { syncRunning.set(false); }
                AutoSyncModule.Result completed = result;
                String error = failure;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) showSync(error);
                    else if (completed != null) {
                        if ("needs-configuration".equals(completed.status)) {
                            showSync("AutoSync · 서버 미설정 · 로컬 저장 중");
                        } else if ("offline".equals(completed.status)) {
                            showSync("AutoSync · 오프라인 · 변경은 이 기기에 보관");
                        } else if ("needs-credential".equals(completed.status)) {
                            showSync("AutoSync · 서버 인증을 확인하세요");
                        } else {
                            showSync("AutoSync · " + completed.detail);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            syncRunning.set(false);
        }
    }

    private void runUpdateCheck() {
        if (!moduleFailure.isEmpty() || !updateRunning.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                AutoUpdateModule.Result result = null;
                String failure = null;
                try { result = autoupdate.checkAndDownload(); }
                catch (RuntimeException e) { failure = "AutoUpdate · 설정 또는 저장 공간을 확인하세요."; }
                finally { updateRunning.set(false); }
                AutoUpdateModule.Result completed = result;
                String error = failure;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) showUpdate(error);
                    else if (completed != null) {
                        showUpdate("not-configured".equals(completed.status)
                                ? "AutoUpdate · 앱별 manifest 설정 필요"
                                : "AutoUpdate · " + completed.message);
                        if ("needs-github-token".equals(completed.status)
                                || "not-configured".equals(completed.status)) {
                            setSettingsAttention(true);
                        } else if ("up-to-date".equals(completed.status)
                                || "update-ready".equals(completed.status)) {
                            setSettingsAttention(false);
                        }
                        if ("update-ready".equals(completed.status)) {
                            if (updateInstallPermissionBlocked) {
                                updateButton.setVisibility(View.VISIBLE);
                            } else {
                                updateButton.setVisibility(View.GONE);
                                applyUpdate(false);
                            }
                        } else {
                            updateButton.setVisibility(View.GONE);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            updateRunning.set(false);
        }
    }

    private void applyUpdate() {
        applyUpdate(true);
    }

    private void applyUpdate(boolean userInitiated) {
        if (userInitiated) updateInstallPermissionBlocked = false;
        AutoUpdateModule.Result result = autoupdate.installPending(this);
        showUpdate("AutoUpdate · " + result.message);
        if ("needs-permission".equals(result.status)) {
            awaitingUpdateInstallPermission = true;
            Toast.makeText(this, "설정에서 설치 권한을 허용하면 자동으로 이어집니다.", Toast.LENGTH_LONG).show();
        } else if ("install-started".equals(result.status)) {
            awaitingUpdateInstallPermission = false;
            updateButton.setVisibility(View.GONE);
        } else if ("error".equals(result.status)) {
            updateButton.setVisibility(View.VISIBLE);
        }
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(10), dp(22), dp(6));

        TextView intro = new TextView(this);
        intro.setText("MA가 새 버전을 자동으로 확인합니다.");
        intro.setTextSize(20);
        intro.setTypeface(null, Typeface.BOLD);
        intro.setTextColor(0xff182f33);
        intro.setPadding(0, dp(4), 0, dp(10));
        panel.addView(intro);

        TextView installedVersion = new TextView(this);
        installedVersion.setText("현재 설치 버전: MA " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\n업데이트 연결: " + updateChannelDescription());
        installedVersion.setTextSize(17);
        installedVersion.setTextColor(0xff182f33);
        installedVersion.setPadding(0, 0, 0, dp(12));
        panel.addView(installedVersion);

        TextView schedule = new TextView(this);
        schedule.setText("앱을 켤 때마다 확인하고, 사용 중에는 "
                + (AutoUpdateModule.FOREGROUND_INTERVAL_MS / 60_000L) + "분마다 다시 확인해요."
                + "\n앱이 백그라운드에 있으면 Android가 허용하는 주기에 따라 확인해요."
                + "\n새 버전이 있으면 내려받은 뒤 Android 설치 화면을 열어요.");
        schedule.setTextSize(17);
        schedule.setTextColor(0xff465568);
        schedule.setLineSpacing(dp(3), 1f);
        panel.addView(schedule);

        updateSetupStatus = new TextView(this);
        updateSetupStatus.setText(updateStatus == null ? "업데이트 상태를 확인하는 중이에요." : updateStatus.getText());
        updateSetupStatus.setTextSize(16);
        updateSetupStatus.setTextColor(0xff17685f);
        updateSetupStatus.setPadding(0, dp(14), 0, dp(12));
        panel.addView(updateSetupStatus);

        Button checkNow = button("지금 업데이트 확인", 18, 64);
        checkNow.setOnClickListener(v -> {
            updateSetupStatus.setText("새 버전이 있는지 확인하고 있어요.");
            runUpdateCheck();
        });
        panel.addView(checkNow, fullWidth());

        TextView automatic = new TextView(this);
        automatic.setText("새 버전은 MA가 자동으로 내려받아요. Android가 설치 확인을 요청하면 화면에서 적용해 주세요.");
        automatic.setTextSize(16);
        automatic.setTextColor(0xff465568);
        automatic.setPadding(0, dp(12), 0, dp(8));
        panel.addView(automatic);

        Button details = button("기타 설정", 17, 58);
        details.setOnClickListener(v -> {
            if (updateSetupDialog != null) updateSetupDialog.dismiss();
            showAdvancedSettings();
        });
        LinearLayout.LayoutParams detailsParams = fullWidth();
        detailsParams.topMargin = dp(8);
        panel.addView(details, detailsParams);

        updateSetupDialog = new AlertDialog.Builder(this)
                .setTitle("자동 업데이트")
                .setView(panel)
                .setNegativeButton("닫기", null)
                .create();
        updateSetupDialog.setOnDismissListener(dialog -> {
            updateSetupDialog = null;
            updateSetupStatus = null;
        });
        updateSetupDialog.show();
    }

    private void showAdvancedSettings() {
        ScrollView settingsScroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView identity = new TextView(this);
        identity.setText("앱 ID: " + AppIdentity.APP_ID
                + "\nAutoSync 토큰과 CLOVA Studio·Gemini·NAVER API HUB 인증정보는 Android Keystore로 암호화됩니다."
                + "\n목적지와 음성 내용은 AutoSync에 넣지 않습니다.");
        form.addView(identity);
        EditText server = field("AutoSync 서버 주소 (HTTPS)", settings.serverUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(server);
        EditText token = field("AutoSync 앱 토큰 (빈칸이면 기존 토큰 유지)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(token);
        CheckBox clearToken = new CheckBox(this);
        clearToken.setText("기존 AutoSync 토큰 삭제");
        form.addView(clearToken);
        EditText githubRepository = field("MA 공개 업데이트 저장소 (자동 설정)",
                settings.githubUpdateRepository(), InputType.TYPE_CLASS_TEXT);
        githubRepository.setVisibility(View.GONE);
        form.addView(githubRepository);
        TextView githubHelp = new TextView(this);
        githubHelp.setText("MA 공개 업데이트 채널을 사용하므로 로그인이나 토큰이 필요하지 않습니다.");
        githubHelp.setVisibility(View.GONE);
        githubHelp.setTextSize(14);
        githubHelp.setPadding(0, dp(4), 0, dp(6));
        form.addView(githubHelp);
        EditText githubToken = field("GitHub 읽기 전용 토큰 (최초 1회, 빈칸이면 기존 토큰 유지)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        githubToken.setTransformationMethod(PasswordTransformationMethod.getInstance());
        githubToken.setVisibility(View.GONE);
        form.addView(githubToken);
        CheckBox clearGithubToken = new CheckBox(this);
        clearGithubToken.setText("저장된 GitHub 업데이트 토큰 삭제");
        clearGithubToken.setVisibility(View.GONE);
        form.addView(clearGithubToken);
        EditText geminiKey = field("Gemini API 키 (실시간 음성 AI 대화에 필요)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        geminiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(geminiKey);
        CheckBox clearGeminiKey = new CheckBox(this);
        clearGeminiKey.setText("저장된 Gemini API 키 삭제");
        form.addView(clearGeminiKey);
        EditText clovaStudioKey = field("CLOVA Studio API 키 · HCX-007 (선택, 설정 시 우선 사용)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clovaStudioKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(clovaStudioKey);
        CheckBox clearClovaStudioKey = new CheckBox(this);
        clearClovaStudioKey.setText("저장된 CLOVA Studio API 키 삭제");
        form.addView(clearClovaStudioKey);
        EditText naverClientId = field("NAVER API HUB Client ID (실제 장소 검색에 필요)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        naverClientId.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(naverClientId);
        CheckBox clearNaverClientId = new CheckBox(this);
        clearNaverClientId.setText("저장된 NAVER API HUB Client ID 삭제");
        form.addView(clearNaverClientId);
        EditText naverClientSecret = field("NAVER API HUB Client Secret", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        naverClientSecret.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(naverClientSecret);
        CheckBox clearNaverClientSecret = new CheckBox(this);
        clearNaverClientSecret.setText("저장된 NAVER API HUB Client Secret 삭제");
        form.addView(clearNaverClientSecret);
        TextView clovaHelp = new TextView(this);
        clovaHelp.setText("CLOVA Studio 서비스 API 키를 저장하면 HyperCLOVA X가 말뜻을 우선 해석합니다. NAVER API HUB Client ID·Secret과는 별도 키입니다. CLOVA 키가 비어 있으면 Gemini를 사용합니다.");
        clovaHelp.setTextSize(14);
        clovaHelp.setPadding(0, dp(4), 0, dp(6));
        form.addView(clovaHelp);
        TextView aiHelp = new TextView(this);
        aiHelp.setText("마이크 음성은 Android 음성인식으로 글자 변환하지 않고 Gemini Live와 직접 실시간 대화합니다. Gemini API 키가 필요해요. 장소명·주소·좌표는 NAVER API HUB 실제 검색 결과를 사용하고, 검색에 실패하면 목적지를 추측하지 않습니다.");
        aiHelp.setTextSize(14);
        aiHelp.setPadding(0, dp(4), 0, dp(6));
        form.addView(aiHelp);
        EditText geminiProxy = field("AI 해석 서버 주소 (선택, HTTPS)", settings.geminiProxyUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(geminiProxy);
        settingsScroll.addView(form);
        new AlertDialog.Builder(this).setTitle("모듈 설정").setView(settingsScroll).setPositiveButton("저장", (d, w) -> {
            try {
                String nextToken = clearToken.isChecked() ? "" : token.getText().toString();
                if (!clearToken.isChecked() && nextToken.isEmpty()) nextToken = settings.autosyncToken();
                String nextGeminiKey = clearGeminiKey.isChecked() ? "" : geminiKey.getText().toString();
                if (!clearGeminiKey.isChecked() && nextGeminiKey.isEmpty()) {
                    nextGeminiKey = settings.geminiApiKey();
                }
                String nextClovaStudioKey = clearClovaStudioKey.isChecked()
                        ? "" : clovaStudioKey.getText().toString();
                if (!clearClovaStudioKey.isChecked() && nextClovaStudioKey.isEmpty()) {
                    nextClovaStudioKey = settings.clovaStudioApiKey();
                }
                String nextNaverClientId = clearNaverClientId.isChecked()
                        ? "" : naverClientId.getText().toString();
                if (!clearNaverClientId.isChecked() && nextNaverClientId.isEmpty()) {
                    nextNaverClientId = settings.naverApiClientId();
                }
                String nextNaverClientSecret = clearNaverClientSecret.isChecked()
                        ? "" : naverClientSecret.getText().toString();
                if (!clearNaverClientSecret.isChecked() && nextNaverClientSecret.isEmpty()) {
                    nextNaverClientSecret = settings.naverApiClientSecret();
                }
                settings.save(server.getText().toString(), nextToken, settings.updateManifestUrl(),
                        geminiProxy.getText().toString(), nextGeminiKey,
                        nextNaverClientId, nextNaverClientSecret);
                settings.saveClovaStudioApiKey(nextClovaStudioKey);
                settings.saveGithubUpdateConfig(githubRepository.getText().toString(),
                        githubToken.getText().toString(), clearGithubToken.isChecked());
                closeLiveVoiceSession();
                preconnectLiveVoiceSession();
                showSync("설정을 저장했습니다. 로컬 목적지 데이터는 동기화하지 않습니다.");
                showStatus("AI 및 장소 검색 설정을 저장했어요.", false);
                runSync();
                runUpdateCheck();
            } catch (Exception e) {
                Toast.makeText(this, "설정 저장 실패 · " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }).setNegativeButton("취소", null).show();
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
            byte[] bytes = new byte[4096];
            int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            JSONObject manifest = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (!"autoupdate-app-modules-v1".equals(manifest.optString("contract"))
                    || !AppIdentity.APP_ID.equals(manifest.optString("app_id"))) {
                return "앱 모듈 manifest ID 불일치";
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
            return "app-modules.json을 읽지 못했습니다.";
        }
    }

    private void showSync(String text) {
        if (syncStatus != null) syncStatus.setText(text);
    }

    private void showUpdate(String text) {
        if (updateStatus != null) updateStatus.setText(text);
        if (updateSetupStatus != null) updateSetupStatus.setText(text);
    }

    private String updateChannelDescription() {
        String manifestUrl = settings == null ? "" : settings.updateManifestUrl().trim();
        if (!manifestUrl.isEmpty()) return "이 PC 업데이트 서버 (" + manifestUrl + ")";
        String repository = settings == null ? "" : settings.githubUpdateRepository().trim();
        if (!repository.isEmpty()) return "GitHub Releases (" + repository + ")";
        return "연결 안 됨";
    }

    private void setSettingsAttention(boolean required) {
        if (settingsButton == null) return;
        if (settingsPulse != null) {
            settingsPulse.cancel();
            settingsPulse = null;
        }
        settingsButton.setAlpha(1f);
        settingsButton.setScaleX(1f);
        settingsButton.setScaleY(1f);
        settingsButton.setBackground(required ? circleBackground(0xffd9f3ee) : null);
        settingsButton.setContentDescription(required
                ? "깜박이는 설정 버튼을 눌러 자동 업데이트 연결을 설정하세요."
                : "설정");
        if (required) {
            settingsPulse = ObjectAnimator.ofFloat(settingsButton, View.ALPHA, 1f, 0.5f);
            settingsPulse.setDuration(700L);
            settingsPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            settingsPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            settingsPulse.start();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private enum TravelMode { TRANSIT, TAXI }
    private enum SpeechPurpose { DESTINATION, CANDIDATE_SELECTION, CONFIRMATION, SPECIALIST_CONSENT }

    private static final class ConditionState {
        final String label;
        final String polarity;
        ConditionState(String label, String polarity) {
            this.label = label == null ? "" : label.trim();
            this.polarity = "AVOID".equals(polarity) ? "AVOID" : "REQUIRE";
        }
    }

    private static final class ConditionAction {
        final String topic;
        final ConditionState state;
        final boolean cancelled;

        private ConditionAction(String topic, ConditionState state, boolean cancelled) {
            this.topic = topic;
            this.state = state;
            this.cancelled = cancelled;
        }

        static ConditionAction cancel(String topic) {
            return new ConditionAction(topic, null, true);
        }

        static ConditionAction set(String topic, ConditionState state) {
            return new ConditionAction(topic, state, false);
        }
    }

    private static final class Candidate {
        final String name;
        final String address;
        final double lat;
        final double lng;
        final boolean nameIsFallback;
        int voiceOrdinal;
        String voiceLabel;
        Candidate(String name, String address, double lat, double lng, boolean nameIsFallback) {
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
            this.nameIsFallback = nameIsFallback;
        }
    }

    private static final class PendingLiveToolReply {
        final GeminiLiveVoiceSession session;
        final JSONArray responses;
        final String exactSpeech;
        final String candidateId;
        final long presentationTurn;
        Runnable timeout;

        PendingLiveToolReply(GeminiLiveVoiceSession session, JSONArray responses,
                             String exactSpeech, String candidateId,
                             long presentationTurn) {
            this.session = session;
            this.responses = responses == null ? new JSONArray() : responses;
            this.exactSpeech = exactSpeech == null ? "" : exactSpeech;
            this.candidateId = candidateId == null ? "" : candidateId;
            this.presentationTurn = presentationTurn;
        }
    }
}
