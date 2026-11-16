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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Voice-first MA. Destinations remain local; confirmed public-transit routes go to NAVER Maps and confirmed taxi trips go to Kakao T. */
public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 4101;
    private static final long CANDIDATE_APPROVAL_TTL_MS = 5 * 60 * 1000L;
    private static final long SPECIALIST_CONSENT_TTL_MS = 2 * 60 * 1000L;
    private static final long UI_APPROVAL_TURN_SEQUENCE = -1L;
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
    private TextView settingsButton;
    private ObjectAnimator settingsPulse;
    private LinearLayout speakButton;
    private ImageButton taxiButton;
    private TravelMode travelMode = TravelMode.TRANSIT;
    private Button confirmButton;
    private Button cancelButton;
    private Button updateButton;
    private boolean ttsReady;
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
    private int confirmationListenAttempts;
    private int destinationSpeechRetryCount;
    private int destinationClarificationAttempts;
    private int destinationSearchFailureCount;
    private boolean activeSearchFromTextEntry;
    private long activeSearchId;
    private long specialistConditionRequestId = -1L;
    private long speechCaptureSequence;
    private long candidateGeneration;
    private long pendingCandidateExpiresAtElapsedRealtime;
    private String moduleFailure = "";
    private String awaitingClarificationUtterance = "";
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
    private boolean awaitingDestinationRetry;
    private Candidate pendingCandidate;
    private ArrayList<Candidate> pendingCandidateChoices;
    private ArrayList<Candidate> voiceCandidateChoices;
    private ArrayList<Candidate> confirmationCandidateChoices;
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
        if (waitingForGuideAccessibilitySettings) {
            waitingForGuideAccessibilitySettings = false;
            Candidate candidate = pendingCandidate;
            if (candidate != null && isNaverGuideServiceEnabled()) {
                openConfirmedRoute(candidate, candidateGeneration, UI_APPROVAL_TURN_SEQUENCE);
            } else if (candidate != null && !isCurrentCandidate(candidate, candidateGeneration)) {
                clearPendingCandidate();
                confirmButton.setVisibility(View.GONE);
                showStatus("확인 시간이 지났어요. 목적지를 다시 찾아 주세요.", true);
            } else {
                showStatus("MA 네이버 안내 보조 기능이 켜지지 않았어요. 경로 확인 버튼을 다시 눌러 설정할 수 있어요.", true);
            }
        } else if (waitingForTaxiAccessibilitySettings) {
            waitingForTaxiAccessibilitySettings = false;
            Candidate candidate = pendingCandidate;
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
    }

    @Override protected void onPause() {
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(updateTick);
        microphonePressed = false;
        cancelPendingAutoListen();
        if (speechRecognizer != null) invalidateSpeechCapture();
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelPendingAutoListen();
        handler.removeCallbacksAndMessages(null);
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
                if (microphonePressed) return true;
                microphonePressed = true;
                speakButton.setScaleX(0.98f);
                speakButton.setScaleY(0.98f);
                beginSpeech();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                microphonePressed = false;
                speakButton.setScaleX(1f);
                speakButton.setScaleY(1f);
                stopSpeechCapture();
                view.performClick();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                microphonePressed = false;
                speakButton.setScaleX(1f);
                speakButton.setScaleY(1f);
                if (speechRecognizer != null) invalidateSpeechCapture();
                showStatus("음성 입력을 취소했어요.", false);
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
            awaitingClarificationUtterance = "";
            awaitingSpecialistVerification = false;
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            awaitingDestinationRetry = false;
        }
        if (pendingCandidate != null) {
            selectTravelMode(TravelMode.TAXI);
            showCandidate(pendingCandidate);
            return;
        }
        selectTravelMode(TravelMode.TAXI);
        speakThenListen("택시를 타고 어디로 가실까요? 마이크를 누르고 있는 동안 말씀해 주세요.",
                SpeechPurpose.DESTINATION);
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
    }

    private void beginSpeech() {
        if (queuedMicPurpose != null) {
            SpeechPurpose purpose = queuedMicPurpose;
            boolean preserveContext = queuedMicPreserveClarificationContext;
            queuedMicPurpose = null;
            queuedMicPreserveClarificationContext = false;
            beginSpeech(purpose, preserveContext);
            return;
        }
        if (isPendingSpecialistConsentCurrent()) {
            beginSpeech(SpeechPurpose.SPECIALIST_CONSENT, true);
            return;
        }
        if (awaitingDestinationRetry) {
            beginSpeech(SpeechPurpose.DESTINATION, true);
            return;
        }
        if (pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
            beginSpeech(SpeechPurpose.CANDIDATE_SELECTION, true);
            return;
        }
        if (pendingCandidate != null && isCurrentCandidate(pendingCandidate, candidateGeneration)) {
            beginSpeech(SpeechPurpose.CONFIRMATION, true);
            return;
        }
        beginSpeech(SpeechPurpose.DESTINATION, false);
    }

    private void beginSpeech(SpeechPurpose purpose) {
        beginSpeech(purpose, false);
    }

    private void beginSpeech(SpeechPurpose purpose, boolean preserveClarificationContext) {
        if (!microphonePressed) {
            queuedMicPurpose = purpose;
            queuedMicPreserveClarificationContext = preserveClarificationContext;
            showStatus("말씀하실 때는 마이크 버튼을 누르고 있어 주세요.", false);
            return;
        }
        if (speechActive) return;
        cancelPendingAutoListen();
        if (!microphonePressed) return;
        permissionSpeechPurpose = purpose;
        permissionPreserveClarificationContext = preserveClarificationContext;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showSpeechRecognitionUnavailableFallback(purpose);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        if (purpose == SpeechPurpose.DESTINATION) {
            if (!preserveClarificationContext) {
                awaitingClarificationUtterance = "";
                awaitingSpecialistVerification = false;
                specialistConditionForCurrentFlow = false;
                specialistStatusUnverifiedForCandidate = false;
                awaitingDestinationRetry = false;
                specialistConditionRequestId = -1L;
                destinationClarificationAttempts = 0;
                destinationSearchFailureCount = 0;
            }
            activeSearchId++;
            clearPendingSpecialistConsent();
            clearPendingCandidate();
            pendingCandidateChoices = null;
            voiceCandidateChoices = null;
            confirmButton.setVisibility(View.GONE);
        }
        try {
            invalidateSpeechCapture();
            long captureSequence = speechCaptureSequence;
            long searchIdAtCapture = activeSearchId;
            Candidate candidateAtCapture = purpose == SpeechPurpose.CONFIRMATION ? pendingCandidate : null;
            long candidateGenerationAtCapture = candidateGeneration;
            TravelMode modeAtCapture = travelMode;
            SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer = recognizer;
            activeSpeechPurpose = purpose;
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean isCurrent() {
                    return isCurrentSpeechCapture(recognizer, captureSequence);
                }

                @Override public void onReadyForSpeech(Bundle params) {
                    if (!isCurrent()) return;
                    String prompt = purpose == SpeechPurpose.CONFIRMATION
                            ? "목적지가 맞는지 듣고 있어요."
                            : purpose == SpeechPurpose.SPECIALIST_CONSENT
                            ? "전문의 확인 없이 검색해도 되는지 듣고 있어요."
                            : purpose == SpeechPurpose.CANDIDATE_SELECTION
                            ? "장소 후보 중에서 고르는 말씀을 듣고 있어요."
                            : (modeAtCapture == TravelMode.TAXI
                            ? "택시 목적지를 듣고 있어요." : "대중교통 목적지를 듣고 있어요.");
                    showStatus(prompt, false);
                    speakButton.setContentDescription("듣는 중입니다. 손을 떼면 음성 입력을 마칩니다.");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (isCurrent()) speakButton.setContentDescription("말씀을 확인하고 있습니다.");
                }
                @Override public void onError(int error) {
                    if (!finishSpeechCapture(recognizer, captureSequence)) return;
                    speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
                    cancelButton.setText(purpose == SpeechPurpose.SPECIALIST_CONSENT
                            ? "검색 취소" : "처음으로");
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            || !hasMicrophonePermission()) {
                        showMicrophonePermissionFallback(purpose);
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        showSpeechRecognitionUnavailableFallback(purpose);
                        return;
                    }
                    if (purpose == SpeechPurpose.SPECIALIST_CONSENT) {
                        handleSpecialistConsent(searchIdAtCapture, "");
                    } else if (purpose == SpeechPurpose.CONFIRMATION) {
                        handleDestinationConfirmation("", candidateAtCapture,
                                candidateGenerationAtCapture, captureSequence, modeAtCapture);
                    } else if (purpose == SpeechPurpose.CANDIDATE_SELECTION) {
                        handleCandidateSelection("");
                    } else {
                        retryDestinationSpeech();
                    }
                }
                @Override public void onResults(Bundle results) {
                    if (!finishSpeechCapture(recognizer, captureSequence)) return;
                    speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
                    cancelButton.setText(purpose == SpeechPurpose.SPECIALIST_CONSENT
                            ? "검색 취소" : "처음으로");
                    ArrayList<String> matches = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty() || matches.get(0).trim().isEmpty()) {
                        if (purpose == SpeechPurpose.SPECIALIST_CONSENT) {
                            handleSpecialistConsent(searchIdAtCapture, "");
                        } else if (purpose == SpeechPurpose.CONFIRMATION) {
                            handleDestinationConfirmation("", candidateAtCapture,
                                    candidateGenerationAtCapture, captureSequence, modeAtCapture);
                        } else if (purpose == SpeechPurpose.CANDIDATE_SELECTION) {
                            handleCandidateSelection("");
                        } else {
                            retryDestinationSpeech();
                        }
                        return;
                    }
                    if (purpose == SpeechPurpose.DESTINATION) destinationSpeechRetryCount = 0;
                    if (purpose == SpeechPurpose.SPECIALIST_CONSENT) {
                        handleSpecialistConsent(searchIdAtCapture, matches.get(0));
                    } else if (purpose == SpeechPurpose.CONFIRMATION) {
                        DestinationConfirmationParser.Decision decision =
                                DestinationConfirmationParser.classifyHypotheses(matches);
                        String confirmedUtterance = DestinationConfirmationParser
                                .representativeHypothesis(matches, decision);
                        handleDestinationConfirmation(confirmedUtterance, candidateAtCapture,
                                candidateGenerationAtCapture, captureSequence, modeAtCapture);
                    } else if (purpose == SpeechPurpose.CANDIDATE_SELECTION) {
                        Candidate alternate = findUnambiguousAlternativeCandidate(matches);
                        if (alternate != null) showCandidate(alternate);
                        else handleCandidateSelection(matches.get(0));
                    } else {
                        handleRequest(matches.get(0));
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, purpose == SpeechPurpose.CONFIRMATION
                    ? "목적지가 맞으면 맞다고 말씀해 주세요."
                    : purpose == SpeechPurpose.SPECIALIST_CONSENT
                    ? "검색에 동의하면 네, 취소하려면 아니요라고 말씀해 주세요."
                    : purpose == SpeechPurpose.CANDIDATE_SELECTION
                    ? "원하는 장소의 번호나 이름을 말씀해 주세요." : "가고 싶은 장소를 말씀해 주세요.");
            speechActive = true;
            recognizer.startListening(intent);
            speakButton.setContentDescription("듣는 중입니다. 손을 떼면 음성 입력을 마칩니다.");
            cancelButton.setText("듣기 취소");
            cancelButton.setVisibility(View.VISIBLE);
            String prompt = purpose == SpeechPurpose.CONFIRMATION ? "목적지가 맞는지 말씀해 주세요."
                    : purpose == SpeechPurpose.SPECIALIST_CONSENT ? "검색에 동의하는지 말씀해 주세요."
                    : purpose == SpeechPurpose.CANDIDATE_SELECTION ? "원하는 장소의 번호나 이름을 말씀해 주세요."
                    : (travelMode == TravelMode.TAXI ? "택시 목적지를 음성으로 말씀해 주세요."
                    : "대중교통 목적지를 음성으로 말씀해 주세요.");
            showStatus(prompt, false);
        } catch (RuntimeException e) {
            invalidateSpeechCapture();
            speakButton.setContentDescription("마이크를 누르고 있는 동안 말하기");
            showSpeechRecognitionUnavailableFallback(purpose);
        }
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
        SpeechRecognizer active = speechRecognizer;
        if (!speechActive || active == null) return;
        showStatus("손을 뗐어요. 들은 내용을 확인하고 있어요.", false);
        try {
            active.stopListening();
        } catch (RuntimeException e) {
            invalidateSpeechCapture();
            showStatus("음성 입력을 마치지 못했어요. 마이크를 다시 눌러 주세요.", true);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (microphonePressed) {
                    beginSpeech(permissionSpeechPurpose, permissionPreserveClarificationContext);
                } else {
                    queuedMicPurpose = permissionSpeechPurpose;
                    queuedMicPreserveClarificationContext = permissionPreserveClarificationContext;
                    showStatus("마이크 버튼을 누르고 있는 동안 말씀해 주세요.", false);
                }
            } else {
                showMicrophonePermissionFallback(permissionSpeechPurpose);
            }
        }
    }

    private void showMicrophonePermissionFallback(SpeechPurpose purpose) {
        if (purpose == SpeechPurpose.CANDIDATE_SELECTION
                && pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
            prepareCandidateChoiceScreen(
                    "마이크를 사용할 수 없어요. 화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.",
                    false);
            showCandidateChoiceDialog();
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
            showCandidateChoiceDialog();
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

    private void handleRequest(String utterance, boolean fromTextEntry) {
        long requestId = ++activeSearchId;
        activeSearchFromTextEntry = fromTextEntry;
        if (fromTextEntry) destinationSearchFailureCount = 0;
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        confirmButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        recognizedText.setText("들은 말: " + utterance.trim());
        destinationText.setText("");
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(utterance);
        if (parsed.modeSpecified) {
            selectTravelMode(parsed.taxiMode ? TravelMode.TAXI : TravelMode.TRANSIT);
        }
        String clarificationContext = awaitingClarificationUtterance;
        boolean continuesClarification = !clarificationContext.trim().isEmpty() || awaitingDestinationRetry;
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
                awaitingClarificationUtterance = mergeClarificationContext(clarificationContext, utterance);
            }
            askForDestinationAgain(parsed.message);
            return;
        }
        awaitingClarificationUtterance = "";
        awaitingDestinationRetry = false;
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
        try {
            resolverWorker.execute(() -> {
                DestinationAiClient.Result interpreted;
                try {
                    String proxyUrl = settings.geminiProxyUrl();
                    String apiKey = settings.geminiApiKey();
                    if (!DestinationAiClient.isConfigured(proxyUrl, apiKey)) {
                        handler.post(() -> showDestinationSetupRequired(requestId));
                        return;
                    }
                    interpreted = DestinationAiClient.interpret(getApplicationContext(), proxyUrl,
                            apiKey, utterance, clarificationContext);
                } catch (Exception e) {
                    handler.post(() -> showDestinationServiceFailure(requestId, "AI가 목적지 뜻을 확인하지 못했어요. 연결을 확인하고 다시 말씀해 주세요."));
                    return;
                }
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
                    if (interpreted.needsClarification) {
                        if (destinationClarificationAttempts >= 1) {
                            awaitingClarificationUtterance = mergeClarificationContext(
                                    clarificationContext, utterance);
                            awaitingSpecialistVerification = requiresSpecialistVerification;
                            showStatus("장소를 정하기 어려워요. 동네와 장소 이름을 함께 말씀하시거나 ‘글자로 입력’을 눌러 주세요.", true);
                            recognizedText.setText("장소를 더 자세히 알려 주세요.");
                            return;
                        }
                        destinationClarificationAttempts++;
                        awaitingClarificationUtterance = mergeClarificationContext(
                                clarificationContext, utterance);
                        awaitingSpecialistVerification = requiresSpecialistVerification;
                        showStatus(interpreted.clarificationQuestion, false);
                        recognizedText.setText("들은 말: " + utterance + "\n장소를 더 확인하고 있어요.");
                        if (activeSearchFromTextEntry) speak(interpreted.clarificationQuestion);
                        else speakThenListen(interpreted.clarificationQuestion, SpeechPurpose.DESTINATION);
                        return;
                    }
                    destinationClarificationAttempts = 0;
                    awaitingClarificationUtterance = "";
                    awaitingSpecialistVerification = false;
                    String guardedQuery = DestinationQueryGuard.preserveExplicitIntent(
                            clarificationContext, utterance, interpreted.searchQuery);
                    if (guardedQuery.isEmpty()) {
                        showDestinationServiceFailure(requestId, "지역과 장소 종류를 유지한 검색어를 만들지 못했어요. 다시 말씀해 주세요.");
                        return;
                    }
                    recognizedText.setText("들은 말: " + utterance + "\n장소 검색: " + guardedQuery);
                    showStatus("실제 장소 정보를 확인하고 있어요.", false);
                    resolveInterpretedDestination(guardedQuery, localFallbackQuery,
                            clarificationContext, requestId,
                            requiresSpecialistVerification);
                });
            });
        } catch (RejectedExecutionException e) {
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

    private void showDestinationSetupRequired(long requestId) {
        if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
        showStatus("목적지를 추측하지 않았어요. 설정에서 Gemini와 NAVER 장소 검색을 연결해 주세요.", true);
        recognizedText.setText("AI 해석과 실제 장소 검색 설정이 필요합니다.");
        confirmButton.setText("AI 검색 설정 열기");
        confirmButton.setOnClickListener(v -> showSettings());
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setText("처음으로");
        cancelButton.setOnClickListener(v -> resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."));
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void showDestinationServiceFailure(long requestId, String message) {
        if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
        showStatus(message, true);
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
            awaitingClarificationUtterance = localFallbackQuery == null ? "" : localFallbackQuery;
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
                awaitingDestinationRetry = false;
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
        DestinationConfirmationParser.Decision decision = DestinationConfirmationParser.classify(utterance);
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
            awaitingDestinationRetry = false;
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
        String naverClientId = settings.naverApiClientId();
        String naverClientSecret = settings.naverApiClientSecret();
        if (naverClientId.trim().isEmpty() || naverClientSecret.trim().isEmpty()) {
            showDestinationSetupRequired(requestId);
            return;
        }
        try {
            resolverWorker.execute(() -> {
                try {
                    NaverLocalSearchClient.Result result = NaverLocalSearchClient.search(
                            naverClientId, naverClientSecret, query);
                    handler.post(() -> postPlaceCandidates(query, result.places, requestId));
                } catch (Exception e) {
                    handler.post(() -> showDestinationServiceFailure(requestId,
                            "네이버 장소 검색에 실패했어요. 네트워크와 API 설정을 확인해 주세요."));
                }
            });
        } catch (RejectedExecutionException e) {
            showStatus("잠시 뒤 다시 말씀해 주세요.", true);
        }
    }

    private void postPlaceCandidates(String query, List<NaverLocalSearchClient.Place> places,
                                     long requestId) {
        if (isFinishing() || isDestroyed() || requestId != activeSearchId) return;
        ArrayList<Candidate> candidates = new ArrayList<>();
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
        if (candidates.isEmpty()) {
            handleDestinationSearchFailure("그 지역에서 해당 종류의 장소를 찾지 못했어요.");
            return;
        }
        destinationSearchFailureCount = 0;
        confirmationCandidateChoices = null;
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        if (candidates.size() == 1) {
            showCandidate(candidates.get(0));
            return;
        }
        pendingCandidateChoices = candidates;
        voiceCandidateChoices = buildVoiceCandidateChoices(candidates);
        candidateSelectionAttempts = 0;
        askForCandidateByVoice();
    }

    private void handleDestinationSearchFailure(String message) {
        destinationSearchFailureCount++;
        if (specialistConditionForCurrentFlow) {
            awaitingDestinationRetry = true;
            awaitingSpecialistVerification = true;
        }
        if (!activeSearchFromTextEntry && destinationSearchFailureCount < 2) {
            String prompt = message + " 동네와 장소 이름을 다시 말씀해 주세요.";
            showStatus(prompt, false);
            speakThenListen(prompt, SpeechPurpose.DESTINATION);
            return;
        }
        String failurePrompt = activeSearchFromTextEntry
                ? "장소를 찾지 못했어요. 네트워크를 확인하거나 다른 동네를 글자로 입력해 주세요."
                : "지금은 장소를 찾기 어려워요. 네트워크를 확인하거나 다른 동네를 말씀해 주세요. 글자로 입력할 수도 있어요.";
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
        awaitingDestinationRetry = false;
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
        String fallbackAddress = candidateVoiceAddress(candidate);
        String spokenAddress = fallbackAddress.isEmpty() ? candidate.address : fallbackAddress;
        String prompt = candidate.nameIsFallback
                ? specialistNote + "장소 이름은 확인되지 않았어요. 확인된 주소는 "
                        + (fallbackAddress.isEmpty() ? candidate.address : fallbackAddress)
                        + "예요. " + (travelMode == TravelMode.TAXI
                        ? "이 주소가 목적지 맞나요? 맞으면 카카오 T에서 택시를 호출할게요."
                        : "이 주소로 대중교통 길을 안내할까요?")
                : travelMode == TravelMode.TAXI
                        ? specialistNote + candidate.name + " " + candidate.address + "이 목적지 맞나요? 맞으면 카카오 T에서 택시를 호출할게요."
                        : specialistNote + candidate.name + ", " + spokenAddress + " 주소예요. 이 주소로 대중교통 길을 안내할까요?";
        confirmButton.setText(travelMode == TravelMode.TAXI ? "확인 후 택시 호출" : "네, 대중교통 경로 열기");
        showStatus(prompt, false);
        confirmButton.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        String spokenPrefix = travelMode == TravelMode.TAXI && !candidate.nameIsFallback
                ? candidate.name + " " + candidate.address + ". " : "";
        String spokenPrompt = spokenPrefix + prompt + " 맞으면 맞다고 말씀해 주세요.";
        if (shouldUseScreenOnlyInteraction()) speak(spokenPrompt);
        else speakThenListen(spokenPrompt, SpeechPurpose.CONFIRMATION);
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
        if (pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) return;
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
            showCandidateChoiceDialog();
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
        recognizedText.setText("장소 후보를 찾았어요. 음성으로 선택해 주세요.");
        showStatus("여러 장소 중 맞는 곳을 말씀해 주세요.", false);
        speakThenListen(list.toString(), SpeechPurpose.CANDIDATE_SELECTION);
    }

    private void handleCandidateSelection(String utterance) {
        if (pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) {
            showStatus("장소 후보가 없어졌어요. 목적지를 다시 말씀해 주세요.", true);
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
            showCandidateChoiceDialog();
            return;
        }
        if (shouldUseScreenOnlyInteraction()) {
            prepareCandidateChoiceScreen(
                    "화면에서 전체 주소를 확인하려면 ‘후보 선택하기’를 눌러 주세요.", false);
            showCandidateChoiceDialog();
            return;
        }
        if (voiceCandidateChoices == null || voiceCandidateChoices.isEmpty()) {
            prepareCandidateChoiceScreen(
                    "주소로 구별하기 어려워 음성으로 고를 수 없어요. 화면에서 전체 주소를 확인해 주세요.",
                    false);
            showCandidateChoiceDialog();
            return;
        }
        List<Candidate> selectable = voiceCandidateChoices;
        int selected = findCandidateIndex(utterance, selectable);
        if (selected >= 0) {
            showCandidate(selectable.get(selected));
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
            showCandidateChoiceDialog();
        }
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
            showCandidateChoiceDialog();
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
            String name = item.nameIsFallback ? "" : compactForSelection(item.name);
            String label = compactForSelection(voiceCandidateLabel(item));
            String address = compactForSelection(item.address);
            if (!name.isEmpty() && (name.equals(value) || value.length() >= 2 && name.contains(value))
                    || !label.isEmpty() && (label.equals(value) || value.length() >= 2 && label.contains(value))
                    || value.length() >= 2 && address.contains(value)) {
                if (match >= 0) return -1;
                match = i;
            }
        }
        return match;
    }

    private String compactForSelection(String value) {
        return CandidateSelectionParser.normalize(value);
    }

    private void showCandidateChoiceDialog() {
        if (pendingCandidateChoices == null || pendingCandidateChoices.isEmpty()) return;
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
        ArrayList<Candidate> choicesSnapshot = new ArrayList<>(pendingCandidateChoices);
        String[] choices = new String[choicesSnapshot.size()];
        for (int i = 0; i < choicesSnapshot.size(); i++) {
            Candidate item = choicesSnapshot.get(i);
            boolean voiceSelectable = voiceCandidateChoices != null
                    && voiceCandidateChoices.contains(item);
            choices[i] = (i + 1) + ". " + candidateDisplayName(item) + "\n" + item.address
                    + (voiceSelectable ? "" : "\n음성으로 구별하기 어려워 화면에서 선택");
        }
        new AlertDialog.Builder(this)
                .setTitle("맞는 장소를 선택해 주세요")
                .setItems(choices, (dialog, which) -> {
                    if (searchIdAtDisplay != activeSearchId
                            || candidateGenerationAtDisplay != candidateGeneration
                            || pendingCandidateChoices == null
                            || which < 0 || which >= choicesSnapshot.size()) {
                        showStatus("후보 목록이 바뀌었어요. 현재 검색 결과를 확인해 주세요.", true);
                        if (pendingCandidateChoices != null && !pendingCandidateChoices.isEmpty()) {
                            showCandidateChoiceDialog();
                        }
                        return;
                    }
                    showCandidate(choicesSnapshot.get(which));
                })
                .setNegativeButton("처음으로", (dialog, which) ->
                        resetFlow("처음으로 돌아왔어요. 새 목적지를 말씀해 주세요."))
                .show();
    }

    private void handleDestinationConfirmation(String utterance, Candidate candidateAtCapture,
                                               long candidateGenerationAtCapture,
                                               long approvingTurnSequence,
                                               TravelMode modeAtCapture) {
        if (modeAtCapture != travelMode
                || !isCurrentCandidate(candidateAtCapture, candidateGenerationAtCapture)) {
            if (candidateAtCapture == pendingCandidate
                    && candidateGenerationAtCapture == candidateGeneration) {
                clearPendingCandidate();
                confirmButton.setVisibility(View.GONE);
                destinationText.setText("");
                showStatus("목적지 확인 시간이 지났어요. 다시 말씀해 주세요.", true);
            }
            return;
        }
        DestinationConfirmationParser.Decision decision = DestinationConfirmationParser.classify(utterance);
        if (decision == DestinationConfirmationParser.Decision.YES) {
            confirmationListenAttempts = 0;
            openConfirmedRoute(candidateAtCapture, candidateGenerationAtCapture, approvingTurnSequence);
        } else if (decision == DestinationConfirmationParser.Decision.NO) {
            if (switchCandidateFromConfirmation(utterance)) return;
            if (restoreCandidateChoicesAfterRejection()) return;
            clearPendingCandidate();
            pendingCandidateChoices = null;
            voiceCandidateChoices = null;
            awaitingClarificationUtterance = "";
            awaitingSpecialistVerification = false;
            specialistConditionForCurrentFlow = false;
            specialistConditionRequestId = -1L;
            specialistStatusUnverifiedForCandidate = false;
            awaitingDestinationRetry = false;
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
        awaitingDestinationRetry = false;
        if (remainingChoices.size() == 1) {
            showCandidate(remainingChoices.get(0));
        } else {
            askForCandidateByVoice("이 후보가 아니면 다른 후보를 선택해 주세요.");
        }
        return true;
    }

    private boolean switchCandidateFromConfirmation(String utterance) {
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
        if (invalidateCurrentCandidate) {
            candidateGeneration++;
            pendingCandidate = null;
            pendingCandidateExpiresAtElapsedRealtime = 0L;
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
        recognizedText.setText("장소 후보를 찾았어요. 화면 목록에서 선택해 주세요.");
        showStatus(message, false);
        speak(message);
    }

    private void speakThenListen(String message, SpeechPurpose purpose) {
        cancelPendingAutoListen();
        if (shouldUseScreenOnlyInteraction()) {
            speak(message);
            return;
        }
        queuedMicPurpose = purpose;
        queuedMicPreserveClarificationContext = true;
        speak(message);
    }

    private void cancelPendingAutoListen() {
        queuedMicPurpose = null;
        queuedMicPreserveClarificationContext = false;
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
                clearPendingCandidate();
                confirmButton.setVisibility(View.GONE);
                destinationText.setText("");
                showStatus("목적지 확인 시간이 지났어요. 다시 찾아 드릴게요.", true);
            }
            return;
        }
        if (approvingTurnSequence != UI_APPROVAL_TURN_SEQUENCE
                && approvingTurnSequence != speechCaptureSequence) return;
        invalidateSpeechCapture();
        cancelPendingAutoListen();
        if (travelMode == TravelMode.TAXI) {
            if (isKakaoTaxiServiceEnabled()) launchTaxiHandoff(candidate, true);
            else showKakaoTaxiAccessibilityPrompt(candidate);
            return;
        }
        if (!isNaverGuideServiceEnabled()) {
            showNaverAccessibilityPrompt(candidate);
            return;
        }
        launchNaverTransitRoute(candidate);
    }

    private void setPendingCandidate(Candidate candidate) {
        pendingCandidate = candidate;
        long expectedGeneration = ++candidateGeneration;
        pendingCandidateExpiresAtElapsedRealtime = SystemClock.elapsedRealtime()
                + CANDIDATE_APPROVAL_TTL_MS;
        confirmButton.setOnClickListener(v -> openConfirmedRoute(candidate,
                expectedGeneration, UI_APPROVAL_TURN_SEQUENCE));
    }

    private void clearPendingCandidate() {
        candidateGeneration++;
        pendingCandidate = null;
        pendingCandidateExpiresAtElapsedRealtime = 0L;
        confirmationCandidateChoices = null;
    }

    private boolean isCurrentCandidate(Candidate candidate, long expectedGeneration) {
        return candidate != null && pendingCandidate == candidate
                && candidateGeneration == expectedGeneration
                && pendingCandidateExpiresAtElapsedRealtime > SystemClock.elapsedRealtime();
    }

    private void showNaverAccessibilityPrompt(Candidate candidate) {
        new AlertDialog.Builder(this)
                .setTitle("네이버 안내 시작 설정")
                .setMessage("MA가 네이버 지도에서 ‘안내 시작’ 버튼을 한 번 눌러드리려면 Android 접근성 설정에서 ‘MA 네이버 안내 시작’을 켜야 해요. MA는 네이버 지도에서 안내 시작 버튼만 찾고, 화면 내용은 저장하거나 전송하지 않아요.")
                .setPositiveButton("설정 열기", (dialog, which) -> {
                    pendingCandidate = candidate;
                    waitingForGuideAccessibilitySettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        waitingForGuideAccessibilitySettings = false;
                        showStatus("접근성 설정을 열지 못했어요. Android 설정에서 MA 네이버 안내 시작을 켜주세요.", true);
                    }
                })
                .setNegativeButton("이번엔 직접", (dialog, which) -> launchNaverTransitRoute(candidate))
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
            if (guideAutomationEnabled) NaverGuideAutomation.arm(this,
                    candidate.nameIsFallback ? "" : candidate.name, candidate.address);
            else NaverGuideAutomation.disarm(this, "manual_handoff");
            startActivity(route);
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
            boolean specificAddress = DestinationAddressMatcher.hasStreetNumber(candidate.address);
            boolean automationArmed = automateDestinationInput && specificAddress;
            taxiDestinationCopied = copied && specificAddress;
            if (automationArmed) KakaoTaxiAutomation.arm(this,
                    candidate.nameIsFallback ? candidate.address : candidate.name, candidate.address);
            else KakaoTaxiAutomation.finish(this, "manual_handoff");
            startActivity(taxiApp);
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
        cancelPendingAutoListen();
        clearPendingSpecialistConsent();
        clearPendingCandidate();
        pendingCandidateChoices = null;
        voiceCandidateChoices = null;
        candidateSelectionAttempts = 0;
        destinationSpeechRetryCount = 0;
        destinationSearchFailureCount = 0;
        activeSearchFromTextEntry = false;
        awaitingClarificationUtterance = "";
        awaitingSpecialistVerification = false;
        specialistConditionForCurrentFlow = false;
        specialistConditionRequestId = -1L;
        specialistStatusUnverifiedForCandidate = false;
        awaitingDestinationRetry = false;
        destinationClarificationAttempts = 0;
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
            awaitingDestinationRetry = true;
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
        if (ttsReady && tts != null) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ma-" + System.currentTimeMillis());
        }
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
        automatic.setText("MA 업데이트는 공개 배포 채널에서 자동으로 받아요.\n별도 계정이나 인증 정보를 입력할 필요가 없어요.");
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
                + "\nAutoSync 토큰과 Gemini·NAVER API HUB 인증정보는 Android Keystore로 암호화됩니다."
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
        EditText geminiKey = field("Gemini API 키 (직접 연결, 선택)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        geminiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(geminiKey);
        CheckBox clearGeminiKey = new CheckBox(this);
        clearGeminiKey.setText("저장된 Gemini API 키 삭제");
        form.addView(clearGeminiKey);
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
        TextView aiHelp = new TextView(this);
        aiHelp.setText("Gemini는 말뜻을 해석하고 NAVER API HUB는 네이버 지도에 있는 업체 이름·주소·좌표를 검색합니다. 키가 없거나 검색이 실패하면 목적지를 추측하지 않습니다.");
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
                settings.saveGithubUpdateConfig(githubRepository.getText().toString(),
                        githubToken.getText().toString(), clearGithubToken.isChecked());
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
}
