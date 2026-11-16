package com.siny.mobilebasic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Searches actual NAVER local listings and returns NAVER's address and WGS84 coordinates. */
final class NaverLocalSearchClient {
    private static final String ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/local";
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final String[] CHECKED_CATEGORIES = {
            "이비인후과", "정형외과", "산부인과", "피부과", "소아과", "치과", "안과", "내과",
            "대학병원", "약국", "보건소", "병원", "시장", "마트", "은행", "주민센터", "우체국"
    };

    private NaverLocalSearchClient() {}

    static Result search(String clientId, String clientSecret, String query)
            throws IOException, JSONException {
        if (!validCredentials(clientId, clientSecret)) {
            throw new IOException("NAVER API HUB credentials are missing or invalid.");
        }
        URL url = buildUrl(query);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-NCP-APIGW-API-KEY-ID", clientId.trim());
        connection.setRequestProperty("X-NCP-APIGW-API-KEY", clientSecret.trim());
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readBounded(stream, MAX_RESPONSE_BYTES);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("NAVER local search unavailable (HTTP " + status + ").");
            }
            return parseResponse(payload, query);
        } finally {
            connection.disconnect();
        }
    }

    static Result parseResponse(String payload, String query) throws JSONException {
        if (payload == null || payload.length() > MAX_RESPONSE_BYTES) {
            throw new JSONException("NAVER local search response is missing or too large.");
        }
        JSONObject root = new JSONObject(payload);
        JSONArray items = root.optJSONArray("items");
        Map<String, Place> unique = new LinkedHashMap<>();
        if (items == null) return new Result(new ArrayList<>());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = cleanTitle(item.optString("title", ""));
            String address = firstNonEmpty(item.optString("roadAddress", ""),
                    item.optString("address", ""));
            String category = item.optString("category", "").trim();
            double longitude = parseCoordinate(item.optString("mapx", ""));
            double latitude = parseCoordinate(item.optString("mapy", ""));
            if (!isValidPlace(name, address, category, latitude, longitude, query)) continue;
            String key = normalize(name) + "|" + normalize(address);
            unique.putIfAbsent(key, new Place(name, address, category, latitude, longitude,
                    item.optString("link", "").trim()));
        }
        return new Result(new ArrayList<>(unique.values()));
    }

    private static boolean isValidPlace(String name, String address, String category,
                                        double latitude, double longitude, String query) {
        if (name.length() < 2 || name.length() > 160
                || !DestinationAddressMatcher.isUsableAddress(address)
                || !DestinationCoordinates.isWithinSupportedArea(latitude, longitude)
                || !hasSpecificAddress(address)) return false;
        String normalizedQuery = normalize(query);
        String listing = normalize(name + " " + category);
        for (String expected : CHECKED_CATEGORIES) {
            String normalizedExpected = normalize(expected);
            if (!normalizedQuery.contains(normalizedExpected)) continue;
            if (expected.equals("병원") && hasMoreSpecificMedicalCategory(normalizedQuery)) continue;
            if (!listing.contains(normalizedExpected)) return false;
        }
        return true;
    }

    private static boolean hasMoreSpecificMedicalCategory(String query) {
        return query.contains(normalize("피부과")) || query.contains(normalize("정형외과"))
                || query.contains(normalize("산부인과")) || query.contains(normalize("소아과"))
                || query.contains(normalize("치과")) || query.contains(normalize("안과"))
                || query.contains(normalize("내과")) || query.contains(normalize("이비인후과"))
                || query.contains(normalize("대학병원"));
    }

    static boolean validCredentials(String clientId, String clientSecret) {
        return validCredential(clientId) && validCredential(clientSecret);
    }

    private static boolean validCredential(String value) {
        return value != null && value.trim().length() >= 4 && value.trim().length() <= 256
                && !value.trim().matches(".*\\s.*");
    }

    static URL buildUrl(String query) throws IOException {
        if (query == null || query.trim().length() < 2 || query.trim().length() > 120) {
            throw new IllegalArgumentException("Local search query is empty or too long.");
        }
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
        return new URL(ENDPOINT + "?query=" + encoded + "&display=5&start=1&sort=random&format=json");
    }

    private static double parseCoordinate(String value) {
        if (value == null || value.trim().isEmpty()) return Double.NaN;
        try {
            double coordinate = Double.parseDouble(value.trim());
            // NAVER's WGS84 feed uses 1e-7 fixed-point integers in some responses.
            if (Math.abs(coordinate) > 180.0) coordinate /= 10_000_000.0;
            return coordinate;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean hasSpecificAddress(String address) {
        return address.matches("(?s).*(?:[가-힣0-9·-]+(?:대로|로|길)\\s*\\d+(?:-\\d+)?"
                + "|[가-힣0-9]+(?:동|읍|면|리)\\s*\\d+(?:-\\d+)?).*" );
    }

    private static String cleanTitle(String value) {
        return value == null ? "" : value.replaceAll("<[^>]*>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim()
                : second == null ? "" : second.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\s]", "");
    }

    private static String readBounded(InputStream stream, int limit) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("NAVER local search response is too large.");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static final class Result {
        final List<Place> places;
        Result(List<Place> places) { this.places = places; }
    }

    static final class Place {
        final String name;
        final String address;
        final String category;
        final double latitude;
        final double longitude;
        final String link;
        Place(String name, String address, String category, double latitude, double longitude, String link) {
            this.name = name;
            this.address = address;
            this.category = category;
            this.latitude = latitude;
            this.longitude = longitude;
            this.link = link;
        }
    }
}
