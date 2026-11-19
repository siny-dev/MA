package com.siny.mobilebasic.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoUpdateSourcePolicyTest {
    @Test public void configuredGithubReleaseTakesPrecedenceOverStaleLocalManifest() {
        assertEquals(AutoUpdateModule.UpdateSource.GITHUB_RELEASE,
                AutoUpdateModule.chooseSource(" http://192.168.50.109:18766/ma/manifest.json ",
                        "siny-dev/MA"));
    }

    @Test public void githubReleaseWorksWithoutLocalManifest() {
        assertEquals(AutoUpdateModule.UpdateSource.GITHUB_RELEASE,
                AutoUpdateModule.chooseSource("  ", "siny-dev/MA"));
    }

    @Test public void localManifestIsUsedWhenGithubIsNotConfigured() {
        assertEquals(AutoUpdateModule.UpdateSource.LOCAL_MANIFEST,
                AutoUpdateModule.chooseSource(" http://192.168.50.109:18766/ma/manifest.json ", "  "));
    }

    @Test public void missingManifestAndRepositoryAreNotConfigured() {
        assertEquals(AutoUpdateModule.UpdateSource.NONE,
                AutoUpdateModule.chooseSource("", " "));
    }

    @Test public void buildConfiguredPublicRepositoryOverridesStaleSavedRepository() {
        assertEquals("siny-dev/MA", AutoUpdateModule.effectiveRepository(
                "siny-dev/ma-updates", "siny-dev/MA"));
    }

    @Test public void savedRepositoryIsUsedWhenBuildHasNoDefault() {
        assertEquals("owner/private-feed", AutoUpdateModule.effectiveRepository(
                " owner/private-feed ", " "));
    }

    @Test public void publicRepositoryIgnoresPreviouslySavedToken() {
        assertEquals("", AutoUpdateModule.effectiveGithubToken(true, "stale-token"));
    }

    @Test public void privateRepositoryKeepsItsSavedToken() {
        assertEquals("saved-token", AutoUpdateModule.effectiveGithubToken(false, " saved-token "));
    }

    @Test public void publicRepositoryNeverAsksForTokenEvenWhenOldTokenWasSaved() {
        assertEquals(false, AutoUpdateModule.needsGithubToken(true, "", "GitHub 읽기 토큰이 올바르지 않습니다."));
        assertEquals(false, AutoUpdateModule.needsGithubToken(true, "old-token", "GitHub 공개 요청이 제한되었습니다."));
    }

    @Test public void privateRepositoryRequestsMissingOrRejectedToken() {
        assertEquals(true, AutoUpdateModule.needsGithubToken(false, "", "HTTP 404"));
        assertEquals(true, AutoUpdateModule.needsGithubToken(false, "bad-token", "GitHub 읽기 토큰이 올바르지 않습니다."));
        assertEquals(false, AutoUpdateModule.needsGithubToken(false, "valid-token", "HTTP 500"));
    }

    @Test public void anonymousPublicErrorsDoNotBlameToken() {
        assertEquals("GitHub 공개 업데이트 요청이 제한되었거나 호출 한도에 도달했습니다. (HTTP 403)",
                AutoUpdateModule.githubAccessFailureMessage(403, false, false));
        assertEquals("GitHub 공개 업데이트 파일을 찾을 수 없습니다. (HTTP 404)",
                AutoUpdateModule.githubAccessFailureMessage(404, false, true));
    }

    @Test public void verificationFailureKeepsDetectedReleaseVersion() {
        assertEquals("설치 버전 "+com.siny.mobilebasic.BuildConfig.VERSION_NAME+" ("
                        +com.siny.mobilebasic.BuildConfig.VERSION_CODE+
                        ") → 확인한 업데이트 1.0.16 (15) · 다운로드한 APK의 서명이 현재 설치된 MA와 다릅니다.",
                AutoUpdateModule.versionedFailure(
                        "다운로드한 APK의 서명이 현재 설치된 MA와 다릅니다.", "1.0.16", 15));
    }

    @Test public void failuresBeforeReadingTheReleaseStillShowTheInstalledVersion() {
        assertEquals("설치 버전 "+com.siny.mobilebasic.BuildConfig.VERSION_NAME+" ("
                        +com.siny.mobilebasic.BuildConfig.VERSION_CODE+") · GitHub 연결 실패",
                AutoUpdateModule.versionedFailure("GitHub 연결 실패", "", 0));
    }

    @Test public void fallbackDownloadUsesTheExactValidatedPublicReleaseAsset() {
        assertEquals("https://github.com/siny-dev/MA/releases/download/v1.0.17/MA-ma-3c44c3e1b8-1.0.17.apk",
                AutoUpdateModule.githubReleaseAssetUrl("siny-dev/MA", "v1.0.17",
                        "MA-ma-3c44c3e1b8-1.0.17.apk", "ma-3c44c3e1b8"));
    }

    @Test public void fallbackDownloadRejectsPathInjectionAndMismatchedAssets() {
        assertRejectedReleaseUrl("siny-dev/MA/../../evil", "v1.0.17",
                "MA-ma-3c44c3e1b8-1.0.17.apk");
        assertRejectedReleaseUrl("siny-dev/MA", "v1.0.17/evil",
                "MA-ma-3c44c3e1b8-1.0.17.apk");
        assertRejectedReleaseUrl("siny-dev/MA", "v1.0.17",
                "../../evil.apk");
        assertRejectedReleaseUrl("siny-dev/MA", "v1.0.17",
                "MA-ma-3c44c3e1b8-1.0.16.apk");
    }

    @Test public void assetRedirectAllowsOnlyHttpsGithubOwnedHosts() {
        assertEquals(true, AutoUpdateModule.isTrustedGithubAssetUrl(
                "https://release-assets.githubusercontent.com/path?signature=opaque"));
        assertEquals(true, AutoUpdateModule.isTrustedGithubAssetUrl(
                "https://github-production-release-asset-1.s3.amazonaws.com/path?signature=opaque"));
        assertEquals(false, AutoUpdateModule.isTrustedGithubAssetUrl(
                "https://githubusercontent.com.evil.example/path"));
        assertEquals(false, AutoUpdateModule.isTrustedGithubAssetUrl(
                "https://user@release-assets.githubusercontent.com/path"));
        assertEquals(false, AutoUpdateModule.isTrustedGithubAssetUrl(
                "http://release-assets.githubusercontent.com/path"));
    }

    @Test public void exactSignerComparisonAcceptsSameCertificateRegardlessOfOrder() {
        byte[] signerA = new byte[]{1, 2, 3};
        byte[] signerB = new byte[]{4, 5, 6};
        assertEquals(true, AutoUpdateModule.sameSignerBytes(
                new byte[][]{signerA, signerB}, new byte[][]{signerB.clone(), signerA.clone()}));
    }

    @Test public void exactSignerComparisonRejectsDifferentOrMissingCertificates() {
        assertEquals(false, AutoUpdateModule.sameSignerBytes(
                new byte[][]{{1, 2, 3}}, new byte[][]{{1, 2, 4}}));
        assertEquals(false, AutoUpdateModule.sameSignerBytes(new byte[0][], new byte[0][]));
        assertEquals(false, AutoUpdateModule.sameSignerBytes(null, new byte[][]{{1}}));
    }

    private static void assertRejectedReleaseUrl(String repository,String tag,String assetName) {
        try {
            AutoUpdateModule.githubReleaseAssetUrl(repository,tag,assetName,"ma-3c44c3e1b8");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid release URL to be rejected");
    }
}
