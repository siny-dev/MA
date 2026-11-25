package com.siny.mobilebasic.modules;

import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Bundle;

import com.siny.mobilebasic.AppIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Exercises the real updater download/hash/signature checks against a disposable local feed. */
@RunWith(AndroidJUnit4.class)
public final class AutoUpdateLocalFeedTest {
    private Context context;
    private SecureSettings settings;
    private String previousServer;
    private String previousToken;
    private String previousUpdateUrl;
    private AutoUpdateModule updater;
    private boolean ownsTestPendingUpdate;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        settings = new SecureSettings(context, AppIdentity.APP_ID);
        previousServer = settings.serverUrl();
        previousToken = settings.autosyncToken();
        previousUpdateUrl = settings.updateManifestUrl();
        updater = new AutoUpdateModule(context, settings);
    }

    @After public void restoreSettingsAndRemoveTestDownload() {
        if (ownsTestPendingUpdate) updater.clearPendingUpdateAfterSuccess();
        settings.save(previousServer, previousToken, previousUpdateUrl);
    }

    @Test public void downloadedPackageMustMatchFeedVersionHashPackageAndExistingSigner() {
        // Never replace or remove an update that was already waiting for the user to install.
        assumeTrue("Refusing to replace a pending real update.", settings.pendingUpdatePath().isEmpty());
        Bundle arguments = InstrumentationRegistry.getArguments();
        String manifestUrl = arguments.getString("localUpdateManifestUrl", "");
        String expectedCode = arguments.getString("localUpdateVersionCode", "1019");
        assertTrue("The local test feed URL was not supplied.", manifestUrl.startsWith("http://127.0.0.1:"));
        settings.save(previousServer, previousToken, manifestUrl);

        AutoUpdateModule.Result result = updater.checkAndDownload();
        ownsTestPendingUpdate = !settings.pendingUpdatePath().isEmpty();
        assertEquals("Update download failed: " + result.message, "update-ready", result.status);
        assertEquals(Long.parseLong(expectedCode), result.versionCode);
        assertEquals("1.0.033", result.versionName);
        assertTrue("The verified update package was not staged.", !settings.pendingUpdatePath().isEmpty());
    }

    @Test public void appCanRequestSelfUpdateWithoutInstallerConfirmation() {
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION));
    }

    @Test public void mismatchedShaIsRejectedWithoutStagingAnUpdate() {
        assumeTrue("Refusing to replace a pending real update.", settings.pendingUpdatePath().isEmpty());
        Bundle arguments = InstrumentationRegistry.getArguments();
        String manifestUrl = arguments.getString("localUpdateManifestUrl", "");
        assertTrue("The local test feed URL was not supplied.", manifestUrl.startsWith("http://127.0.0.1:"));
        String badManifestUrl = manifestUrl.replace("/manifest", "/bad-manifest");
        settings.save(previousServer, previousToken, badManifestUrl);

        AutoUpdateModule.Result result = updater.checkAndDownload();
        assertEquals("The bad-SHA feed was not rejected.", "error", result.status);
        assertEquals("내려받은 APK의 SHA-256이 업데이트 manifest와 다릅니다.", result.message);
        assertTrue("An invalid package must not be staged.", settings.pendingUpdatePath().isEmpty());
    }
}
