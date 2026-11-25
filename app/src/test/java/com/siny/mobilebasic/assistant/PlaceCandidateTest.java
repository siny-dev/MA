package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class PlaceCandidateTest {
    @Test public void naverScaledWgs84CoordinatesAreConvertedToDegrees() throws Exception {
        JSONObject item = new JSONObject();
        item.put("title", "서울시청");
        item.put("category", "관공서 > 시청");
        item.put("address", "서울 중구 태평로1가 31");
        item.put("roadAddress", "서울 중구 세종대로 110");
        item.put("mapx", "1269873882");
        item.put("mapy", "375666103");

        PlaceCandidate place = PlaceCandidate.fromNaver(item);

        assertEquals(37.5666103, place.latitude, 0.0000001);
        assertEquals(126.9873882, place.longitude, 0.0000001);
        assertTrue(NaverMapLink.publicTransitUri(place).contains("dlat=37.5666103"));
        assertTrue(NaverMapLink.publicTransitUri(place).contains("dlng=126.9873882"));
    }

    @Test public void alreadyDecimalWgs84CoordinatesRemainUnchanged() throws Exception {
        JSONObject item = new JSONObject();
        item.put("title", "피부과");
        item.put("address", "부산 금정구 서동");
        item.put("mapx", "129.1056");
        item.put("mapy", "35.2134");
        PlaceCandidate place = PlaceCandidate.fromNaver(item);
        assertEquals(35.2134, place.latitude, 0.0000001);
        assertEquals(129.1056, place.longitude, 0.0000001);
    }
}
