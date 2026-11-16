package com.siny.mobilebasic;

import android.content.Context;
import android.content.SharedPreferences;

/** Holds a short-lived, one-use destination and its already confirmed taxi request. */
final class KakaoTaxiAutomation {
    static final String TARGET_PACKAGE = "com.kakao.taxi";
    private static final String PREFS = "ma_kakao_taxi_destination";
    private static final long ARM_WINDOW_MS = 120_000L;
    private static final long CALL_WINDOW_MS = 45_000L;
    private static final String ARMED = "armed";
    private static final String EXPIRES_AT = "expires_at";
    private static final String NAME = "name";
    private static final String ADDRESS = "address";
    private static final String RESULT = "result";
    private static final String STAGE = "stage";
    static final String STAGE_ENTER_DESTINATION = "enter_destination";
    static final String STAGE_SELECT_RESULT = "select_result";
    static final String STAGE_REQUEST_TAXI = "request_taxi";

    private KakaoTaxiAutomation() {}

    static void arm(Context context, String name, String address) {
        preferences(context).edit()
                .putBoolean(ARMED, true)
                .putLong(EXPIRES_AT, System.currentTimeMillis() + ARM_WINDOW_MS)
                .putString(NAME, bounded(name, 120))
                .putString(ADDRESS, bounded(address, 240))
                .putString(STAGE, STAGE_ENTER_DESTINATION)
                .putString(RESULT, "waiting")
                .apply();
    }

    static boolean isArmed(Context context) {
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean(ARMED, false)) return false;
        if (System.currentTimeMillis() <= prefs.getLong(EXPIRES_AT, 0L)) return true;
        finish(context, "expired");
        return false;
    }

    static String name(Context context) { return preferences(context).getString(NAME, ""); }
    static String address(Context context) { return preferences(context).getString(ADDRESS, ""); }
    static String stage(Context context) { return preferences(context).getString(STAGE, STAGE_ENTER_DESTINATION); }
    static String result(Context context) { return preferences(context).getString(RESULT, "idle"); }

    static void markTextEntered(Context context) {
        preferences(context).edit()
                .putString(STAGE, STAGE_SELECT_RESULT)
                .putString(RESULT, "destination_entered")
                .apply();
    }

    static void markDestinationSelected(Context context) {
        preferences(context).edit()
                .putString(STAGE, STAGE_REQUEST_TAXI)
                .putLong(EXPIRES_AT, System.currentTimeMillis() + CALL_WINDOW_MS)
                .putString(RESULT, "destination_match_tapped")
                .apply();
    }

    static void finish(Context context, String result) {
        preferences(context).edit()
                .putBoolean(ARMED, false)
                .putString(RESULT, result)
                .remove(NAME)
                .remove(ADDRESS)
                .remove(STAGE)
                .remove(EXPIRES_AT)
                .apply();
    }

    static void clearResult(Context context) {
        preferences(context).edit().putString(RESULT, "idle").apply();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
