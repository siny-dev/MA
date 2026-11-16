package com.siny.mobilebasic.modules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/** Selects MA's private-release channel without disturbing other apps in the same repository. */
final class GitHubReleaseResolver {
    private GitHubReleaseResolver() { }

    static Candidate newestForApp(String payload, String appId) throws JSONException {
        if (payload == null || payload.length() > 4 * 1024 * 1024) {
            throw new JSONException("GitHub releases response is missing or too large.");
        }
        JSONArray releases = new JSONArray(payload);
        Pattern tagPattern = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
        Candidate newest = null;
        long[] newestVersion = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", true)
                    || release.optBoolean("prerelease", true)) continue;
            String tag = release.optString("tag_name", "");
            java.util.regex.Matcher tagMatcher = tagPattern.matcher(tag);
            if (!tagMatcher.matches()) continue;
            long[] version = parseVersion(tagMatcher);
            if (version == null) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            long manifestId = uniqueAssetId(assets, "MA-" + appId + "-manifest.json");
            if (manifestId <= 0 || (newestVersion != null && compare(version, newestVersion) <= 0)) continue;
            newest = new Candidate(tag, assets, manifestId);
            newestVersion = version;
        }
        return newest;
    }

    private static long[] parseVersion(java.util.regex.Matcher matcher) {
        try {
            long major = Long.parseLong(matcher.group(1));
            long minor = Long.parseLong(matcher.group(2));
            long patch = Long.parseLong(matcher.group(3));
            return new long[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compare(long[] left, long[] right) {
        for (int i = 0; i < 3; i++) {
            int result = Long.compare(left[i], right[i]);
            if (result != 0) return result;
        }
        return 0;
    }

    private static long uniqueAssetId(JSONArray assets, String name) {
        long found = -1;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name", ""))) continue;
            if (found > 0) return -1;
            found = asset.optLong("id", -1);
        }
        return found;
    }

    static final class Candidate {
        final String tag;
        final long manifestAssetId;
        private final JSONArray assets;

        Candidate(String tag, JSONArray assets, long manifestAssetId) {
            this.tag = tag;
            this.assets = assets;
            this.manifestAssetId = manifestAssetId;
        }

        long assetId(String name) {
            return uniqueAssetId(assets, name);
        }

        long assetSize(String name) {
            long found = -1;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset != null && name.equals(asset.optString("name", ""))) {
                    if (found > 0) return -1;
                    found = asset.optLong("size", -1);
                }
            }
            return found;
        }
    }
}
