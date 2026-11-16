package com.siny.mobilebasic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/** Per-app identity shared by AutoSync and AutoUpdate. */
public final class AppIdentity {
    public static final String APP_NAME = "MA";
    public static final String APP_ID = "ma-3c44c3e1b8";
    public static final int AUTOUPDATE_API_MAJOR = 1;
    public static final int AUTOSYNC_API_MAJOR = 1;

    private AppIdentity() { }

    public static String slugForName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Program name is required");
        String normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String base = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "app";
        if (base.length() > 50) base = base.substring(0, 50).replaceAll("-+$", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < 5; i++) suffix.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return base + "-" + suffix;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String applicationIdForName(String name) {
        return "com.siny.apps." + slugForName(name).replace('-', '_');
    }

    public static boolean isValidAppId(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{0,62}");
    }
}
