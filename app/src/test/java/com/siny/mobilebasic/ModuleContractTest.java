package com.siny.mobilebasic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import com.siny.mobilebasic.modules.AutoSyncModule;
import com.siny.mobilebasic.modules.AutoUpdateModule;

import org.junit.Test;

public final class ModuleContractTest {
    @Test public void autoUpdateAcceptsHttpsAndDebugLoopbackOnly() {
        assertTrue(AutoUpdateModule.isSecureUrl("https://updates.example.test/manifest.json", false));
        assertTrue(AutoUpdateModule.isSecureUrl("http://127.0.0.1:18765/manifest.json", true));
        assertTrue(AutoUpdateModule.isSecureUrl("http://10.0.2.2:18765/manifest.json", true));
        assertFalse(AutoUpdateModule.isSecureUrl("http://updates.example.test/manifest.json", false));
        assertFalse(AutoUpdateModule.isSecureUrl("http://10.0.2.2:18765/manifest.json", false));
    }

    @Test public void autoUpdateRejectsEmbeddedCredentialsAndFragments() {
        assertFalse(AutoUpdateModule.isSecureUrl("https://user:secret@updates.example.test/feed", false));
        assertFalse(AutoUpdateModule.isSecureUrl("https://updates.example.test/feed#fragment", false));
    }

    @Test public void autoUpdateFollowsSecureReleaseAssetRedirectsOnly() {
        assertTrue(AutoUpdateModule.isAllowedRedirect("https://github.com/siny-dev/app/releases/download/v1/app.apk",
                "https://release-assets.githubusercontent.com/signed/app.apk", false));
        assertFalse(AutoUpdateModule.isAllowedRedirect("https://github.com/siny-dev/app/releases/download/v1/app.apk",
                "http://release-assets.githubusercontent.com/app.apk", false));
        assertFalse(AutoUpdateModule.isAllowedRedirect("https://github.com/siny-dev/app/releases/download/v1/app.apk",
                "https://user:pass@release-assets.githubusercontent.com/app.apk", false));
        assertFalse(AutoUpdateModule.isAllowedRedirect("http://updates.example.test/manifest.json",
                "https://updates.example.test/manifest.json", false));
    }

    @Test public void autoUpdateExplainsIntegrityFailuresSeparately() {
        assertTrue(AutoUpdateModule.safeMessage(new SecurityException("Update package SHA-256 does not match the manifest")).contains("SHA-256"));
        assertTrue(AutoUpdateModule.safeMessage(new SecurityException("APK package name does not match this app")).contains("패키지명"));
        assertTrue(AutoUpdateModule.safeMessage(new SecurityException("APK signing certificate does not match the installed app")).contains("서명 인증서"));
    }

    @Test public void autoSyncAcceptsHttpsAndDebugLoopbackOnly() {
        AutoSyncModule.validateEndpoint("https://sync.example.test", false);
        AutoSyncModule.validateEndpoint("http://127.0.0.1:18765", true);
        AutoSyncModule.validateEndpoint("http://10.0.2.2:18765", true);
        assertThrows(IllegalArgumentException.class, () -> AutoSyncModule.validateEndpoint("http://sync.example.test", false));
        assertThrows(IllegalArgumentException.class, () -> AutoSyncModule.validateEndpoint("http://10.0.2.2:18765", false));
    }

    @Test public void autoSyncRejectsPathsQueriesFragmentsAndCredentials() {
        assertThrows(IllegalArgumentException.class, () -> AutoSyncModule.validateEndpoint("https://sync.example.test/v1", false));
        assertThrows(IllegalArgumentException.class, () -> AutoSyncModule.validateEndpoint("https://sync.example.test?token=x", false));
        assertThrows(IllegalArgumentException.class, () -> AutoSyncModule.validateEndpoint("https://user:secret@sync.example.test", false));
    }
}
