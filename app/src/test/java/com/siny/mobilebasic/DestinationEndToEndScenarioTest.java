package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Scenario fixtures use the same phrase, intent, NAVER search, and consent parsers as the app. */
public final class DestinationEndToEndScenarioTest {
    @Test public void reportedSeodongDermatologyCaseDoesNotCollapseToTheNeighborhood() throws Exception {
        String speech = "부산 금정구 서동에 있는 피부과";
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(speech);
        assertTrue(parsed.isUsable());

        DestinationAiClient.Result ai = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"서동\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\"}");
        String query = DestinationQueryGuard.preserveExplicitIntent("", speech, ai.searchQuery);
        assertTrue(query.contains("부산"));
        assertTrue(query.contains("금정구"));
        assertTrue(query.contains("서동"));
        assertTrue(query.contains("피부과"));

        NaverLocalSearchClient.Result results = NaverLocalSearchClient.parseResponse(
                response(item("<b>이종호</b>피부과", "병원 > 피부과", "부산광역시 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156")), query);
        assertEquals(1, results.places.size());
        assertEquals("이종호 피부과", results.places.get(0).name);
        assertEquals("부산광역시 금정구 서동로 123", results.places.get(0).address);
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classify("네, 맞아요"));
    }

    @Test public void colloquialSpeechAndRecognitionPunctuationRetainThePlaceCategory() throws Exception {
        String[] utterances = {
                "서동의 피부과요", "저기 부산 금정구 서동, 피부과", "부산 금정구 서동 피부과 가자"
        };
        for (String speech : utterances) {
            DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(speech);
            assertTrue(speech, parsed.isUsable());
            String query = DestinationQueryGuard.preserveExplicitIntent("", speech, "서동");
            assertTrue(speech, query.contains("피부과"));
            assertTrue(speech, query.contains("서동"));
        }
    }

    @Test public void conversationClarificationKeepsPriorDistrictAndNewNeighborhoodAndCategory() {
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구에 있는 병원",
                "서동의 피부과", "서동");
        assertEquals("부산 금정구 서동 피부과", query);
    }

    @Test public void destinationCorrectionDropsThePreviouslyRequestedAreaAndCategory() {
        String speech = "부산 금정구 서동 피부과 말고 서울 종로구 약국으로 가자";
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(speech);
        assertTrue(parsed.isUsable());
        assertEquals("서울 종로구 약국", parsed.destination);
        String query = DestinationQueryGuard.preserveExplicitIntent("부산 금정구 서동 피부과",
                speech, "부산 금정구 서동 피부과");
        assertEquals("서울 종로구 약국", query);
    }

    @Test public void ambiguousAiTurnAsksOneQuestionInsteadOfSearchingAShortFragment() throws Exception {
        DestinationAiClient.Result ai = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"\",\"needsClarification\":true,"
                        + "\"clarificationQuestion\":\"어느 동네에 있는 피부과인가요?\"}");
        assertTrue(ai.needsClarification);
        assertEquals("", ai.searchQuery);
        assertEquals("어느 동네에 있는 피부과인가요?", ai.clarificationQuestion);
    }

    @Test public void administrativeAreaOrWrongMedicalTypeNeverBecomesTheRouteCandidate() throws Exception {
        String administrativeOnly = response(item("서동", "지역 > 행정구역",
                "부산광역시 금정구 서동 123-4", "부산광역시 금정구 서동로 123",
                "129.1022", "35.2156"));
        assertTrue(NaverLocalSearchClient.parseResponse(administrativeOnly,
                "부산 금정구 서동 피부과").places.isEmpty());

        String genericDoctor = response(item("이종호의원", "병원 > 의원",
                "부산광역시 금정구 서동 123-4", "부산광역시 금정구 서동로 123",
                "129.1022", "35.2156"));
        assertTrue(NaverLocalSearchClient.parseResponse(genericDoctor,
                "부산 금정구 서동 피부과").places.isEmpty());
    }

    @Test public void multiplePlausiblePlacesStayUnconfirmedUntilTheUserChooses() throws Exception {
        NaverLocalSearchClient.Result results = NaverLocalSearchClient.parseResponse(response(
                item("이종호피부과", "병원 > 피부과", "부산 금정구 서동 123-4",
                        "부산광역시 금정구 서동로 123", "129.1022", "35.2156"),
                item("서동맑은피부과", "병원 > 피부과", "부산 금정구 서동 124-5",
                        "부산광역시 금정구 서동로 210", "129.1030", "35.2160")),
                "부산 금정구 서동 피부과");
        assertEquals(2, results.places.size());
        assertFalse(DestinationConfirmationParser.classify("어느 쪽이죠?")
                == DestinationConfirmationParser.Decision.YES);
    }

    @Test public void sayingNoOrUncertainDoesNotApproveAnIncorrectCandidate() {
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("아니요, 다른 곳이에요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("잘 모르겠어요"));
    }

    private static String response(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String item(String title, String category, String address, String roadAddress,
                               String longitude, String latitude) {
        return "{\"title\":\"" + title + "\",\"category\":\"" + category
                + "\",\"address\":\"" + address + "\",\"roadAddress\":\"" + roadAddress
                + "\",\"mapx\":\"" + longitude + "\",\"mapy\":\"" + latitude + "\"}";
    }
}
