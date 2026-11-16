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
            return parseResponse(payload);
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
            return parseResponse(payload);
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
                .put("needsClarification", new JSONObject().put("type", "BOOLEAN"))
                .put("clarificationQuestion", new JSONObject().put("type", "STRING"));
        JSONArray ordered = new JSONArray().put("searchQuery").put("needsClarification")
                .put("clarificationQuestion");
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
                .append("searchQuery에는 사용자가 말한 지역, 업종/장소 종류, 고유명사를 모두 보존하세요.").append(lineBreak)
                .append("예: ‘부산 금정구 서동의 피부과’는 ‘부산 금정구 서동 피부과’입니다. ‘서동’만 남기면 안 됩니다.").append(lineBreak)
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
        boolean clarification = interpreted.optBoolean("needsClarification", true);
        String question = interpreted.optString("clarificationQuestion", "").trim();
        String query = interpreted.optString("searchQuery", "").trim();
        if (clarification) {
            if (question.length() < 2 || question.length() > 160) {
                throw new JSONException("Invalid clarification question.");
            }
            return new Result("", question, true);
        }
        if (query.length() < 2 || query.length() > 120 || !question.isEmpty()) {
            throw new JSONException("Invalid destination search query.");
        }
        return new Result(query, "", false);
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
        Result(String searchQuery, String clarificationQuestion, boolean needsClarification) {
            this.searchQuery = searchQuery;
            this.clarificationQuestion = clarificationQuestion;
            this.needsClarification = needsClarification;
        }
    }
}
