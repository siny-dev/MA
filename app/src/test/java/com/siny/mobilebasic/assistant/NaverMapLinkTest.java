package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NaverMapLinkTest {
    @Test public void publicTransitUriContainsVerifiedDestinationCoordinatesAndAppId() {
        PlaceCandidate destination = new PlaceCandidate("서동이종호 피부과", "병원 > 피부과",
                "부산 금정구 서동 123", "부산 금정구 서동로 10", 35.2134, 129.1056);
        String uri = NaverMapLink.publicTransitUri(destination);
        assertTrue(uri.startsWith("nmap://route/public?"));
        assertTrue(uri.contains("dlat=35.2134"));
        assertTrue(uri.contains("dlng=129.1056"));
        assertTrue(uri.contains("dname=%EC%84%9C%EB%8F%99%EC%9D%B4%EC%A2%85%ED%98%B8%20%ED%94%BC%EB%B6%80%EA%B3%BC"));
        assertTrue(uri.contains("appname=com.siny.apps.ma_3c44c3e1b8"));
    }

    @Test public void transitHandoffCannotBeBuiltWithoutASelectedPlace() {
        assertTrue(throwsForNull());
    }

    private boolean throwsForNull() {
        try { NaverMapLink.publicTransitUri(null); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
}
