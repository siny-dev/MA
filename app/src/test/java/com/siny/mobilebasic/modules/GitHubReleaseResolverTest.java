package com.siny.mobilebasic.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class GitHubReleaseResolverTest {
    @Test public void selectsOnlyTheNewestPublishedReleaseForThisMaApp() throws Exception {
        String payload = "["
                + release("v9.9.9", false, false, asset(1, "MA-other-app-manifest.json", 1)) + ","
                + release("v1.0.005", false, false, asset(2, "latest.json", 12)) + ","
                + release("v1.0.007", true, false, asset(3, "MA-ma-3c44c3e1b8-manifest.json", 12)) + ","
                + release("v1.0.006", false, false,
                        asset(6, "MA-ma-3c44c3e1b8-manifest.json", 128),
                        asset(7, "MA-ma-3c44c3e1b8-1.0.006.apk", 4096)) + ","
                + release("v1.0.003", false, false,
                        asset(4, "MA-ma-3c44c3e1b8-manifest.json", 128),
                        asset(5, "MA-ma-3c44c3e1b8-1.0.3.apk", 2048)) + "]";
        GitHubReleaseResolver.Candidate candidate =
                GitHubReleaseResolver.newestForApp(payload, "ma-3c44c3e1b8");
        assertNotNull(candidate);
        assertEquals("v1.0.006", candidate.tag);
        assertEquals(6, candidate.manifestAssetId);
        assertEquals(7, candidate.assetId("MA-ma-3c44c3e1b8-1.0.006.apk"));
        assertEquals(4096, candidate.assetSize("MA-ma-3c44c3e1b8-1.0.006.apk"));
    }

    @Test public void ignoresUnreleasedAndMalformedTags() throws Exception {
        String payload = "["
                + release("v1.0.006-preview", false, false,
                        asset(1, "MA-ma-3c44c3e1b8-manifest.json", 10)) + ","
                + release("v1.0.007", false, true,
                        asset(2, "MA-ma-3c44c3e1b8-manifest.json", 10)) + "]";
        assertNull(GitHubReleaseResolver.newestForApp(payload, "ma-3c44c3e1b8"));
    }

    @Test public void duplicateManifestAssetsDoNotCreateAnAmbiguousUpdate() throws Exception {
        String payload = "[" + release("v1.0.006", false, false,
                asset(1, "MA-ma-3c44c3e1b8-manifest.json", 10),
                asset(2, "MA-ma-3c44c3e1b8-manifest.json", 10)) + "]";
        assertNull(GitHubReleaseResolver.newestForApp(payload, "ma-3c44c3e1b8"));
    }

    private static String release(String tag, boolean draft, boolean prerelease, String... assets) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft
                + ",\"prerelease\":" + prerelease + ",\"assets\":["
                + String.join(",", assets) + "]}";
    }

    private static String asset(long id, String name, long size) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"size\":" + size + "}";
    }
}
