package com.siny.mobilebasic;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DestinationAiClientTest {
    @Test public void clovaRequestDisablesInferenceForStructuredJsonOutput() throws Exception {
        JSONObject request = DestinationAiClient.buildClovaRequest(
                "부산 금정구 서동 피부과", "");

        assertEquals("none", request.getJSONObject("thinking").getString("effort"));
        assertEquals("json", request.getJSONObject("responseFormat").getString("type"));
        assertTrue(request.getJSONObject("responseFormat").has("schema"));
    }

    @Test public void aiRequiresAnExplicitKeyOrProxy() {
        assertFalse(DestinationAiClient.isConfigured("", ""));
        assertFalse(DestinationAiClient.isConfigured(null, null));
        assertTrue(DestinationAiClient.isConfigured("", "test-key"));
        assertTrue(DestinationAiClient.isConfigured("https://ma.example.test", ""));
    }

    @Test public void parsesGeminiResponseWithoutClaimingItFoundARealPlace() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                        + "\"{\\\"searchQuery\\\":\\\"부산 금정구 서동 피부과\\\","
                        + "\\\"needsClarification\\\":false,\\\"clarificationQuestion\\\":\\\"\\\"}\"}]}}]}");
        assertFalse(result.needsClarification);
        assertEquals("부산 금정구 서동 피부과", result.searchQuery);
    }

    @Test public void parsesProxyInterpretationContract() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"부산 금정구 서동 피부과\",\"region\":\"부산 금정구 서동\","
                        + "\"category\":\"피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\"}");
        assertEquals("부산 금정구 서동 피부과", result.searchQuery);
    }

    @Test public void ignoresAiInventedPlaceNameWhenBuildingSearch() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"부산 금정구 서동 이종호 피부과\","
                        + "\"region\":\"부산 금정구 서동\",\"category\":\"피부과\","
                        + "\"placeName\":\"이종호 피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\"}",
                "부산 금정구 서동에 피부과 찾아줘");

        assertFalse(result.hasNamedPlace);
        assertEquals("부산 금정구 서동 피부과", result.searchQuery);
    }

    @Test public void spokenPlaceNameCanComeFromEarlierClarificationTurn() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"부산 금정구 서동 이종호 피부과\","
                        + "\"region\":\"부산 금정구 서동\",\"category\":\"피부과\","
                        + "\"placeName\":\"이종호 피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\"}",
                "그곳으로 안내해줘", "부산 금정구 서동에 있는 이종호 피부과");

        assertTrue(result.hasNamedPlace);
        assertEquals("부산 금정구 서동 이종호 피부과", result.searchQuery);
    }

    @Test public void positiveParkingQualityEvidenceIsNotMistakenForCancellation() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"부산 금정구 서동 피부과\",\"region\":\"부산 금정구 서동\","
                        + "\"category\":\"피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\",\"conditions\":["
                        + "{\"label\":\"주차 조건\",\"evidence\":\"주차 괜찮은 곳\","
                        + "\"polarity\":\"REQUIRE\"}]}",
                "주차 괜찮은 곳으로 서동 피부과 찾아줘");

        assertEquals(1, result.conditions.size());
        assertEquals("REQUIRE", result.conditionPolarities.get(0));
        assertEquals("주차 괜찮은 곳", result.conditionEvidence.get(0));
    }

    @Test public void okayButEvidenceDoesNotTurnIntoARequireOrCancellation() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"부산 금정구 서동 피부과\",\"region\":\"부산 금정구 서동\","
                        + "\"category\":\"피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\",\"conditions\":["
                        + "{\"label\":\"주차 조건\",\"evidence\":\"주차는 괜찮은데\","
                        + "\"polarity\":\"REQUIRE\"}]}",
                "주차는 괜찮은데 엘리베이터는 필요 없어, 서동 피부과 찾아줘");

        assertTrue(result.conditions.isEmpty());
    }

    @Test public void asksUserWhenAreaOrPlaceIsMissing() throws Exception {
        DestinationAiClient.Result result = DestinationAiClient.parseResponse(
                "{\"searchQuery\":\"\",\"needsClarification\":true,"
                        + "\"clarificationQuestion\":\"어느 동네에 있는 병원인가요?\"}");
        assertTrue(result.needsClarification);
        assertEquals("어느 동네에 있는 병원인가요?", result.clarificationQuestion);
        assertEquals("", result.searchQuery);
    }

    @Test public void rejectsEmptySearchAndMissingClarificationQuestion() throws Exception {
        assertInvalid("{\"searchQuery\":\"\",\"needsClarification\":false,\"clarificationQuestion\":\"\"}");
        assertInvalid("{\"searchQuery\":\"\",\"needsClarification\":true,\"clarificationQuestion\":\"\"}");
        assertInvalid("{\"searchQuery\":\"서울역\",\"needsClarification\":false,\"clarificationQuestion\":\"어느 역인가요?\"}");
    }

    @Test public void generatedPromptKeepsTheCoreFailureCaseExplicit() {
        String prompt = DestinationAiClient.buildPrompt("부산 금정구 서동의 피부과", "");
        assertTrue(prompt.contains("부산 금정구 서동 피부과"));
        assertTrue(prompt.contains("‘서동’만 목적지로 만들면 안 됩니다"));
        assertTrue(prompt.contains("주소, 상호, 좌표를 만들거나 추측하지 마세요"));
    }

    private static void assertInvalid(String payload) throws Exception {
        try {
            DestinationAiClient.parseResponse(payload);
            fail("Invalid interpretation must fail closed.");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("Invalid"));
        }
    }
}
