package com.siny.mobilebasic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** One-shot, user-confirmed handoff state for the NAVER Map guide-start helper. */
final class NaverGuideAutomation {
    static final String TARGET_PACKAGE = "com.nhn.android.nmap";
    private static final String PREFS = "ma_naver_guide_automation";
    private static final String ARMED = "armed";
    private static final String EXPIRES_AT_ELAPSED = "expires_at_elapsed";
    private static final String LEGACY_EXPIRES_AT = "expires_at";
    private static final String BOOT_COUNT = "boot_count";
    private static final String RESULT = "result";
    private static final String RESULT_AT = "result_at";
    private static final String DESTINATION_NAME = "destination_name";
    private static final String DESTINATION_ADDRESS = "destination_address";
    private static final String ARM_GENERATION = "arm_generation";

    private NaverGuideAutomation() {}

    static void arm(Context context, String destinationName, String destinationAddress) {
        SharedPreferences prefs = preferences(context);
        long bootCount = currentBootCount(context);
        SharedPreferences.Editor editor = prefs.edit()
                .putLong(ARM_GENERATION, prefs.getLong(ARM_GENERATION, 0L) + 1L)
                .putBoolean(ARMED, bootCount >= 0L)
                .putLong(BOOT_COUNT, bootCount)
                .putString(RESULT, bootCount >= 0L ? "waiting" : "arm_unavailable")
                .putString(DESTINATION_NAME, destinationName == null ? "" : destinationName.trim())
                .putString(DESTINATION_ADDRESS, destinationAddress == null ? "" : destinationAddress.trim())
                .remove(RESULT_AT)
                .remove(LEGACY_EXPIRES_AT);
        if (bootCount >= 0L) {
            editor.putLong(EXPIRES_AT_ELAPSED,
                    SystemClock.elapsedRealtime() + NaverGuideArmLease.ARM_WINDOW_MS);
        } else {
            editor.remove(EXPIRES_AT_ELAPSED);
        }
        editor.apply();
    }

    static String destinationName(Context context) {
        return preferences(context).getString(DESTINATION_NAME, "");
    }

    static String destinationAddress(Context context) {
        return preferences(context).getString(DESTINATION_ADDRESS, "");
    }

    static long armGeneration(Context context) {
        return preferences(context).getLong(ARM_GENERATION, 0L);
    }

    static boolean isArmed(Context context) {
        return hasMinimumRemaining(context, 1L);
    }

    static boolean hasMinimumRemaining(Context context, long minimumRemainingMs) {
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean(ARMED, false)) return false;
        long expectedBootCount = prefs.getLong(BOOT_COUNT, -1L);
        long actualBootCount = currentBootCount(context);
        long expiresAt = prefs.getLong(EXPIRES_AT_ELAPSED, 0L);
        if (NaverGuideArmLease.isUsable(expectedBootCount, actualBootCount, expiresAt,
                SystemClock.elapsedRealtime(), minimumRemainingMs)) return true;
        disarm(context, "expired");
        return false;
    }

    static void disarm(Context context, String result) {
        preferences(context).edit()
                .putBoolean(ARMED, false)
                .putString(RESULT, result)
                .putLong(RESULT_AT, System.currentTimeMillis())
                .remove(EXPIRES_AT_ELAPSED)
                .remove(BOOT_COUNT)
                .remove(LEGACY_EXPIRES_AT)
                .apply();
    }

    static String result(Context context) {
        return preferences(context).getString(RESULT, "idle");
    }

    static void clearResult(Context context) {
        preferences(context).edit().putString(RESULT, "idle").apply();
    }

    private static long currentBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
