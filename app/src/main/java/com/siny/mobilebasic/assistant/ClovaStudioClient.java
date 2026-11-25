package com.siny.mobilebasic.assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;

/** Uses HyperCLOVA X to interpret speech and rank only NAVER-returned places. */
public final class ClovaStudioClient {
    private static final String ENDPOINT = "https://clovastudio.stream.ntruss.com/v3/chat-completions/HCX-005";
    private final String apiKey;

    public ClovaStudioClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("CLOVA Studio API key is required");
        this.apiKey = apiKey.trim();
    }

    public Interpretation interpret(String transcript) throws IOException {
        return interpret("", transcript);
    }

    /** Reinterprets a follow-up using the active request instead of treating it as a new destination. */
    public Interpretation interpret(String previousRequest, String transcript) throws IOException {
        String prompt = "사용자는 70대 이상 한국어 사용자입니다. 음성 인식 결과에서 목적지 요청 의도를 이해하세요. "
                + "조사 생략, 말버릇, 반복, 일부 음성 인식 오타를 감안해 자연스러운 뜻을 이해하되, 피부과·전문의·지역 같은 명시 조건은 보존하세요. "
                + "주소나 장소를 만들어내지 말고, 목적지 검색이 가능할 때만 실제 검색어를 제안하세요. "
                + "JSON만 반환: {\"kind\":\"search|clarify|conversation\",\"searchQuery\":\"\",\"reply\":\"짧은 한국어 안내\"}. "
                + "예: '부산 금정구 서동에 있는 피부과' -> kind search, searchQuery '부산 금정구 서동 피부과'. "
                + "행정구역만 말하고 업종/장소가 빠졌으면 반복 확인 대신 무엇을 찾는지 한 번 질문하세요. "
                + "이전 요청과 추가 발언이 함께 있으면 이전의 지역·장소 조건을 유지하고 새 조건을 더해 전체 검색어를 만드세요. "
                + "다만 사용자가 새 지역이나 새 목적지를 분명히 말하면 이전 목적지는 버리고 새 목적지로 바꾸세요. "
                + "사용자가 '그 조건은 빼고', '그냥 가까운 곳', '전문의 말고'처럼 조건을 분명히 완화하면 그 조건을 이전 요청에서 제거하세요. "
                + "조건이 서로 충돌하거나 지역·장소 종류가 여전히 모호하면 kind=clarify로 한 가지 핵심 질문만 하세요. "
                + "검색 결과가 실제 장소 확인 전이라면 정답인 것처럼 말하지 말고, 후보를 찾는 단계임을 짧게 안내하세요.";
        JSONObject value = ask(prompt, interpretationInput(previousRequest, transcript).toString());
        String kind = value.optString("kind", "clarify").trim().toLowerCase();
        String query = value.optString("searchQuery", "").trim();
        String reply = value.optString("reply", "").trim();
        if (!kind.equals("search") && !kind.equals("clarify") && !kind.equals("conversation"))
            throw new IOException("AI가 목적지 요청을 이해하지 못했습니다. 다시 말씀해 주세요.");
        if (query.length() > 160) throw new IOException("AI 검색어가 허용 길이를 넘었습니다.");
        if (kind.equals("search") && query.isEmpty())
            throw new IOException("AI가 찾을 장소를 정하지 못했습니다. 동네와 장소를 함께 말씀해 주세요.");
        return new Interpretation(kind, query, reply);
    }

    static JSONObject interpretationInput(String previousRequest, String transcript) throws IOException {
        JSONObject input = new JSONObject();
        try {
            input.put("previous_request", previousRequest == null ? "" : previousRequest.trim());
            input.put("latest_user_utterance", transcript == null ? "" : transcript.trim());
            return input;
        } catch (JSONException impossible) {
            throw new IOException("대화 내용을 처리하지 못했습니다.", impossible);
        }
    }

    public Ranking rank(String transcript, String query, List<PlaceCandidate> candidates) throws IOException {
        NaverPlaceRelevance.Result relevance = NaverPlaceRelevance.filter(transcript, query, candidates);
        if (!relevance.accepted()) return new Ranking("none", -1, relevance.rejectionReason, relevance.indexes);
        JSONArray places = new JSONArray();
        for (int i : relevance.indexes) {
            PlaceCandidate item = candidates.get(i);
            JSONObject place = new JSONObject();
            try {
                place.put("index", i);
                place.put("name", item.name);
                place.put("category", item.category);
                place.put("address", item.displayAddress());
                places.put(place);
            } catch (JSONException impossible) {
                throw new IOException("검색 결과를 처리하지 못했습니다.", impossible);
            }
        }
        String prompt = "목적지 요청과 실제 NAVER 지역검색 결과를 비교해 고르세요. "
                + "목록에 없는 곳을 만들지 마세요. 지역과 업종 조건이 맞는 단 하나가 분명할 때만 action=confirm으로 0부터 시작하는 index를 반환하세요. "
                + "전문의·자격·진료 가능 여부는 이 장소 목록만으로 확인할 수 없으니 추정하지 마세요. "
                + "둘 이상이 그럴듯하면 action=choose, 결과가 목적과 맞지 않으면 action=none. "
                + "JSON만 반환: {\"action\":\"confirm|choose|none\",\"index\":-1}.";
        JSONObject input = new JSONObject();
        try {
            input.put("user_request", transcript);
            input.put("ai_search_query", query);
            input.put("naver_results", places);
        } catch (JSONException impossible) {
            throw new IOException("검색 결과를 처리하지 못했습니다.", impossible);
        }
        JSONObject value = ask(prompt, input.toString());
        String action = value.optString("action", "none").trim().toLowerCase();
        int index = value.optInt("index", -1);
        if (!action.equals("confirm") && !action.equals("choose") && !action.equals("none"))
            return new Ranking("none", -1, "no-matching-place", relevance.indexes);
        if (action.equals("confirm") && (index < 0 || index >= candidates.size()))
            return new Ranking("none", -1, "no-matching-place", relevance.indexes);
        if (action.equals("confirm") && !relevance.indexes.contains(index))
            return new Ranking("none", -1, "no-matching-place", relevance.indexes);
        if (action.equals("choose") && relevance.indexes.size() < 2)
            return new Ranking("none", -1, "no-matching-place", relevance.indexes);
        return new Ranking(action, index, action.equals("none") ? "no-matching-place" : "", relevance.indexes);
    }

    /** Lets HyperCLOVA understand natural spoken confirmation phrases without granting it navigation control. */
    public String classifyConfirmation(String request, PlaceCandidate place, String utterance) throws IOException {
        if (place == null) throw new IOException("확인 중인 장소가 없습니다.");
        String prompt = "사용자는 70대 이상 한국어 사용자입니다. 직전 질문은 특정 목적지가 맞는지 확인하는 질문입니다. "
                + "최신 발화의 뜻만 분류하세요. 명시적으로 그 장소로 가겠다는 말이면 yes, 거부면 no, 다른 장소나 조건 수정이면 correction, 망설임·불명확이면 unclear입니다. "
                + "'네 거기로 가자', '거기로 부탁해요' 같은 자연스러운 수락은 yes입니다. '아니, 다른 곳'은 correction입니다. "
                + "질문, 침묵, 맞장구만으로 수락을 추측하지 마세요. JSON만 반환: {\"intent\":\"yes|no|correction|unclear\"}.";
        JSONObject input = new JSONObject();
        try {
            input.put("destination_request", request == null ? "" : request);
            JSONObject candidate = new JSONObject();
            candidate.put("name", place.name);
            candidate.put("address", place.displayAddress());
            input.put("confirmed_candidate", candidate);
            input.put("latest_user_utterance", utterance == null ? "" : utterance);
        } catch (JSONException impossible) {
            throw new IOException("대화 내용을 처리하지 못했습니다.", impossible);
        }
        String intent = ask(prompt, input.toString()).optString("intent", "unclear").trim().toLowerCase();
        if (!intent.equals("yes") && !intent.equals("no") && !intent.equals("correction")) return "unclear";
        return intent;
    }

    private JSONObject ask(String systemPrompt, String userText) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("X-NCP-CLOVASTUDIO-REQUEST-ID", UUID.randomUUID().toString());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            JSONArray messages = new JSONArray();
            messages.put(message("system", systemPrompt));
            messages.put(message("user", userText));
            JSONObject body = new JSONObject();
            body.put("messages", messages);
            body.put("topP", 0.8);
            body.put("topK", 0);
            body.put("maxCompletionTokens", 1024);
            body.put("temperature", 0.1);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String response = read(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw new IOException("HyperCLOVA X 응답 오류 (HTTP " + status + ").");
            String content = new JSONObject(response).getJSONObject("result")
                    .getJSONObject("message").getString("content");
            return parseJsonObject(content);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("HyperCLOVA X 응답을 읽지 못했습니다.", e);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject message(String role, String content) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private static JSONObject parseJsonObject(String content) throws IOException {
        if (content == null) throw new IOException("HyperCLOVA X 응답이 비어 있습니다.");
        String text = content.trim();
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("HyperCLOVA X가 구조화된 답변을 반환하지 않았습니다.");
        try { return new JSONObject(text.substring(start, end + 1)); }
        catch (JSONException e) { throw new IOException("HyperCLOVA X 답변 형식이 올바르지 않습니다.", e); }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1024 * 1024) throw new IOException("AI 응답이 너무 큽니다.");
                out.write(buffer, 0, count);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    public static final class Interpretation {
        public final String kind, searchQuery, reply;
        Interpretation(String kind, String query, String reply) {
            this.kind = kind; this.searchQuery = query; this.reply = reply;
        }
    }

    public static final class Ranking {
        public final String action;
        public final int index;
        public final String rejectionReason;
        public final List<Integer> eligibleIndexes;
        Ranking(String action, int index, String rejectionReason, List<Integer> eligibleIndexes) {
            this.action = action;
            this.index = index;
            this.rejectionReason = rejectionReason;
            this.eligibleIndexes = new java.util.ArrayList<>(eligibleIndexes);
        }
    }
}
