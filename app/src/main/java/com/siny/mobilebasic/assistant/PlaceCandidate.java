package com.siny.mobilebasic.assistant;

import org.json.JSONObject;

/** A place selected from NAVER's real local-search response. */
public final class PlaceCandidate {
    public final String name;
    public final String category;
    public final String address;
    public final String roadAddress;
    public final double latitude;
    public final double longitude;

    public PlaceCandidate(String name, String category, String address, String roadAddress,
                          double latitude, double longitude) {
        this.name = clean(name);
        this.category = clean(category);
        this.address = clean(address);
        this.roadAddress = clean(roadAddress);
        this.latitude = latitude;
        this.longitude = longitude;
        if (this.name.isEmpty() || (this.address.isEmpty() && this.roadAddress.isEmpty())
                || !isKoreanCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Place needs a name, address, and valid Korean coordinates");
        }
    }

    public static PlaceCandidate fromNaver(JSONObject item) {
        String name = item.optString("title", "").replaceAll("<[^>]*>", "");
        return new PlaceCandidate(name, item.optString("category", ""),
                item.optString("address", ""), item.optString("roadAddress", ""),
                parseCoordinate(item.optString("mapy", "")),
                parseCoordinate(item.optString("mapx", "")));
    }

    public String displayAddress() {
        return !roadAddress.isEmpty() ? roadAddress : address;
    }

    /** Short locality for speech; the complete address stays visible on screen. */
    public String spokenArea() {
        String locality = lastMatch(address, "[가-힣0-9]+(?:동|읍|면|리|가)");
        if (locality.isEmpty()) locality = lastMatch(displayAddress(), "[가-힣0-9]+(?:동|읍|면|리|가)");
        if (locality.isEmpty()) locality = lastMatch(address, "[가-힣0-9]+(?:구|군|시)");
        if (locality.isEmpty()) locality = lastMatch(displayAddress(), "[가-힣0-9]+(?:구|군|시)");
        return locality;
    }

    public String confirmationText() {
        String area = spokenArea();
        return area.isEmpty() ? name + " 맞나요?" : area + "에 있는 " + name + " 맞나요?";
    }

    public String confirmationDetails() {
        return confirmationText() + "\n주소: " + displayAddress();
    }

    public static boolean isKoreanCoordinate(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= 33.0 && lat <= 39.5 && lng >= 124.0 && lng <= 132.0;
    }

    private static double parseCoordinate(String value) {
        try {
            double coordinate = Double.parseDouble(value.trim());
            // NAVER Local Search returns WGS84 values as integer degrees × 10^7.
            // Accept decimal-degree responses too, so both documented response formats are safe.
            if (Math.abs(coordinate) > 180.0 && Math.abs(coordinate) <= 1_800_000_000.0)
                coordinate /= 10_000_000.0;
            return coordinate;
        }
        catch (RuntimeException e) { return Double.NaN; }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String lastMatch(String source, String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(expression).matcher(source);
        String result = "";
        while (matcher.find()) result = matcher.group();
        return result;
    }
}
