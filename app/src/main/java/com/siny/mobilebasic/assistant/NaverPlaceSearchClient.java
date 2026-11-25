package com.siny.mobilebasic.assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** NAVER API HUB local search. Only returned address and WGS84 coordinates become candidates. */
public final class NaverPlaceSearchClient {
    private static final String ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/local";

    public List<PlaceCandidate> search(String query, String keyId, String key) throws IOException {
        if (query == null || query.trim().isEmpty()) throw new IOException("검색할 목적지가 비어 있습니다.");
        if (empty(keyId) || empty(key)) throw new IOException("NAVER API HUB 설정이 필요합니다.");
        String url = ENDPOINT + "?query=" + URLEncoder.encode(query.trim(), "UTF-8")
                + "&display=5&start=1&sort=random&format=json";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-NCP-APIGW-API-KEY-ID", keyId.trim());
            connection.setRequestProperty("X-NCP-APIGW-API-KEY", key.trim());
            int status = connection.getResponseCode();
            InputStream bodyStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = read(bodyStream);
            if (status < 200 || status >= 300) throw new IOException("NAVER 장소 검색에 실패했습니다 (HTTP " + status + ").");
            JSONArray items;
            try { items = new JSONObject(body).optJSONArray("items"); }
            catch (JSONException e) { throw new IOException("NAVER 장소 검색 응답을 읽지 못했습니다.", e); }
            List<PlaceCandidate> candidates = new ArrayList<>();
            if (items == null) return candidates;
            for (int i = 0; i < items.length(); i++) {
                try {
                    PlaceCandidate candidate = PlaceCandidate.fromNaver(items.getJSONObject(i));
                    if (!containsEquivalent(candidates, candidate)) candidates.add(candidate);
                } catch (JSONException | RuntimeException ignored) {
                    // An incomplete result without a verified coordinate/address cannot be navigated to.
                }
            }
            return candidates;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean containsEquivalent(List<PlaceCandidate> items, PlaceCandidate candidate) {
        for (PlaceCandidate item : items) {
            if (item.name.equals(candidate.name) && item.displayAddress().equals(candidate.displayAddress())) return true;
        }
        return false;
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1024 * 1024) throw new IOException("NAVER 검색 응답이 너무 큽니다.");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
