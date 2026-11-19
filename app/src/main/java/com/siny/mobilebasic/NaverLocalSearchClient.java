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
    private static final int ACCEPT = 0;
    private static final int REJECT_GENERAL = 1;
    private static final int REJECT_AREA = 1 << 1;
    private static final int REJECT_CATEGORY = 1 << 2;
    private static final String[] CHECKED_CATEGORIES = {
            "이비인후과", "정형외과", "산부인과", "피부과", "소아과", "치과", "안과", "내과",
            "대학병원", "약국", "보건소", "병원", "시장", "마트", "은행", "주민센터", "우체국"
    };

    private NaverLocalSearchClient() {}

    static Result search(String clientId, String clientSecret, String query)
            throws IOException, JSONException {
        return search(clientId, clientSecret, query, query);
    }

    static Result search(String clientId, String clientSecret, String query, String requestedAreaQuery)
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
            return parseResponse(payload, query, requestedAreaQuery);
        } finally {
            connection.disconnect();
        }
    }

    static Result parseResponse(String payload, String query) throws JSONException {
        return parseResponse(payload, query, query);
    }

    static Result parseResponse(String payload, String query, String requestedAreaQuery)
            throws JSONException {
        if (payload == null || payload.length() > MAX_RESPONSE_BYTES) {
            throw new JSONException("NAVER local search response is missing or too large.");
        }
        JSONObject root = new JSONObject(payload);
        JSONArray items = root.optJSONArray("items");
        Map<String, Place> unique = new LinkedHashMap<>();
        int rawCount = items == null ? 0 : items.length();
        int rejectedByRequestedConstraints = 0;
        int rejectedByArea = 0;
        int rejectedByCategory = 0;
        if (items == null) return new Result(new ArrayList<>(), root.optInt("total", 0), 0);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = cleanTitle(item.optString("title", ""));
            String roadAddress = item.optString("roadAddress", "").trim();
            String parcelAddress = item.optString("address", "").trim();
            String address = firstNonEmpty(roadAddress, parcelAddress);
            String category = item.optString("category", "").trim();
            double longitude = parseCoordinate(item.optString("mapx", ""));
            double latitude = parseCoordinate(item.optString("mapy", ""));
            int decision = validatePlace(name, address, roadAddress, parcelAddress, category,
                    latitude, longitude, query, requestedAreaQuery);
            if (decision != ACCEPT) {
                if ((decision & (REJECT_AREA | REJECT_CATEGORY)) != 0) {
                    rejectedByRequestedConstraints++;
                }
                if ((decision & REJECT_AREA) != 0) rejectedByArea++;
                if ((decision & REJECT_CATEGORY) != 0) rejectedByCategory++;
                continue;
            }
            String key = normalize(name) + "|" + normalize(address);
            unique.putIfAbsent(key, new Place(name, address, category, latitude, longitude,
                    item.optString("link", "").trim()));
        }
        return new Result(new ArrayList<>(unique.values()),
                Math.max(rawCount, root.optInt("total", rawCount)), rejectedByRequestedConstraints,
                rejectedByArea, rejectedByCategory);
    }

    private static int validatePlace(String name, String address, String roadAddress,
                                     String parcelAddress, String category,
                                     double latitude, double longitude, String query,
                                     String requestedAreaQuery) {
        if (name.length() < 2 || name.length() > 160
                || !DestinationAddressMatcher.isUsableAddress(address)
                || !DestinationCoordinates.isWithinSupportedArea(latitude, longitude)
                || !hasSpecificAddress(address)) return REJECT_GENERAL;
        String normalizedQuery = normalize(query);
        String listing = normalize(name + " " + category);
        int rejection = matchesRequestedAreas(requestedAreaQuery, roadAddress, parcelAddress)
                ? ACCEPT : REJECT_AREA;
        for (String expected : CHECKED_CATEGORIES) {
            String normalizedExpected = normalize(expected);
            if (!normalizedQuery.contains(normalizedExpected)) continue;
            if (expected.equals("병원") && hasMoreSpecificMedicalCategory(normalizedQuery)) continue;
            if (!listing.contains(normalizedExpected)) rejection |= REJECT_CATEGORY;
        }
        return rejection;
    }

    private static boolean matchesRequestedAreas(String query, String roadAddress,
                                                 String parcelAddress) {
        java.util.LinkedHashSet<String> parcelAreas = DestinationQueryGuard.findAreas(parcelAddress);
        java.util.LinkedHashSet<String> roadAreas = DestinationQueryGuard.findAreas(roadAddress);
        for (String area : DestinationQueryGuard.findAreas(query)) {
            String expected = DestinationQueryGuard.canonicalAreaToken(area);
            if (expected.length() < 2) {
                return false;
            }
            boolean matched = containsCanonicalArea(parcelAreas, expected);
            // A road address can substantiate city and district names, but a road suffix such as
            // "서동로" must not impersonate the requested administrative neighborhood "서동".
            if (!matched && DestinationQueryGuard.areaLevel(area) < 3) {
                matched = containsCanonicalArea(roadAreas, expected);
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean containsCanonicalArea(java.util.Set<String> areas, String expected) {
        for (String area : areas) {
            if (expected.equals(DestinationQueryGuard.canonicalAreaToken(area))) return true;
        }
        return false;
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
        final int rawResultCount;
        final int rejectedByRequestedConstraints;
        final int rejectedByArea;
        final int rejectedByCategory;
        Result(List<Place> places) { this(places, places == null ? 0 : places.size(), 0, 0, 0); }
        Result(List<Place> places, int rawResultCount, int rejectedByRequestedConstraints) {
            this(places, rawResultCount, rejectedByRequestedConstraints, 0, 0);
        }
        Result(List<Place> places, int rawResultCount, int rejectedByRequestedConstraints,
               int rejectedByArea, int rejectedByCategory) {
            this.places = places;
            this.rawResultCount = Math.max(0, rawResultCount);
            this.rejectedByRequestedConstraints = Math.max(0, rejectedByRequestedConstraints);
            this.rejectedByArea = Math.max(0, rejectedByArea);
            this.rejectedByCategory = Math.max(0, rejectedByCategory);
        }
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
