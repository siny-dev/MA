package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Keeps same-city, same-neighborhood candidates from being silently treated as one place. */
final class DestinationDistrictClarification {
    enum UnmatchedAction { ASK_ONCE, STOP }

    private final ArrayList<String> districts = new ArrayList<>();
    private boolean clarificationAsked;

    void begin(List<String> candidateAddresses) {
        clear();
        districts.addAll(DestinationDistrictAmbiguity.districtsFromAddresses(candidateAddresses));
        clarificationAsked = false;
    }

    boolean isActive() {
        return districts.size() > 1;
    }

    List<String> districts() {
        return new ArrayList<>(districts);
    }

    String matchDistrict(String utterance) {
        LinkedHashSet<String> spoken = DestinationQueryGuard.findAreas(utterance);
        String match = null;
        for (String area : spoken) {
            if (DestinationQueryGuard.areaLevel(area) != 2) continue;
            String canonical = DestinationQueryGuard.canonicalAreaToken(area);
            for (String district : districts) {
                if (!canonical.equals(DestinationQueryGuard.canonicalAreaToken(district))) continue;
                if (match != null && !match.equals(district)) return "";
                match = district;
            }
        }
        return match == null ? "" : match;
    }

    static boolean addressMatchesDistrict(String address, String district) {
        String expected = DestinationQueryGuard.canonicalAreaToken(district);
        for (String area : DestinationQueryGuard.findAreas(address)) {
            if (DestinationQueryGuard.areaLevel(area) == 2
                    && expected.equals(DestinationQueryGuard.canonicalAreaToken(area))) return true;
        }
        return false;
    }

    UnmatchedAction onUnmatchedAnswer() {
        if (!isActive() || clarificationAsked) return UnmatchedAction.STOP;
        clarificationAsked = true;
        return UnmatchedAction.ASK_ONCE;
    }

    void clear() {
        districts.clear();
        clarificationAsked = false;
    }

    static String question(String requestedAreaQuery, List<String> districts) {
        ArrayList<String> neighborhoods = new ArrayList<>();
        for (String area : DestinationQueryGuard.findAreas(requestedAreaQuery)) {
            if (area.matches(".*(?:동|읍|면|리)$")) neighborhoods.add(area);
        }
        String target = neighborhoods.isEmpty() ? "검색한 동네" : String.join(" ", neighborhoods);
        return target + " 검색 결과가 여러 구에 있어요. " + String.join(" 또는 ", districts)
                + " 중 어느 구인지 말씀해 주세요.";
    }
}
