package com.siny.mobilebasic;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeminiLiveProtocolTest {
    @Test public void setupUsesNativeAudioAndLocalFunctionTools() {
        JSONObject setup = GeminiLiveProtocol.setupMessage().optJSONObject("setup");
        assertEquals("models/gemini-3.8-live", setup.optString("model"));
        assertEquals("AUDIO", setup.optJSONArray("responseModalities").optString(0));
        JSONObject automaticVad = setup.optJSONObject("realtimeInputConfig")
                .optJSONObject("automaticActivityDetection");
        assertTrue(automaticVad.optBoolean("disabled"));
        assertFalse(setup.has("inputAudioTranscription"));
        assertTrue(setup.has("outputAudioTranscription"));
        String instruction = setup.optJSONObject("systemInstruction").optJSONArray("parts")
                .optJSONObject(0).optString("text");
        assertTrue(instruction.contains("네이티브 오디오 대화"));
        assertTrue(instruction.contains("앱이 실제 NAVER 결과의 이름과 주소"));
        assertTrue(instruction.contains("실제로 TTS 재생을 완료한 경우에만 app_presentation.completed=true"));
        assertTrue(instruction.contains("candidate_presented=true는 정확한 NAVER 후보명과 주소가 끝까지 읽혔고"));
        assertTrue(instruction.contains("앱의 도구 응답과 후보 상태는 이전에 억제된 네 오디오보다 우선한다"));
        assertTrue(instruction.contains("사용자가 새로 말한 뒤에만"));
        assertTrue(instruction.contains("바꾸라고 한 항목만 새 값으로 바꿔 검색"));
        assertTrue(instruction.contains("destination_slots는 앱이 현재 요청에서 받은 슬롯 기록"));
        assertTrue(instruction.contains("region에 이미 포함된 동 이름을 place_name에 그대로 반복한 것만으로는 목적지가 정해진 것이 아니므로 검색하지 말고"));

        JSONArray tools = setup.optJSONArray("tools");
        JSONArray functions = tools.optJSONObject(0).optJSONArray("functionDeclarations");
        assertEquals("search_places", functions.optJSONObject(0).optString("name"));
        assertEquals("select_candidate", functions.optJSONObject(1).optString("name"));
        assertEquals("start_navigation", functions.optJSONObject(3).optString("name"));
    }

    @Test public void rawPcmIsSentAsSixteenKhzLittleEndianAudioWithManualPttBoundaries() {
        byte[] pcm = new byte[]{0, 1, -2, 127};
        JSONObject audio = GeminiLiveProtocol.audioMessage(pcm)
                .optJSONObject("realtimeInput").optJSONObject("audio");
        assertEquals("audio/pcm;rate=16000", audio.optString("mimeType"));
        assertEquals(Base64.getEncoder().encodeToString(pcm), audio.optString("data"));
        assertTrue(GeminiLiveProtocol.activityStartMessage().optJSONObject("realtimeInput")
                .has("activityStart"));
        assertTrue(GeminiLiveProtocol.activityEndMessage().optJSONObject("realtimeInput")
                .has("activityEnd"));
        assertFalse(GeminiLiveProtocol.activityEndMessage().optJSONObject("realtimeInput")
                .has("audioStreamEnd"));
    }

    @Test public void toolResponsesPreserveCallIdentityForManualLiveApiResponse() throws Exception {
        JSONArray functions = new JSONArray().put(new JSONObject()
                .put("id", "call-1").put("name", "search_places")
                .put("response", new JSONObject().put("ok", true)));
        JSONObject message = GeminiLiveProtocol.toolResponseMessage(functions);
        JSONObject response = message.optJSONObject("toolResponse")
                .optJSONArray("functionResponses").optJSONObject(0);
        assertEquals("call-1", response.optString("id"));
        assertEquals("search_places", response.optString("name"));
        assertTrue(response.optJSONObject("response").optBoolean("ok"));
    }

    @Test public void anySearchOrSelectionInBatchRequiresSuppressedModelAudio() throws Exception {
        JSONArray generic = new JSONArray().put(new JSONObject()
                .put("id", "call-1").put("name", "set_travel_mode"));
        JSONArray mixed = new JSONArray()
                .put(new JSONObject().put("id", "call-2").put("name", "set_travel_mode"))
                .put(new JSONObject().put("id", "call-3").put("name", "search_places"));
        JSONArray selection = new JSONArray().put(new JSONObject()
                .put("id", "call-4").put("name", "select_candidate"));

        assertFalse(GeminiLiveProtocol.requiresSuppressedAssistantAudio(null));
        assertFalse(GeminiLiveProtocol.requiresSuppressedAssistantAudio(generic));
        assertTrue(GeminiLiveProtocol.requiresSuppressedAssistantAudio(mixed));
        assertTrue(GeminiLiveProtocol.requiresSuppressedAssistantAudio(selection));
    }

    @Test public void toolResponseCarriesOnlyTheActuallyCompletedAppPresentation() throws Exception {
        JSONObject speech = GeminiLiveProtocol.appPresentation(true,
                "검색한 곳은 서동피부과, 부산 금정구 서동입니다.", true);
        JSONObject function = new JSONArray().put(new JSONObject()
                .put("id", "call-2").put("name", "search_places")
                .put("response", new JSONObject().put("app_presentation", speech)))
                .optJSONObject(0);
        JSONObject response = function.optJSONObject("response").optJSONObject("app_presentation");
        assertTrue(response.optBoolean("completed"));
        assertTrue(response.optBoolean("candidate_presented"));
        assertEquals("검색한 곳은 서동피부과, 부산 금정구 서동입니다.", response.optString("text"));
        JSONObject incomplete = GeminiLiveProtocol.appPresentation(false,
                "이 문장은 끝까지 재생되지 않았어요.");
        assertFalse(incomplete.optBoolean("completed"));
        assertFalse(incomplete.optBoolean("candidate_presented"));
        assertFalse(incomplete.has("text"));
        assertFalse(GeminiLiveProtocol.appPresentation(true,
                "검색 결과가 아닌 안내 문장입니다.").optBoolean("candidate_presented"));
        assertFalse(GeminiLiveProtocol.appPresentation(true, " ").optBoolean("completed"));
        assertFalse(GeminiLiveProtocol.setupMessage().has("clientContent"));
    }
}
