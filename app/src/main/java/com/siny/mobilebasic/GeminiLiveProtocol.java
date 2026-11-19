package com.siny.mobilebasic;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

/** Pure Gemini Live wire-protocol messages, kept separate for offline contract tests. */
final class GeminiLiveProtocol {
    static final String MODEL = "gemini-3.8-live";
    static final int INPUT_SAMPLE_RATE = 16000;
    static final int OUTPUT_SAMPLE_RATE = 24000;

    private static final String SYSTEM_INSTRUCTION =
            "너는 MA(Mother's Assistant) 안에서 70대 이상 사용자를 돕는 한국어 음성 대화 비서다. "
            + "사용자의 마이크 음성을 Gemini Live의 네이티브 오디오 대화로 직접 듣고, 앞뒤 대화의 뜻을 이어서 이해한다. "
            + "음성 인식 결과를 규칙 문답에 끼워 맞추지 말고, 목적·지역·정정·거절·확인 의도를 대화 맥락으로 판단한다. "
            + "짧고 자연스럽고 공손한 문장으로 한 번에 한 가지씩 묻는다. 사용자가 '아니'라고 하면 같은 후보나 질문을 반복하지 말고 무엇이 다른지 묻거나 정정 내용을 반영한다. "
            + "목적지 검색은 search_places 도구로만 한다. 지역(최소 시/군/구 수준)과 장소 종류 또는 정확한 장소 이름을 모두 확보하기 전에는 검색하지 말고 필요한 정보를 대화로 물어라. "
            + "place_name은 실제 가게·병원 이름이나 검색할 구체적인 장소 표현이어야 한다. region에 이미 포함된 동 이름을 place_name에 그대로 반복한 것만으로는 목적지가 정해진 것이 아니므로 검색하지 말고 병원·약국처럼 찾을 장소 종류를 물어라. "
            + "도구 응답의 destination_slots는 앱이 현재 요청에서 받은 슬롯 기록이다. 정보가 빠져 되물을 때 이미 제공된 지역/종류/이름은 유지하고, 다음 search_places에 채워진 슬롯을 모두 다시 포함해라. 사용자가 일부 조건만 고치면 지시한 슬롯만 바꿔라. 사용자가 새 목적지를 시작하면 이전 슬롯을 섞지 마라. "
            + "검색 결과의 장소명과 주소만 사실로 사용하고 결과에 없는 전문의, 영업 여부, 거리, 특징을 지어내지 마라. "
            + "도구 결과에 후보 ID, 순번, 이름, 주소가 제공된다. 앱이 실제 NAVER 결과의 이름과 주소를 음성으로 정확히 읽으므로 네가 검색 결과를 소리 내어 다시 읽거나 새 이름·주소를 만들지 마라. "
            + "search_places/select_candidate 도구 응답에는 앱이 실제로 TTS 재생을 완료한 경우에만 app_presentation.completed=true와 정확한 문장이 들어간다. candidate_presented=true는 정확한 NAVER 후보명과 주소가 끝까지 읽혔고 새 사용자 발화를 기다리는 후보로 앱에 기록됐다는 뜻이다. 이는 길 안내 동의가 아니다. false면 그 후보가 실제로 읽혔다고 가정하지 마라. 이것이 앱이 사용자에게 무엇을 말했고 후보가 제시됐는지에 관한 공식 상태다. false이거나 필드가 없으면 사용자가 후보를 들었다고 가정하지 말고, 자기 오디오가 앱에서 억제됐을 수 있으니 그 안에 질문을 넣지 마라. 앱의 도구 응답과 후보 상태는 이전에 억제된 네 오디오보다 우선한다. "
            + "'네', '응', '맞아'는 바로 직전에 앱이 실제로 읽은 질문에만 답한다. 직전 질문이 지역·장소 종류·후보 선택 확인이면 길 안내 동의가 아니다. 안내 동의를 명시적으로 물은 바로 다음 새 발화에서만 start_navigation을 호출하라. "
            + "'아까 말한 데', '그 병원'처럼 여러 후보나 거절한 곳 중 무엇인지 분명하지 않은 표현은 임의로 선택하지 말고 한 가지 짧은 확인 질문을 하라. "
            + "후보가 여러 개면 앱이 순번, 이름, 주소를 읽는다. 사용자가 말한 순번이나 이름을 대화 맥락에 맞춰 candidate_id에 연결해 select_candidate를 호출하라. "
            + "후보를 고르면 select_candidate 도구를 호출하라. 앱이 선택한 장소명과 주소를 정확히 읽고 길 안내 동의를 묻는다. "
            + "명확한 긍정 답변을 사용자가 새로 말한 뒤에만 start_navigation을 호출한다. 검색이나 선택과 같은 사용자 발화 turn에서 바로 길 안내를 시작하지 마라. "
            + "사용자가 '아니'만 말하면 reject_candidate로 현재 후보만 제외하고 지역과 장소 종류는 유지한 채 무엇을 바꿀지 한 가지만 물어라. "
            + "사용자가 '피부과 말고 약국'처럼 바꿀 조건을 말하면 바꾸지 말라고 되묻지 마라. reject_candidate로 기존 후보를 제외하고, 사용자가 바꾸라고 한 항목만 새 값으로 바꿔 검색하라. 나머지 확인된 지역과 조건은 그대로 유지한다. 거절된 동일 장소를 다시 권하지 마라. "
            + "기본 교통수단은 대중교통이다. 택시 버튼은 택시 모드를 뜻한다. 음성으로 택시를 명시하면 set_travel_mode를 호출할 수 있다. "
            + "길 안내 시작은 사용자가 확인한 후보 하나에 대해서만 도구를 호출한다. 검색 결과가 없거나 불확실하면 추측하지 말고 지역이나 장소를 다시 묻는다.";

