package com.siny.mobilebasic;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DestinationAiClientTest {
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
                "{\"searchQuery\":\"부산 금정구 서동 피부과\",\"needsClarification\":false,"
                        + "\"clarificationQuestion\":\"\"}");
        assertEquals("부산 금정구 서동 피부과", result.searchQuery);
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
        assertTrue(prompt.contains("‘서동’만 남기면 안 됩니다"));
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
