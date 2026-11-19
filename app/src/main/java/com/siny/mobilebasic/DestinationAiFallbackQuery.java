package com.siny.mobilebasic;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;

/** Builds a conservative literal-search query when destination AI interpretation is unavailable. */
final class DestinationAiFallbackQuery {
    private DestinationAiFallbackQuery() {}

    static Result fromSpokenPhrase(String previousContext, String utterance,
                                   String localFallbackQuery) {
        LinkedHashSet<String> areas = DestinationQueryGuard.effectiveAreas(
                previousContext, utterance);
        if (areas.isEmpty() || DestinationQueryGuard.hasConflictingAreas(areas)
                || !hasParentForLocalArea(areas)) return null;

        String query = DestinationQueryGuard.preserveExplicitIntent(
                previousContext, utterance, localFallbackQuery)
                .replaceAll("(?<!\\S)(?:있는|있어요|있나요|있죠)(?=\\s|$)", " ")
                .replaceAll("\\s+", " ").trim();
        if (query.isEmpty() || !hasOnePlaceType(query)) return null;

        String contextNotice = buildContextNotice(previousContext, utterance, query, areas);
        return new Result(query, String.join(" ", areas), contextNotice);
    }

    static String blockedPrompt(String previousContext, String utterance,
                                String localFallbackQuery) {
        LinkedHashSet<String> areas = DestinationQueryGuard.effectiveAreas(
                previousContext, utterance);
        if (DestinationQueryGuard.hasConflictingAreas(areas)) {
            return "동네가 여러 곳으로 들려요. 찾으실 동네 한 곳을 말씀해 주세요.";
        }
        if (!hasParentForLocalArea(areas)) {
            return "그 동네는 여러 지역에 있을 수 있어요. 시나 구도 함께 말씀해 주세요. 예를 들어 부산 금정구 서동처럼요.";
        }
        String query = DestinationQueryGuard.preserveExplicitIntent(
                previousContext, utterance, localFallbackQuery).trim();
        if (query.isEmpty() || query.length() > 120) {
            return "동네와 장소 종류를 짧게 말씀해 주세요. 예를 들어 부산 금정구 서동 피부과처럼요.";
        }
        LinkedHashSet<String> types = DestinationQueryGuard.findTypes(query);
        if (types.isEmpty()) {
            return "어떤 장소를 찾을까요? 동네와 장소 종류를 함께 말씀해 주세요. 예를 들어 부산 금정구 서동 피부과처럼요.";
        }
        if (!hasOnePlaceType(query)) {
            LinkedHashSet<String> previousTypes = DestinationQueryGuard.findTypes(previousContext);
            LinkedHashSet<String> currentTypes = DestinationQueryGuard.findTypes(utterance);
            if (previousTypes.size() == 1 && currentTypes.size() == 1
                    && !previousTypes.equals(currentTypes)) {
                return "앞서 말씀하신 " + previousTypes.iterator().next() + "와 이번에 말씀하신 "
                        + currentTypes.iterator().next() + " 중 어느 곳을 찾을까요? 한 곳만 말씀해 주세요.";
            }
            types = DestinationQueryGuard.distinctSpecificPlaceTypes(query);
            String choices = String.join("와 ", types);
            if (!choices.isEmpty()) {
                return "장소가 여러 가지로 들려요: " + choices
                        + ". 찾으실 장소 종류 한 가지만 말씀해 주세요.";
            }
            return "장소 종류가 여러 가지로 들려요. 찾으실 종류 한 가지만 말씀해 주세요.";
        }
        return "어느 동네에서 찾을까요? 동네와 장소 종류를 함께 말씀해 주세요.";
    }

    private static boolean hasParentForLocalArea(LinkedHashSet<String> areas) {
        boolean hasSpecificLocalArea = false;
        boolean hasBroaderArea = false;
        for (String area : areas) {
            if (DestinationQueryGuard.areaLevel(area) >= 3) hasSpecificLocalArea = true;
            else hasBroaderArea = true;
        }
        return !hasSpecificLocalArea || hasBroaderArea;
    }

    private static String buildContextNotice(String previousContext, String utterance,
                                             String query, LinkedHashSet<String> effectiveAreas) {
        LinkedHashSet<String> currentAreas = DestinationQueryGuard.findAreas(utterance);
        List<String> reusedDetails = new ArrayList<>();
        for (String area : effectiveAreas) {
            boolean spokenNow = false;
            String canonical = DestinationQueryGuard.canonicalAreaToken(area);
            for (String currentArea : currentAreas) {
                if (canonical.equals(DestinationQueryGuard.canonicalAreaToken(currentArea))) {
                    spokenNow = true;
                    break;
                }
            }
            if (!spokenNow) reusedDetails.add(area);
        }

        LinkedHashSet<String> currentTypes = DestinationQueryGuard.findTypes(utterance);
        for (String type : DestinationQueryGuard.findTypes(query)) {
            if (!currentTypes.contains(type)) reusedDetails.add(type);
        }
        if (reusedDetails.isEmpty() || previousContext == null || previousContext.trim().isEmpty()) {
            return "";
        }
        LinkedHashSet<String> currentDetails = new LinkedHashSet<>(currentAreas);
        currentDetails.addAll(currentTypes);
        String notice = "앞서 말씀하신 내용은 ‘" + String.join(" ", reusedDetails) + "’";
        if (currentDetails.isEmpty()) return notice + "이고, 그 내용을 사용해 ";
        return notice + "이고, 이번 말씀은 ‘" + String.join(" ", currentDetails)
                + "’예요. 두 내용을 합쳐 ";
    }

    private static boolean hasOnePlaceType(String query) {
        LinkedHashSet<String> types = DestinationQueryGuard.distinctSpecificPlaceTypes(query);
        // A specialty is a more specific form of the generic hospital category.
        // Other combinations can represent multiple destinations, so literal fallback
        // must not guess which one the user intended.
        return types.size() == 1;
    }

    static final class Result {
        final String query;
        final String requestedArea;
        final String contextNotice;

        Result(String query, String requestedArea, String contextNotice) {
            this.query = query;
            this.requestedArea = requestedArea;
            this.contextNotice = contextNotice;
        }
    }
}
