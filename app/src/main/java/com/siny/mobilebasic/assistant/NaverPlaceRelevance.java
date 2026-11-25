package com.siny.mobilebasic.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic guardrails around model ranking of NAVER Local Search results. */
public final class NaverPlaceRelevance {
    private static final Pattern ADMIN_AREA = Pattern.compile(
            "[가-힣0-9]+(?:특별자치시|특별시|광역시|자치시|특별자치도|자치도|도|시|군|구|읍|면|동|리)|[가-힣]+[0-9]+가");
    private static final Pattern ADMIN_TOKEN = Pattern.compile("[가-힣0-9]+");
    private static final List<String> SHORT_CITIES = Arrays.asList(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private static final List<String> MEDICAL_CATEGORIES = Arrays.asList(
            "피부과", "정형외과", "이비인후과", "산부인과", "소아청소년과", "소아과",
            "치과", "안과", "내과", "비뇨기과", "신경외과", "정신건강의학과", "약국", "병원");

    private NaverPlaceRelevance() { }

    public static String preflight(String userRequest, String searchQuery) {
        String request = safe(userRequest) + " " + safe(searchQuery);
        String compactRequest = compact(request);
        if (compactRequest.contains("전문의")) return "qualification-unverified";
        String category = requiredMedicalCategory(compactRequest);
        if (areaTokens(request).isEmpty() && (!category.isEmpty()
                || compactRequest.contains("근처") || compactRequest.contains("주변")
                || compactRequest.contains("가까운") || compactRequest.contains("가까이")))
            return "area-required";
        return "";
    }

    public static Result filter(String userRequest, String searchQuery, List<PlaceCandidate> places) {
        String request = safe(userRequest) + " " + safe(searchQuery);
        String compactRequest = compact(request);

        // NAVER Local Search provides place/category/address data, not physician credential proof.
        String preflight = preflight(userRequest, searchQuery);
        if (!preflight.isEmpty()) return Result.rejected(preflight);

        String requiredMedicalCategory = requiredMedicalCategory(compactRequest);
        List<String> requiredAreas = areaTokens(request);
        List<Integer> eligible = new ArrayList<>();
        boolean categoryMatched = false;
        for (int i = 0; i < places.size(); i++) {
            PlaceCandidate place = places.get(i);
            if (!requiredMedicalCategory.isEmpty() && !hasMedicalCategory(place, requiredMedicalCategory)) continue;
            categoryMatched = true;
            if (!hasAllAreas(place, requiredAreas)) continue;
            eligible.add(i);
        }
        if (eligible.isEmpty()) {
            if (!categoryMatched) return Result.rejected("category-not-verified");
            if (!requiredAreas.isEmpty()) return Result.rejected("area-not-matched");
            return Result.rejected("no-matching-place");
        }
        return Result.accepted(eligible);
    }

    private static String requiredMedicalCategory(String request) {
        for (String category : MEDICAL_CATEGORIES) {
            if (request.contains(compact(category))) return category;
        }
        return "";
    }

    private static boolean hasMedicalCategory(PlaceCandidate place, String requested) {
        String metadata = compact(place.category + " " + place.name);
        if (requested.equals("소아과") || requested.equals("소아청소년과"))
            return metadata.contains("소아과") || metadata.contains("소아청소년과");
        if (requested.equals("병원")) return metadata.contains("병원") || metadata.contains("의원");
        return metadata.contains(compact(requested));
    }

    private static List<String> areaTokens(String request) {
        Set<String> areas = new LinkedHashSet<>();
        Matcher matcher = ADMIN_AREA.matcher(request);
        while (matcher.find()) addArea(areas, matcher.group());
        String compact = compact(request);
        for (String city : SHORT_CITIES) {
            if (compact.contains(city)) addArea(areas, city);
        }
        return new ArrayList<>(areas);
    }

    private static void addArea(Set<String> areas, String value) {
        String canonical = canonicalArea(value);
        if (!canonical.isEmpty()) areas.add(canonical);
    }

    private static String canonicalArea(String area) {
        for (String city : SHORT_CITIES) {
            if (area.equals(city) || area.startsWith(city + "특별시") || area.startsWith(city + "광역시")
                    || area.startsWith(city + "특별자치시") || area.startsWith(city + "특별자치도")) return city;
        }
        return compact(area);
    }

    private static boolean hasAllAreas(PlaceCandidate place, List<String> areas) {
        if (areas.isEmpty()) return true;
        String address = place.address + " " + place.roadAddress;
        List<String> addressTokens = new ArrayList<>();
        Matcher matcher = ADMIN_TOKEN.matcher(address);
        while (matcher.find()) addressTokens.add(matcher.group());
        String compactAddress = compact(address);
        for (String required : areas) {
            if (SHORT_CITIES.contains(required)) {
                if (!compactAddress.contains(required)) return false;
                continue;
            }
            boolean found = false;
            for (String token : addressTokens) {
                String normalized = compact(token);
                if (normalized.equals(required)) { found = true; break; }
                // Permit road names such as 서동로 when the requested neighborhood is 서동.
                if (required.endsWith("동") && normalized.equals(required + "로")) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String compact(String value) {
        return safe(value).replaceAll("[^가-힣0-9]", "");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public static final class Result {
        public final List<Integer> indexes;
        public final String rejectionReason;

        private Result(List<Integer> indexes, String rejectionReason) {
            this.indexes = Collections.unmodifiableList(new ArrayList<>(indexes));
            this.rejectionReason = rejectionReason;
        }

        private static Result accepted(List<Integer> indexes) { return new Result(indexes, ""); }
        private static Result rejected(String reason) { return new Result(Collections.emptyList(), reason); }
        public boolean accepted() { return !indexes.isEmpty(); }
    }
}
