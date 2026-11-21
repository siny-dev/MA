package com.siny.mobilebasic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A feature-free app shell with the shared update and sync modules wired in. */
public final class MainActivity extends Activity {
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
    private Button updateButton;
    private String moduleFailure = "";

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
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(updateTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(0xfff7f8fa);

        TextView title = new TextView(this);
        title.setText(AppIdentity.APP_NAME);
        title.setTextSize(26);
        title.setTextColor(0xff17202a);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("AutoUpdate · AutoSync 기본 셸");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xff586575);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        root.addView(subtitle, subtitleParams);

        appVersionStatus = new TextView(this);
        appVersionStatus.setText(installedVersionText());
        appVersionStatus.setTextSize(14);
        appVersionStatus.setTextColor(0xff364152);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
        versionParams.topMargin = dp(20);
        root.addView(appVersionStatus, versionParams);

        syncStatus = statusText();
        updateStatus = statusText();
        addStatusBlock("AutoSync", syncStatus);
        addStatusBlock("AutoUpdate", updateStatus);

        Button checkButton = actionButton("지금 업데이트 확인");
        checkButton.setOnClickListener(v -> runUpdateCheck());
        root.addView(checkButton, actionParams());

        updateButton = actionButton("업데이트 설치");
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener(v -> applyUpdate());
        root.addView(updateButton, actionParams());

        Button syncButton = actionButton("동기화 확인");
        syncButton.setOnClickListener(v -> runSync());
        root.addView(syncButton, actionParams());

        Button settingsButton = actionButton("모듈 설정");
        settingsButton.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams settingsParams = actionParams();
        settingsParams.topMargin = dp(20);
        root.addView(settingsButton, settingsParams);

        TextView identity = new TextView(this);
        identity.setText("앱 ID: " + AppIdentity.APP_ID);
        identity.setTextSize(12);
        identity.setTextColor(0xff687586);
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(-1, -2);
        identityParams.topMargin = dp(18);
        root.addView(identity, identityParams);

        scroll.addView(root);
        setContentView(scroll);
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
                        String candidate = completed.versionCode > 0
                                ? " · 새 버전 " + (completed.versionName.isEmpty() ? "확인됨" : completed.versionName)
                                  + " (" + completed.versionCode + ")" : "";
                        showUpdate("현재 " + installedVersionText() + candidate + " · " + completed.message);
                        updateButton.setVisibility("update-ready".equals(completed.status) ? View.VISIBLE : View.GONE);
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

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView explanation = new TextView(this);
        explanation.setText("서버 주소가 비어 있으면 앱 자료는 이 기기에만 저장됩니다. 다른 기기와 동기화하려면 AutoSync 서버 주소와 발급된 앱 토큰이 필요합니다.");
        form.addView(explanation);

        EditText server = field("AutoSync 서버 주소 (HTTPS)", settings.serverUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(server);
        EditText token = field("AutoSync 앱 토큰", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(token);
        CheckBox clearToken = new CheckBox(this);
        clearToken.setText("저장된 AutoSync 토큰 삭제");
        form.addView(clearToken);
        EditText update = field("앱별 AutoUpdate manifest URL (HTTPS)", settings.updateManifestUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(update);

        new AlertDialog.Builder(this).setTitle("모듈 설정").setView(form).setPositiveButton("저장", (dialog, which) -> {
            try {
                String nextToken = clearToken.isChecked() ? "" : token.getText().toString();
                if (!clearToken.isChecked() && nextToken.isEmpty()) nextToken = settings.autosyncToken();
                settings.save(server.getText().toString(), nextToken, update.getText().toString());
                MobileJobs.schedule(this);
                showLocalSyncState();
                runSync();
                runUpdateCheck();
            } catch (Exception e) {
                Toast.makeText(this, "설정을 저장하지 못했습니다. 입력값을 확인하세요.", Toast.LENGTH_LONG).show();
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
    }

    private void showUpdate(String text) {
        if (updateStatus != null) updateStatus.setText(text);
    }

    private String installedVersionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return (info.versionName == null ? "unknown" : info.versionName) + " (versionCode " + code + ")";
        } catch (Exception e) {
            return "설치 버전을 확인할 수 없습니다.";
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
