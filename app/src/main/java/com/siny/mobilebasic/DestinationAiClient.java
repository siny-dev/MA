package com.siny.mobilebasic;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Converts an elder's Korean conversation into a search phrase; it never invents a place result. */
final class DestinationAiClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent";

    private DestinationAiClient() {}

    static boolean isConfigured(String proxyBaseUrl, String apiKey) {
        return apiKey != null && !apiKey.trim().isEmpty()
                || proxyBaseUrl != null && !proxyBaseUrl.trim().isEmpty();
    }

    static boolean isClovaConfigured(String apiKey) {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Uses NAVER CLOVA Studio HyperCLOVA X for the same constrained destination intent. */
    static Result interpretWithClova(String apiKey, String utterance, String clarificationContext)
            throws IOException, JSONException {
        validateInput(utterance, clarificationContext);
        String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (cleanKey.length() < 20 || cleanKey.length() > 2048 || cleanKey.matches(".*\\s.*")) {
            throw new IOException("CLOVA Studio API key is invalid.");
        }
        URL endpoint = new URL("https://clovastudio.stream.ntruss.com/v3/chat-completions/HCX-007");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(25000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + cleanKey);
        connection.setRequestProperty("X-NCP-CLOVASTUDIO-REQUEST-ID", UUID.randomUUID().toString());
        try {
            byte[] body = buildClovaRequest(utterance.trim(), clarificationContext).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readBounded(stream, MAX_RESPONSE_BYTES);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("NAVER CLOVA destination interpretation failed (HTTP " + status + ").");
            }
            JSONObject root = new JSONObject(payload);
            JSONObject result = root.optJSONObject("result");
            JSONObject message = result == null ? null : result.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "").trim();
            if (content.isEmpty() || content.length() > 8192) {
                throw new JSONException("CLOVA Studio returned no bounded intent result.");
            }
            return parseInterpreted(new JSONObject(content), utterance.trim(), clarificationContext);
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject buildClovaRequest(String utterance, String clarificationContext)
            throws JSONException {
        JSONObject system = new JSONObject().put("role", "system")
                .put("content", "70대 이상 사용자의 목적지를 돕는 MA 한국어 대화 해석기입니다. "
                        + "사용자 말에 없는 주소, 상호, 자격, 사실을 추측하지 말고 지정된 JSON 형식으로만 답하세요.");
        JSONObject user = new JSONObject().put("role", "user")
                .put("content", buildPrompt(utterance, clarificationContext));
        JSONObject responseFormat = new JSONObject().put("type", "json")
                .put("schema", clovaDestinationSchema());
        return new JSONObject().put("messages", new JSONArray().put(system).put(user))
                .put("maxCompletionTokens", 512)
                .put("temperature", 0.1)
                .put("thinking", new JSONObject().put("effort", "none"))
                .put("responseFormat", responseFormat);
    }

    private static JSONObject clovaDestinationSchema() throws JSONException {
        JSONObject properties = new JSONObject()
                .put("searchQuery", new JSONObject().put("type", "string"))
                .put("region", new JSONObject().put("type", "string"))
                .put("category", new JSONObject().put("type", "string"))
                .put("placeName", new JSONObject().put("type", "string"))
                .put("conditions", new JSONObject().put("type", "array")
                        .put("items", conditionSchema(false)))
                .put("needsClarification", new JSONObject().put("type", "boolean"))
                .put("clarificationQuestion", new JSONObject().put("type", "string"));
        JSONArray required = new JSONArray().put("searchQuery").put("region")
                .put("category").put("placeName").put("conditions")
                .put("needsClarification").put("clarificationQuestion");
        return new JSONObject().put("type", "object")
                .put("properties", properties).put("required", required);
    }

    private static JSONObject conditionSchema(boolean gemini) throws JSONException {
        String objectType = gemini ? "OBJECT" : "object";
        String stringType = gemini ? "STRING" : "string";
        JSONObject properties = new JSONObject()
                .put("label", new JSONObject().put("type", stringType))
                .put("evidence", new JSONObject().put("type", stringType))
                .put("polarity", new JSONObject().put("type", stringType)
                        .put("enum", new JSONArray().put("REQUIRE").put("AVOID")));
        JSONArray required = new JSONArray().put("label").put("evidence").put("polarity");
        JSONObject schema = new JSONObject().put("type", objectType)
                .put("properties", properties).put("required", required);
        if (gemini) schema.put("propertyOrdering", required).put("additionalProperties", false);
        return schema;
    }

    static Result interpret(Context context, String proxyBaseUrl, String apiKey, String utterance,
                            String clarificationContext) throws IOException, JSONException {
        validateInput(utterance, clarificationContext);
        String cleanUtterance = utterance.trim();
        String cleanContext = clarificationContext == null ? "" : clarificationContext.trim();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return interpretWithGemini(apiKey.trim(), cleanUtterance, cleanContext);
        }

        boolean debugBuild = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        URL url = endpoint(proxyBaseUrl, debugBuild);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(25000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            JSONObject request = new JSONObject().put("utterance", cleanUtterance)
                    .put("clarificationContext", cleanContext);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readBounded(stream, MAX_RESPONSE_BYTES);
            if (status != HttpURLConnection.HTTP_OK) {
                String code = "request-failed";
                try { code = new JSONObject(payload).optString("error", code); }
                catch (JSONException ignored) { }
                throw new IOException("Gemini intent service unavailable (" + code + ").");
            }
            return parseResponse(payload, cleanUtterance, cleanContext);
        } finally {
            connection.disconnect();
        }
    }

    static Result interpret(Context context, String proxyBaseUrl, String utterance,
                            String clarificationContext) throws IOException, JSONException {
        return interpret(context, proxyBaseUrl, "", utterance, clarificationContext);
    }

    private static Result interpretWithGemini(String apiKey, String utterance,
                                              String clarificationContext) throws IOException, JSONException {
        if (apiKey.length() < 20 || apiKey.length() > 256 || apiKey.matches(".*\\s.*")) {
            throw new IOException("Gemini API key is invalid.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(GEMINI_ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(25000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", apiKey);
        try {
            byte[] body = buildRequest(utterance, clarificationContext).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readBounded(stream, MAX_RESPONSE_BYTES);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Gemini destination interpretation failed (HTTP " + status + ").");
            }
            return parseResponse(payload, utterance, clarificationContext);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject buildRequest(String utterance, String clarificationContext)
            throws JSONException {
        JSONObject requestContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject()
                        .put("text", buildPrompt(utterance, clarificationContext))));
        JSONObject generationConfig = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", destinationSchema());
        return new JSONObject().put("contents", new JSONArray().put(requestContent))
                .put("generationConfig", generationConfig);
    }

    private static JSONObject destinationSchema() throws JSONException {
        JSONObject properties = new JSONObject()
                .put("searchQuery", new JSONObject().put("type", "STRING"))
                .put("region", new JSONObject().put("type", "STRING"))
                .put("category", new JSONObject().put("type", "STRING"))
                .put("placeName", new JSONObject().put("type", "STRING"))
                .put("conditions", new JSONObject().put("type", "ARRAY")
                        .put("items", conditionSchema(true)))
                .put("needsClarification", new JSONObject().put("type", "BOOLEAN"))
                .put("clarificationQuestion", new JSONObject().put("type", "STRING"));
        JSONArray ordered = new JSONArray().put("searchQuery").put("region")
                .put("category").put("placeName").put("conditions")
                .put("needsClarification").put("clarificationQuestion");
        return new JSONObject().put("type", "OBJECT")
                .put("properties", properties)
                .put("required", new JSONArray(ordered.toString()))
                .put("propertyOrdering", ordered)
                .put("additionalProperties", false);
    }

    static String buildPrompt(String utterance, String clarificationContext) {
        String lineBreak = System.lineSeparator();
        StringBuilder prompt = new StringBuilder()
                .append("당신은 70대 이상 사용자가 목적지를 말로 정하도록 돕는 MA의 한국어 대화 해석기.").append(lineBreak)
                .append("사용자의 표현, 조사, 띄어쓰기, 발음 인식 오류와 앞선 대화를 함께 해석하세요.").append(lineBreak)
                .append("지역, 장소종류, 사용자가 직접 말한 상호명, 조건을 각각 region, category, placeName, conditions로 분리하세요.").append(lineBreak)
                .append("region에는 발화에 나온 행정구역을 빠짐없이 원래 순서대로 넣고, 말하지 않은 상위 지역은 추측하지 마세요.").append(lineBreak)
                .append("category에는 피부과·시장처럼 사용자가 찾는 장소 종류를 넣고, placeName에는 사용자가 실제 말한 상호명만 넣으세요.").append(lineBreak)
                .append("conditions에는 이번 발화에서 사용자가 직접 표현한 선호·필수·회피·불편·건강상 제약만 넣으세요. 각 항목은 {label, evidence, polarity}이며 evidence는 이번 발화에 실제로 연속해서 나온 짧은 원문 인용이어야 합니다.").append(lineBreak)
                .append("조건의 근거가 되는 요구·불편 표현이 원문에 없으면 conditions에 넣지 마세요. 단순히 장소나 단어가 언급된 것만으로 조건을 추측하지 마세요. 예: ‘오늘 피부과’만으로 오늘 진료 조건을 만들지 말고, ‘오늘 진료하는 피부과’처럼 실제 진료 표현이 있어야 합니다.").append(lineBreak)
                .append("polarity는 REQUIRE 또는 AVOID입니다. REQUIRE는 사용자가 원하는 조건(예: 주차 가능한 곳), AVOID는 피하고 싶은 조건(예: 계단 없는 곳, 계단이 힘든 곳)입니다. ‘주차 필요 없어’처럼 조건을 취소하는 말은 넣지 마세요. ‘계단 없는 곳’은 계단 조건 취소가 아니라 AVOID 조건입니다.").append(lineBreak)
                .append("예: ‘계단이 힘들다’ → label=계단 이용이 어려움, evidence=계단이 힘들다, polarity=AVOID. ‘오래 걷기 어렵다’ → label=보행 불편, evidence=오래 걷기 어렵다, polarity=AVOID. ‘주차가 필요하다’ → label=주차 조건, evidence=주차가 필요하다, polarity=REQUIRE. 장소 데이터로 확인할 수 없는 조건도 원문 근거가 명확할 때만 넣으세요.").append(lineBreak)
                .append("conditions는 앞선 대화 전체를 복사하지 말고 이번 발화에 근거가 있는 항목만 반환하세요. 이전 조건 보존과 취소는 앱이 관리합니다.").append(lineBreak)
                .append("searchQuery는 실제 검색할 장소의 지역, 종류, 상호명만 담으세요. conditions는 검색어에 넣지 마세요.").append(lineBreak)
                .append("전문의·주차처럼 장소 검색 자료로 확인할 수 없는 조건은 사실처럼 판단하지 마세요. 조건은 conditions에만 남기세요.").append(lineBreak)
                .append("예: ‘부산 금정구 서동의 피부과’는 region=‘부산 금정구 서동’, category=‘피부과’, searchQuery=‘부산 금정구 서동 피부과’입니다. ‘서동’만 목적지로 만들면 안 됩니다.").append(lineBreak)
                .append("지역과 장소종류가 이미 함께 주어졌다면 둘 중 하나만 목적지로 오해하지 말고, 바로 검색할 수 있으면 되묻지 마세요.").append(lineBreak)
                .append("사용자가 조건을 정정하면 이전 조건 대신 가장 최근 조건을 사용하세요.").append(lineBreak)
                .append("주소, 상호, 좌표를 만들거나 추측하지 마세요. searchQuery는 실제 장소 검색에 쓸 말만 반환하세요.").append(lineBreak)
                .append("지역이나 의도가 불분명해 실제 장소를 좁힐 수 없으면 needsClarification=true로 한 번에 하나만 짧게 물으세요.").append(lineBreak)
                .append("사용자가 말한 정보만으로 검색할 수 있으면 needsClarification=false, clarificationQuestion은 빈 문자열로 두세요.").append(lineBreak)
                .append("집/가족 집 주소는 저장되어 있지 않으므로 주소를 추측하지 말고 물으세요.").append(lineBreak)
                .append("검색어는 120자, 확인 질문은 160자 이내입니다.").append(lineBreak);
        if (clarificationContext != null && !clarificationContext.trim().isEmpty()) {
            prompt.append("앞선 대화: ").append(clarificationContext.trim()).append(lineBreak);
        }
        return prompt.append("이번 발화: ").append(utterance.trim()).toString();
    }

    static Result parseResponse(String payload) throws JSONException {
        return parseResponse(payload, "");
    }

    static Result parseResponse(String payload, String utterance) throws JSONException {
        return parseResponse(payload, utterance, "");
    }

    static Result parseResponse(String payload, String utterance, String clarificationContext)
            throws JSONException {
        JSONObject root = new JSONObject(payload);
        JSONObject interpreted = root;
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates != null) {
            if (candidates.length() == 0) throw new JSONException("Gemini returned no intent candidate.");
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null || parts.length() == 0) throw new JSONException("Gemini returned no intent text.");
            String text = "";
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                String partText = part == null ? "" : part.optString("text", "");
                if (!partText.isEmpty()) { text = partText; break; }
            }
            if (text.length() > 8192) throw new JSONException("Gemini intent result is too large.");
            interpreted = new JSONObject(text);
        }
        return parseInterpreted(interpreted, utterance, clarificationContext);
    }

    private static Result parseInterpreted(JSONObject interpreted, String utterance,
                                           String clarificationContext) throws JSONException {
        boolean clarification = interpreted.optBoolean("needsClarification", true);
        String question = interpreted.optString("clarificationQuestion", "").trim();
        ConditionReadResult conditionResult = readConditions(interpreted, utterance);
        ArrayList<String> conditions = conditionResult.accepted;
        boolean hasUnverifiedConditions = !conditions.isEmpty();
        String placeName = interpreted.optString("placeName", "").trim();
        boolean genericPlaceName = DestinationQueryGuard.isGenericPlaceType(placeName);
        boolean placeNameGrounded = isSpokenPlaceName(placeName, utterance, clarificationContext);
        boolean hasNamedPlace = !placeName.isEmpty() && placeNameGrounded && !genericPlaceName
                && DestinationQueryGuard.findAreas(placeName).isEmpty();
        String query = buildStructuredSearchQuery(interpreted, placeNameGrounded
                && !genericPlaceName ? placeName : "");
        if (clarification) {
            if (question.length() < 2 || question.length() > 160) {
                throw new JSONException("Invalid clarification question.");
            }
            return new Result("", question, true, hasUnverifiedConditions, hasNamedPlace,
                    conditions, conditionResult.polarities, conditionResult.evidence,
                    conditionResult.unverified);
        }
        if (query.length() < 2 && hasUnverifiedConditions) {
            return new Result("", "어느 동네의 어떤 장소를 찾을까요?", true, true, hasNamedPlace,
                    conditions, conditionResult.polarities, conditionResult.evidence,
                    conditionResult.unverified);
        }
        if (query.length() < 2 || query.length() > 120 || !question.isEmpty()) {
            throw new JSONException("Invalid destination search query.");
        }
        return new Result(query, "", false, hasUnverifiedConditions, hasNamedPlace,
                conditions, conditionResult.polarities, conditionResult.evidence,
                conditionResult.unverified);
    }

    private static String buildStructuredSearchQuery(JSONObject interpreted, String groundedPlaceName)
            throws JSONException {
        String legacyQuery = interpreted.optString("searchQuery", "").trim();
        LinkedHashSet<String> components = new LinkedHashSet<>();
        addComponent(components, interpreted.optString("region", ""), 60);
        String category = interpreted.optString("category", "").trim();
        String distinctPlaceName = groundedPlaceName;
        if (!category.isEmpty() && !distinctPlaceName.isEmpty()) {
            distinctPlaceName = distinctPlaceName.replaceAll(
                    "(?i)" + java.util.regex.Pattern.quote(category), " ");
        }
        addComponent(components, distinctPlaceName, 80);
        addComponent(components, category, 50);
        // Search only for place identity. Conditions such as parking or specialist status
        // are not searchable facts in the local-place API and must never poison the query.
        if (components.isEmpty()) return hasConditions(interpreted) ? "" : legacyQuery;
        StringBuilder query = new StringBuilder();
        for (String component : components) {
            if (query.length() > 0) query.append(' ');
            query.append(component);
        }
        String result = query.toString().trim().replaceAll("\\s+", " ");
        return result.length() <= 120 ? result : "";
    }

    private static boolean hasConditions(JSONObject interpreted) throws JSONException {
        JSONArray conditions = interpreted.optJSONArray("conditions");
        return conditions != null && conditions.length() > 0;
    }

    private static ConditionReadResult readConditions(JSONObject interpreted, String utterance)
            throws JSONException {
        JSONArray conditions = interpreted.optJSONArray("conditions");
        ConditionReadResult result = new ConditionReadResult();
        if (conditions == null) return result;
        if (conditions.length() > 8) throw new JSONException("Too many destination conditions.");
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        LinkedHashMap<String, String> evidenceByLabel = new LinkedHashMap<>();
        LinkedHashSet<String> unverified = new LinkedHashSet<>();
        for (int i = 0; i < conditions.length(); i++) {
            Object value = conditions.opt(i);
            String label;
            String evidence;
            String polarity;
            if (value instanceof JSONObject) {
                JSONObject item = (JSONObject) value;
                label = item.optString("label", "").trim().replaceAll("\\s+", " ");
                evidence = item.optString("evidence", "").trim().replaceAll("\\s+", " ");
                polarity = item.optString("polarity", "").trim().toUpperCase(java.util.Locale.ROOT);
            } else if (value instanceof String) {
                // Older proxy responses carry labels without a source quote. Quarantine them.
                label = ((String) value).trim().replaceAll("\\s+", " ");
                evidence = "";
                polarity = "";
            } else {
                throw new JSONException("Invalid destination condition.");
            }
            if (label.isEmpty()) continue;
            addComponent(new LinkedHashSet<>(), label, 80);
            if (evidence.length() > 120
                    || !("REQUIRE".equals(polarity) || "AVOID".equals(polarity))
                    || !hasVerifiableConditionEvidence(utterance, label, evidence, polarity)) {
                unverified.add(label);
                continue;
            }
            unique.put(label, polarity);
            evidenceByLabel.put(label, evidence);
        }
        result.accepted.addAll(unique.keySet());
        result.polarities.addAll(unique.values());
        for (String label : unique.keySet()) result.evidence.add(evidenceByLabel.get(label));
        result.unverified.addAll(unverified);
        return result;
    }

    private static boolean hasVerifiableConditionEvidence(String utterance, String label,
                                                            String evidence, String polarity) {
        String source = compactEvidence(utterance);
        String quote = compactEvidence(evidence);
        return quote.length() >= 2 && source.contains(quote)
                && !ConditionCancellationMatcher.isNeutralOkayEvidence(evidence)
                && !hasIncidentalConditionLocationContext(utterance, evidence)
                && containsConditionRelation(label, evidence)
                && conditionEvidenceSupportsLabel(label, evidence)
                && conditionEvidenceSupportsPolarity(polarity, evidence);
    }

    private static boolean hasIncidentalConditionLocationContext(String utterance, String evidence) {
        String source = compactEvidence(utterance);
        String quote = compactEvidence(evidence);
        int index = source.indexOf(quote);
        if (index < 0) return false;
        int tailEnd = Math.min(source.length(), index + quote.length() + 40);
        String tail = source.substring(index + quote.length(), tailEnd);
        return tail.matches(".*(?:건물|빌딩|상가)(?:옆|근처|앞|뒤|건너|맞은편).*(?:피부과|병원|의원|약국|시장|가게).*");
    }

    private static boolean conditionEvidenceSupportsPolarity(String polarity, String evidence) {
        String value = compactEvidence(evidence);
        if (isPositiveDoubleNegativeCondition(value)) return "REQUIRE".equals(polarity);
        if ("AVOID".equals(polarity)) {
            String[] avoid = {"없", "피하", "싫", "불편", "힘들", "어렵", "무리", "못", "아파"};
            for (String marker : avoid) if (value.contains(marker)) return true;
            return false;
        }
        String[] require = {"필요", "원해", "원하", "찾", "좋", "편", "해야", "도와",
                "가능", "진료", "이용", "있어야", "가까", "멀리", "괜찮은"};
        for (String marker : require) if (value.contains(marker)) return true;
        return value.contains("있") || value.contains("되");
    }

    private static boolean isPositiveDoubleNegativeCondition(String compact) {
        return compact.contains("없으면안돼") || compact.contains("없으면안되")
                || compact.contains("없어서는안돼") || compact.contains("없어서는안되")
                || compact.contains("빼면안돼") || compact.contains("빼면안되")
                || compact.contains("안되면곤란") || compact.contains("없으면곤란")
                || compact.contains("없으면안됨") || compact.contains("없으면안됩니다")
                || compact.contains("없어선안돼") || compact.contains("없어선안되");
    }

    private static boolean conditionEvidenceSupportsLabel(String label, String evidence) {
        String key = compactEvidence(label);
        String quote = compactEvidence(evidence);
        if (key.contains("주차") || key.contains("발렛") || key.contains("차댈")) {
            return quote.contains("주차") || quote.contains("발렛") || quote.contains("차댈")
                    || quote.contains("세워둘");
        }
        if (key.contains("전문의") || key.contains("전문진료")) return quote.contains("전문의");
        if (key.contains("계단") || key.contains("층계")) {
            return quote.contains("계단") || quote.contains("층계");
        }
        if (key.contains("경사로") || key.contains("오르막")) {
            return quote.contains("경사") || quote.contains("오르막");
        }
        if (key.contains("휠체어")) return quote.contains("휠체어");
        if (key.contains("엘리베이터") || key.contains("승강기")) {
            return quote.contains("엘리베이터") || quote.contains("승강기");
        }
        if (key.contains("장애인화장실") || key.contains("장애인전용화장실")) {
            return quote.contains("장애인화장실") || quote.contains("장애인전용화장실");
        }
        if (key.contains("접근성") || key.contains("장애인편의") || key.contains("이동편의")) {
            return quote.contains("접근성") || quote.contains("장애인")
                    || quote.contains("이동편의") || quote.contains("휠체어")
                    || quote.contains("엘리베이터") || quote.contains("경사로")
                    || quote.contains("계단");
        }
        if (key.contains("보행") || key.contains("이동")) {
            return quote.contains("걷") || quote.contains("보행") || quote.contains("무릎")
                    || quote.contains("다리") || quote.contains("발이아");
        }
        if (key.contains("진료시간") || key.contains("영업시간") || key.contains("진료중")) {
            return quote.contains("진료") || quote.contains("영업시간") || quote.contains("24시간");
        }
        if (key.contains("거리") || key.contains("가까운") || key.contains("멀리")) {
            return quote.contains("가까") || quote.contains("멀리") || quote.contains("거리");
        }
        String lexicalCore = key.replaceAll("(?:추가)?조건|요청|선호|필요|불편|확인|편의|여부|정보|사항|원함|있음", "");
        return lexicalCore.length() >= 2 && quote.contains(lexicalCore);
    }

    private static String compactEvidence(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
    }

    private static boolean containsConditionRelation(String label, String evidence) {
        String value = compactEvidence(evidence);
        if (isPositiveDoubleNegativeCondition(value)) return true;
        if (containsCancellationEvidence(value)) return false;
        String[] markers = {"필요", "원해", "원하", "찾", "좋", "편", "불편", "힘들",
                "어렵", "싫", "아파", "걱정", "부담", "해야", "도와", "가능", "진료",
                "이용", "가까", "멀리", "있어야", "괜찮은"};
        for (String marker : markers) if (value.contains(marker)) return true;
        String key = compactEvidence(label);
        boolean availability = value.contains("있") || value.contains("되") || value.contains("없");
        boolean selectedPlace = value.contains("곳") || value.contains("병원")
                || value.contains("의원") || value.contains("피부과") || value.contains("약국")
                || value.contains("시장") || value.contains("가게");
        boolean incidentalLocation = value.contains("옆") || value.contains("근처")
                || value.contains("앞") || value.contains("뒤") || value.contains("건너")
                || value.contains("맞은편");
        return availability && selectedPlace && !incidentalLocation
                && conditionEvidenceSupportsLabel(label, evidence)
                && (key.contains("주차") || key.contains("발렛") || key.contains("차댈")
                || key.contains("계단") || key.contains("엘리베이터") || key.contains("휠체어")
                || key.contains("장애인") || key.contains("경사") || key.contains("오르막"));
    }

    private static boolean containsCancellationEvidence(String compact) {
        return ConditionCancellationMatcher.hasCancellationText(compact);
    }

    private static final class ConditionReadResult {
        final ArrayList<String> accepted = new ArrayList<>();
        final ArrayList<String> polarities = new ArrayList<>();
        final ArrayList<String> evidence = new ArrayList<>();
        final ArrayList<String> unverified = new ArrayList<>();
    }

    private static void addComponent(LinkedHashSet<String> components, String raw, int maxLength)
            throws JSONException {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) return;
        if (value.length() > maxLength || value.matches("(?s).*\\p{Cntrl}.*")) {
            throw new JSONException("Invalid structured destination component.");
        }
        String normalized = value.replaceAll("[\\p{P}\\s]", "");
        for (String existing : components) {
            if (existing.replaceAll("[\\p{P}\\s]", "").equals(normalized)) return;
        }
        components.add(value);
    }

    private static boolean isSpokenPlaceName(String placeName, String utterance,
                                             String clarificationContext) {
        String normalizedName = normalizeSpokenEvidence(placeName);
        if (normalizedName.isEmpty()) return false;
        String normalizedSpeech = normalizeSpokenEvidence(
                (clarificationContext == null ? "" : clarificationContext) + " "
                        + (utterance == null ? "" : utterance));
        return normalizedSpeech.contains(normalizedName);
    }

    private static String normalizeSpokenEvidence(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) normalized.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return normalized.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void validateInput(String utterance, String clarificationContext) {
        if (utterance == null || utterance.trim().isEmpty() || utterance.length() > 200
                || clarificationContext != null && clarificationContext.length() > 400) {
            throw new IllegalArgumentException("Destination utterance is empty or too long.");
        }
    }

    private static URL endpoint(String baseUrl, boolean debugBuild) throws IOException {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean loopback = host.equals("localhost");
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(scheme.equals("https") || (debugBuild && scheme.equals("http") && loopback))) {
                throw new IllegalArgumentException("Use HTTPS, or localhost HTTP in a debug build.");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return URI.create(uri.toString().replaceAll("/+$", "")
                    + (path.endsWith("/v1/destination/interpret") ? "" : "/v1/destination/interpret")).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Gemini proxy URL.", e);
        } catch (java.net.MalformedURLException e) {
            throw new IOException("Invalid Gemini proxy URL.", e);
        }
    }

    private static String readBounded(InputStream stream, int limit) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("Gemini intent response is too large.");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static final class Result {
        final String searchQuery;
        final String clarificationQuestion;
        final boolean needsClarification;
        final boolean hasUnverifiedConditions;
        final boolean hasNamedPlace;
        final List<String> conditions;
        final List<String> conditionPolarities;
        final List<String> conditionEvidence;
        final List<String> unverifiedConditions;
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification) {
            this(searchQuery, clarificationQuestion, needsClarification, false, false);
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions) {
            this(searchQuery, clarificationQuestion, needsClarification,
                    hasUnverifiedConditions, false);
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions, boolean hasNamedPlace) {
            this(searchQuery, clarificationQuestion, needsClarification,
                    hasUnverifiedConditions, hasNamedPlace, new ArrayList<>());
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions, boolean hasNamedPlace, List<String> conditions) {
            this(searchQuery, clarificationQuestion, needsClarification, hasUnverifiedConditions,
                    hasNamedPlace, conditions, new ArrayList<>(), new ArrayList<>());
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions, boolean hasNamedPlace, List<String> conditions,
               List<String> unverifiedConditions) {
            this(searchQuery, clarificationQuestion, needsClarification, hasUnverifiedConditions,
                    hasNamedPlace, conditions, new ArrayList<>(), unverifiedConditions);
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions, boolean hasNamedPlace, List<String> conditions,
               List<String> conditionPolarities, List<String> unverifiedConditions) {
            this(searchQuery, clarificationQuestion, needsClarification, hasUnverifiedConditions,
                    hasNamedPlace, conditions, conditionPolarities, new ArrayList<>(),
                    unverifiedConditions);
        }
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification,
               boolean hasUnverifiedConditions, boolean hasNamedPlace, List<String> conditions,
               List<String> conditionPolarities, List<String> conditionEvidence,
               List<String> unverifiedConditions) {
            this.searchQuery = searchQuery;
            this.clarificationQuestion = clarificationQuestion;
            this.needsClarification = needsClarification;
            this.hasUnverifiedConditions = hasUnverifiedConditions;
            this.hasNamedPlace = hasNamedPlace;
            this.conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
            this.conditionPolarities = conditionPolarities == null
                    ? new ArrayList<>() : new ArrayList<>(conditionPolarities);
            this.conditionEvidence = conditionEvidence == null
                    ? new ArrayList<>() : new ArrayList<>(conditionEvidence);
            this.unverifiedConditions = unverifiedConditions == null
                    ? new ArrayList<>() : new ArrayList<>(unverifiedConditions);
        }
    }
}