    private GeminiLiveProtocol() {}

    static JSONObject setupMessage() {
        JSONObject root = new JSONObject();
        try {
            JSONObject setup = new JSONObject();
            JSONArray declarations = new JSONArray()
                    .put(function("search_places", "지역과 장소 종류/이름으로 실제 NAVER Local Search를 합니다.",
                            new String[]{"region", "category", "place_name"}, new String[]{"region"}))
                    .put(function("select_candidate", "사용자가 순번이나 이름으로 고른 실제 검색 후보를 지정합니다. 길 안내는 시작하지 않습니다.",
                            new String[]{"candidate_id"}, new String[]{"candidate_id"}))
                    .put(function("reject_candidate", "사용자가 현재 후보를 거절하고 동일 장소를 제외합니다.",
                            new String[]{"candidate_id", "reason"}, new String[]{"candidate_id"}))
                    .put(function("start_navigation", "사용자가 새 발화로 명확히 동의한 뒤에만 경로를 엽니다.",
                            new String[]{"candidate_id"}, new String[]{"candidate_id"}))
                    .put(function("set_travel_mode", "대중교통 또는 택시 모드를 설정합니다.",
                            new String[]{"mode"}, new String[]{"mode"}));
            setup.put("model", "models/" + MODEL)
                    .put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", SYSTEM_INSTRUCTION))))
                    .put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection",
                            new JSONObject().put("disabled", true)))
                    .put("tools", new JSONArray().put(new JSONObject()
                            .put("functionDeclarations", declarations)))
                    .put("outputAudioTranscription", new JSONObject())
                    .put("speechConfig", new JSONObject().put("languageCode", "ko-KR")
                            .put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig",
                                    new JSONObject().put("voiceName", "Kore"))));
            root.put("setup", setup);
        } catch (JSONException ignored) { }
        return root;
    }

    static JSONObject audioMessage(byte[] pcm) {
        JSONObject realtime = new JSONObject();
        JSONObject audio = new JSONObject();
        try {
            audio.put("data", Base64.getEncoder().encodeToString(pcm == null ? new byte[0] : pcm));
            audio.put("mimeType", "audio/pcm;rate=" + INPUT_SAMPLE_RATE);
            realtime.put("audio", audio);
            return new JSONObject().put("realtimeInput", realtime);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static JSONObject activityStartMessage() {
        try {
            return new JSONObject().put("realtimeInput", new JSONObject().put("activityStart", new JSONObject()));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static JSONObject activityEndMessage() {
        try {
            return new JSONObject().put("realtimeInput", new JSONObject().put("activityEnd", new JSONObject()));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static JSONObject toolResponseMessage(JSONArray functionResponses) {
        try {
            return new JSONObject().put("toolResponse", new JSONObject().put("functionResponses",
                    functionResponses == null ? new JSONArray() : functionResponses));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static boolean requiresSuppressedAssistantAudio(JSONArray functionResponses) {
        if (functionResponses == null) return false;
        for (int i = 0; i < functionResponses.length(); i++) {
            JSONObject item = functionResponses.optJSONObject(i);
            String name = item == null ? "" : item.optString("name", "");
            if ("search_places".equals(name) || "select_candidate".equals(name)) return true;
        }
        return false;
    }

    static JSONObject appPresentation(boolean completed, String exactSpeech) {
        return appPresentation(completed, exactSpeech, false);
    }

    static JSONObject appPresentation(boolean completed, String exactSpeech, boolean candidatePresented) {
        JSONObject result = new JSONObject();
        boolean hasSpeech = exactSpeech != null && !exactSpeech.trim().isEmpty();
        boolean actuallyCompleted = completed && hasSpeech;
        try {
            result.put("completed", actuallyCompleted);
            result.put("candidate_presented", actuallyCompleted && candidatePresented);
            if (actuallyCompleted) {
                result.put("text", exactSpeech.trim());
            }
        } catch (JSONException ignored) { }
        return result;
    }

    private static JSONObject function(String name, String description, String[] properties, String[] required) {
        try {
            JSONObject propertySchema = new JSONObject();
            JSONArray requiredArray = new JSONArray();
            for (String property : properties) propertySchema.put(property,
                    new JSONObject().put("type", "STRING"));
            for (String property : required) requiredArray.put(property);
            return new JSONObject().put("name", name).put("description", description)
                    .put("parameters", new JSONObject().put("type", "OBJECT")
                            .put("properties", propertySchema).put("required", requiredArray));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
