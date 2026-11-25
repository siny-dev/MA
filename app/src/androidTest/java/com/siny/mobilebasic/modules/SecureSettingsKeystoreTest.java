package com.siny.mobilebasic.modules;

import android.content.Context;
import android.content.SharedPreferences;

import com.siny.mobilebasic.AppIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Verifies API credentials survive recreation without being stored as plaintext preferences. */
@RunWith(AndroidJUnit4.class)
public final class SecureSettingsKeystoreTest {
    private Context context;
    private SecureSettings settings;
    private String previousServer;
    private String previousToken;
    private String previousUpdateUrl;
    private String previousClovaKey;
    private String previousNaverKeyId;
    private String previousNaverKey;
    private boolean ownsTestPendingUpdate;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        settings = new SecureSettings(context, AppIdentity.APP_ID);
        previousServer = settings.serverUrl();
        previousToken = settings.autosyncToken();
        previousUpdateUrl = settings.updateManifestUrl();
        previousClovaKey = settings.clovaApiKey();
        previousNaverKeyId = settings.naverApiKeyId();
        previousNaverKey = settings.naverApiKey();
    }

    @After public void restoreSettings() {
        if (ownsTestPendingUpdate) settings.clearPendingUpdate();
        settings.save(previousServer, previousToken, previousUpdateUrl);
        settings.saveAssistant(previousClovaKey, previousNaverKeyId, previousNaverKey);
    }

    @Test public void assistantCredentialsSurviveSettingsRecreationAndAreNotPlaintext() {
        String testClova = "clova-test-secret-7a9f";
        String testNaverId = "naver-test-id-4821";
        String testNaverKey = "naver-test-secret-b615";
        settings.saveAssistant(testClova, testNaverId, testNaverKey);

        SecureSettings reopened = new SecureSettings(context, AppIdentity.APP_ID);
        assertEquals(testClova, reopened.clovaApiKey());
        assertEquals(testNaverId, reopened.naverApiKeyId());
        assertEquals(testNaverKey, reopened.naverApiKey());

        SharedPreferences raw = context.getSharedPreferences("mobile_basic_settings", Context.MODE_PRIVATE);
        String stored = raw.getAll().toString();
        assertFalse(stored.contains(testClova));
        assertFalse(stored.contains(testNaverId));
        assertFalse(stored.contains(testNaverKey));
    }

    @Test public void existingAutoSyncAndUpdateSettingsStillRoundTrip() {
        String server = "https://sync.example.test";
        String token = "autosync-test-credential-12345678901234567890123456789012";
        String feed = "https://updates.example.test/ma/stable.json";
        settings.save(server, token, feed);

        SecureSettings reopened = new SecureSettings(context, AppIdentity.APP_ID);
        assertEquals(server, reopened.serverUrl());
        assertEquals(token, reopened.autosyncToken());
        assertEquals(feed, reopened.updateManifestUrl());
    }

    @Test public void pendingUpdatePermissionStateSurvivesRecreationAndCanResumeOnceGranted() {
        assumeTrue("Refusing to replace a pending real update.", settings.pendingUpdatePath().isEmpty());
        ownsTestPendingUpdate = true;
        settings.setPendingUpdate(context.getNoBackupFilesDir() + "/test-pending.apk", 12345L);
        settings.markPendingUpdateAwaitingPermission();

        SecureSettings reopened = new SecureSettings(context, AppIdentity.APP_ID);
        assertTrue(reopened.pendingUpdateInstallAttempted());
        assertTrue(reopened.pendingUpdateAwaitingPermission());

        reopened.resetPendingUpdateInstallAttempted();
        assertFalse(reopened.pendingUpdateInstallAttempted());
        assertFalse(reopened.pendingUpdateAwaitingPermission());
    }
}
