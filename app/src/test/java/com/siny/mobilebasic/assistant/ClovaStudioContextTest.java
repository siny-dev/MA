package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class ClovaStudioContextTest {
    @Test public void aiReceivesPreviousDestinationAndNewSpeechAsSeparateTurns() throws Exception {
        JSONObject input = ClovaStudioClient.interpretationInput(
                "부산 금정구 서동 피부과", "전문의 있는 곳으로 찾아줘");
        assertEquals("부산 금정구 서동 피부과", input.getString("previous_request"));
        assertEquals("전문의 있는 곳으로 찾아줘", input.getString("latest_user_utterance"));
    }

    @Test public void aiReceivesAnEmptyPreviousTurnForANewConversation() throws Exception {
        JSONObject input = ClovaStudioClient.interpretationInput(null, "서동 피부과");
        assertEquals("", input.getString("previous_request"));
        assertEquals("서동 피부과", input.getString("latest_user_utterance"));
    }
}
