package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Flags a candidate set that spans districts when the spoken request named only a city and dong. */
final class DestinationDistrictAmbiguity {
    private DestinationDistrictAmbiguity() {}

    static String reminder(String requestedAreaQuery, List<String> candidateAddresses) {
        if (!requiresUnspokenDistrict(requestedAreaQuery) || candidateAddresses == null) return "";

        LinkedHashSet<String> candidateDistricts = districts(candidateAddresses);
        return candidateDistricts.size() > 1
                ? "검색 결과가 여러 구에 있어요. 주소에 나온 구 이름을 먼저 확인해 주세요."
                : "";
    }

    static String confirmationNote(String requestedAreaQuery, String candidateAddress) {
        if (!requiresUnspokenDistrict(requestedAreaQuery)) return "";
        LinkedHashSet<String> candidateDistricts = districts(java.util.Collections.singletonList(
                candidateAddress));
        if (candidateDistricts.size() != 1) return "";
        return "구를 말씀하지 않으셔서, 검색된 주소의 "
                + candidateDistricts.iterator().next() + "를 확인해 주세요. ";
    }

    private static boolean requiresUnspokenDistrict(String requestedAreaQuery) {
        LinkedHashSet<String> requestedAreas = DestinationQueryGuard.findAreas(requestedAreaQuery);
        boolean hasDistrict = false;
        boolean hasCity = false;
        boolean hasNeighborhood = false;
        for (String area : requestedAreas) {
            int level = DestinationQueryGuard.areaLevel(area);
            hasCity |= level == 1;
            hasDistrict |= level == 2;
            hasNeighborhood |= isNeighborhood(area);
        }
        return hasCity && hasNeighborhood && !hasDistrict;
    }

    static LinkedHashSet<String> districts(List<String> candidateAddresses) {
        LinkedHashSet<String> candidateDistricts = new LinkedHashSet<>();
        for (String address : candidateAddresses) {
            for (String area : DestinationQueryGuard.findAreas(address)) {
                if (DestinationQueryGuard.areaLevel(area) == 2) {
                    candidateDistricts.add(DestinationQueryGuard.canonicalAreaToken(area));
                }
            }
        }
        return candidateDistricts;
    }

    static List<String> districtsFromAddresses(List<String> candidateAddresses) {
        return new ArrayList<>(districts(candidateAddresses));
    }

    private static boolean isNeighborhood(String area) {
        return area != null && area.matches(".*(?:동|읍|면|리)$");
    }
}
