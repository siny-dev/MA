package com.siny.mobilebasic.assistant;

import android.net.Uri;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds the official NAVER Maps public-transit route handoff from a confirmed coordinate. */
public final class NaverMapLink {
    private NaverMapLink() { }

    public static Uri publicTransit(PlaceCandidate destination) {
        return Uri.parse(publicTransitUri(destination));
    }

    public static String publicTransitUri(PlaceCandidate destination) {
        if (destination == null) throw new IllegalArgumentException("Confirmed destination required");
        return "nmap://route/public?dlat=" + destination.latitude
                + "&dlng=" + destination.longitude
                + "&dname=" + encode(destination.name)
                + "&appname=com.siny.apps.ma_3c44c3e1b8";
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
}
