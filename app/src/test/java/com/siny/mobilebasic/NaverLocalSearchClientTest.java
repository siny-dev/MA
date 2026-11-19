package com.siny.mobilebasic;

import org.json.JSONException;
import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NaverLocalSearchClientTest {
    @Test public void requestUsesEncodedKoreanQueryAndNaverHubParameters() throws Exception {
        URL url = NaverLocalSearchClient.buildUrl("부산 금정구 서동 피부과");
        assertEquals("https", url.getProtocol());
        assertEquals("naverapihub.apigw.ntruss.com", url.getHost());
        assertTrue(url.getQuery().contains("query=%EB%B6%80%EC%82%B0+%EA%B8%88%EC%A0%95%EA%B5%AC"));
        assertTrue(url.getQuery().contains("display=5"));
        assertTrue(url.getQuery().contains("format=json"));
        assertTrue(NaverLocalSearchClient.validCredentials("client-id", "client-secret"));
        assertFalse(NaverLocalSearchClient.validCredentials("client id", "client-secret"));
    }

    @Test public void returnsNaverPoiNameRoadAddressAndWgs84Coordinates() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("<b>이종호</b>피부과", "피부과 > 병원", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 금정구 서동 피부과");
        assertEquals(1, result.places.size());
        NaverLocalSearchClient.Place found = result.places.get(0);
        assertEquals("이종호 피부과", found.name);
        assertEquals("부산광역시 금정구 서동로 123", found.address);
        assertEquals(35.2156, found.latitude, 0.00001);
        assertEquals(129.1022, found.longitude, 0.00001);
    }

    @Test public void acceptsNaverFixedPointWgs84Coordinates() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("서동피부과", "피부과", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "1291022000", "352156000")),
                "부산 금정구 서동 피부과");
        assertEquals(1, result.places.size());
        assertEquals(35.2156, result.places.get(0).latitude, 0.00001);
        assertEquals(129.1022, result.places.get(0).longitude, 0.00001);
    }

    @Test public void rejectsGenericClinicWhenCategoryDoesNotMatchDermatologyRequest() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("이종호의원", "병원 > 의원", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 금정구 서동 피부과");
        assertTrue(result.places.isEmpty());
    }

    @Test public void acceptsSkinClinicCategoryWhenNameOmitsDermatology() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("이종호의원", "병원 > 피부과", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 금정구 서동 피부과");
        assertEquals(1, result.places.size());
    }

    @Test public void roadNameAloneCannotSatisfyRequestedNeighborhood() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("서동피부과", "병원 > 피부과", "부산광역시 부산진구 전포동 123-4",
                        "부산광역시 부산진구 서동로 123", "129.0640", "35.1570")),
                "부산 서동 피부과");

        assertTrue(result.places.isEmpty());
        assertEquals(1, result.rawResultCount);
        assertEquals(1, result.rejectedByArea);
        assertEquals(1, result.rejectedByRequestedConstraints);
    }

    @Test public void acceptsNeighborhoodFromParcelAddressWhenDistrictWasNotSpoken() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("서동피부과", "병원 > 피부과", "부산광역시 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 서동 피부과");

        assertEquals(1, result.places.size());
    }

    @Test public void shortenedCityNameMatchesOfficialCityAddressForm() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("서동피부과", "병원 > 피부과", "부산광역시 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 서동 피부과");

        assertEquals(1, result.places.size());
    }

    @Test public void rejectsWrongBusinessTypesEvenWhenAddressMatches() throws Exception {
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(response(
                item("서동 우리은행", "금융,보험 > 은행", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")),
                "부산 금정구 서동 약국");
        assertTrue(result.places.isEmpty());
    }

    @Test public void rejectsNeighborhoodOnlyNameAndIncompleteOrOutOfCountryCoordinates() throws Exception {
        String payload = "{\"items\":["
                + item("서동", "지역 > 행정구역", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156") + ","
                + item("해외피부과", "피부과", "미국 123 Main St", "미국 123 Main St", "-122.4", "37.8")
                + "]}";
        assertTrue(NaverLocalSearchClient.parseResponse(payload, "부산 금정구 서동 피부과").places.isEmpty());
        assertTrue(NaverLocalSearchClient.parseResponse("{\"items\":[]}", "피부과").places.isEmpty());
    }

    @Test public void duplicateListingsDoNotCreateFalseCandidateChoices() throws Exception {
        String clinic = item("이종호피부과", "피부과", "부산 금정구 서동 123-4",
                "부산광역시 금정구 서동로 123", "129.1022", "35.2156");
        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(
                response(clinic, clinic), "부산 금정구 서동 피부과");
        assertEquals(1, result.places.size());
    }

    @Test public void sameAddressWithDifferentBusinessNamesRemainsTwoCandidates() throws Exception {
        String first = item("이종호피부과", "피부과", "부산 금정구 서동 123-4",
                "부산광역시 금정구 서동로 123", "129.1022", "35.2156");
        String second = item("서동맑은의원", "병원 > 피부과", "부산 금정구 서동 123-4",
                "부산광역시 금정구 서동로 123", "129.1022", "35.2156");

        NaverLocalSearchClient.Result result = NaverLocalSearchClient.parseResponse(
                response(first, second), "부산 금정구 서동 피부과");

        assertEquals(2, result.places.size());
        assertEquals("이종호피부과", result.places.get(0).name);
        assertEquals("서동맑은의원", result.places.get(1).name);
        assertEquals(result.places.get(0).address, result.places.get(1).address);
    }

    @Test public void malformedAndOversizedResponsesAreRejected() throws Exception {
        assertInvalid("not-json");
        assertInvalid("x".repeat(NaverLocalSearchClient.MAX_RESPONSE_BYTES + 1));
    }

    private static void assertInvalid(String payload) throws Exception {
        try {
            NaverLocalSearchClient.parseResponse(payload, "부산 금정구 서동 피부과");
            throw new AssertionError("Invalid response must fail closed.");
        } catch (JSONException expected) {
            assertTrue(true);
        }
    }

    private static String response(String... values) {
        return "{\"items\":[" + String.join(",", values) + "]}";
    }

    private static String item(String title, String category, String address, String roadAddress,
                               String longitude, String latitude) {
        return "{\"title\":\"" + title + "\",\"category\":\"" + category
                + "\",\"address\":\"" + address + "\",\"roadAddress\":\"" + roadAddress
                + "\",\"mapx\":\"" + longitude + "\",\"mapy\":\"" + latitude + "\"}";
    }
}
