package com.siny.mobilebasic;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps explicit areas and place categories from disappearing during AI intent normalization. */
final class DestinationQueryGuard {
    private static final Pattern ADMIN_AREA = Pattern.compile(
            "(?<![가-힣0-9])(?:서울|부산|대구|인천|광주|대전|울산|세종|제주|경기|강원|충북|충남|전북|전남|경북|경남"
                    + "|경기도|강원도|충청북도|충청남도|전라북도|전라남도|경상북도|경상남도|제주도"
                    + "|[가-힣0-9]{1,12}(?:특별시|광역시|특별자치시|특별자치도|자치도|시|구|군|읍|면|동|리))"
                    + "(?=$|\\s|[,，、.]|에|에서|의|으로|로|까지|근처|인근|부근|주변|쪽|일대|요|이요)");
    /** Common speech words that happen to end in Korean administrative-unit syllables. */
    private static final Set<String> NON_AREA_WORDS = new HashSet<>(Arrays.asList(
            "택시", "다시", "혹시", "잠시", "역시", "임시", "실시", "표시", "무시", "말구", "빼구",
            "운동", "행동", "활동", "이동", "감동", "공동", "전동", "노동", "수동", "자동",
            "거리", "다리", "소리", "자리", "요리", "정리", "관리", "수리", "처리", "권리",
            "원리", "논리", "심리", "분리", "편리", "미리", "유리", "머리", "허리", "뿌리",
            "고리", "마리", "추리", "예리", "영리", "출구", "입구", "문구", "가구", "기구",
            "도구", "야구", "배구", "축구", "농구", "연구", "친구", "요구", "라면", "화면",
            "표면", "지면", "내면", "체면", "가면"
    ));
    private static final String[] PLACE_TYPES = {
            "피부과", "정형외과", "산부인과", "소아과", "치과", "안과", "내과", "이비인후과",
            "신경외과", "정신과", "대학병원", "병원", "약국", "보건소", "응급실", "시장",
            "마트", "은행", "주민센터", "우체국", "복지관", "지하철역", "기차역"
    };

    private DestinationQueryGuard() {}

    static String preserveExplicitIntent(String previousContext, String utterance, String aiQuery) {
        if (aiQuery == null || aiQuery.trim().isEmpty()) return "";
        String previous = previousContext == null ? "" : previousContext;
        String current = utterance == null ? "" : utterance;
        LinkedHashSet<String> previousAreas = findAreas(previous);
        LinkedHashSet<String> currentAreas = findAreas(current);
        LinkedHashSet<String> previousTypes = findTypes(previous);
        LinkedHashSet<String> currentTypes = findTypes(current);
        LinkedHashSet<String> requiredAreas = effectiveAreas(previous, current);
        LinkedHashSet<String> requiredTypes = effectiveTypes(previous, current);
        LinkedHashSet<String> requiredNamedTargets = effectiveNamedTargets(current);

        String normalizedAiQuery = aiQuery.trim();
        LinkedHashSet<String> spokenAreas = new LinkedHashSet<>(previousAreas);
        spokenAreas.addAll(currentAreas);
        spokenAreas.removeAll(requiredAreas);
        for (String replacedArea : spokenAreas) normalizedAiQuery = removeTerm(normalizedAiQuery, replacedArea);
        Set<String> replacedAreaCanonical = new HashSet<>();
        for (String replacedArea : spokenAreas) {
            replacedAreaCanonical.add(canonicalAreaToken(replacedArea));
        }
        if (!replacedAreaCanonical.isEmpty()) {
            for (String aiArea : findAreas(normalizedAiQuery)) {
                if (replacedAreaCanonical.contains(canonicalAreaToken(aiArea))) {
                    normalizedAiQuery = removeTerm(normalizedAiQuery, aiArea);
                }
            }
        }
        LinkedHashSet<String> spokenTypes = new LinkedHashSet<>(previousTypes);
        spokenTypes.addAll(currentTypes);
        spokenTypes.removeAll(requiredTypes);
        for (String replacedType : spokenTypes) normalizedAiQuery = removeTerm(normalizedAiQuery, replacedType);
        normalizedAiQuery = normalizedAiQuery.replaceAll("\\s+", " ").trim();
        // Put the explicit geography in the speaker's original order. Appending a missing
        // city after an AI-returned district can turn "서울 종로구" into "종로구 서울".
        String remainder = removeAreaTokens(normalizedAiQuery, requiredAreas);
        remainder = prependMissingNamedTargets(remainder, requiredNamedTargets);
        StringBuilder query = new StringBuilder();
        for (String area : requiredAreas) {
            if (query.length() > 0) query.append(' ');
            query.append(area);
        }
        if (!remainder.isEmpty()) {
            if (query.length() > 0) query.append(' ');
            query.append(remainder);
        }
        String normalizedQuery = normalize(query.toString());
        for (String term : requiredTypes) {
            if (!normalizedQuery.contains(normalize(term))) {
                query.append(' ').append(term);
                normalizedQuery = normalize(query.toString());
            }
        }
        String result = query.toString().trim().replaceAll("\\s+", " ");
        return result.length() <= 120 ? result : "";
    }

    private static LinkedHashSet<String> effectiveNamedTargets(String utterance) {
        String current = utterance == null ? "" : utterance;
        if (!isCorrection(current)) return new LinkedHashSet<>();
        String tail = correctionTail(current);
        if (tail.isEmpty()) return new LinkedHashSet<>();
        LinkedHashSet<String> correctedTargets = findNamedTargets(tail);
        if (!correctedTargets.isEmpty()) return correctedTargets;
        if (countCorrections(current) != 1) return new LinkedHashSet<>();
        return findNamedTargets(correctionPrefix(current));
    }

    private static int countCorrections(String text) {
        Matcher matcher = Pattern.compile(
                "말고요?|말구요?|빼고|빼구|아니고요?|아니에요|아니라|대신|틀렸")
                .matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static LinkedHashSet<String> findNamedTargets(String phrase) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(phrase);
        if (!parsed.isUsable()) return targets;
        String destination = parsed.destination;
        destination = removeAreaTokens(destination, findAreas(destination));
        for (String type : findTypes(destination)) {
            destination = removeTerm(destination, type);
        }
        destination = destination.replaceAll("(?:말고요?|말구요?|빼고|빼구|아니고요?|아니라|대신|아니에요|틀렸)", " ");
        destination = destination.replaceAll("(?<!\\S)(?:있는|있어요|있나요|있죠|근처|주변|쪽|일대)(?=\\s|$)", " ")
                .replaceAll("(?<!\\S)(?:에|에서|의|으로|로)(?=\\s|$)", " ");
        for (String token : destination.trim().split("\\s+")) {
            String value = token.replaceAll("^[,，、.]+|[,，、.]+$", "").trim();
            if (!value.isEmpty()) targets.add(value);
        }
        return targets;
    }

    private static String prependMissingNamedTargets(String query, Set<String> targets) {
        if (targets == null || targets.isEmpty()) return query;
        Set<String> existing = new HashSet<>();
        if (query != null) {
            for (String token : query.trim().split("\\s+")) {
                existing.add(normalize(token.replaceAll("^[,，、.]+|[,，、.]+$", "")));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String target : targets) {
            if (!existing.contains(normalize(target))) missing.add(target);
        }
        if (missing.isEmpty()) return query == null ? "" : query;
        String prefix = String.join(" ", missing);
        return query == null || query.trim().isEmpty() ? prefix : prefix + " " + query.trim();
    }

    static LinkedHashSet<String> findAreas(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = ADMIN_AREA.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!NON_AREA_WORDS.contains(candidate)) found.add(candidate);
        }
        return found;
    }

    /** Areas explicitly stated by the user, with the same correction semantics as query guarding. */
    static LinkedHashSet<String> effectiveAreas(String previousContext, String utterance) {
        String previous = previousContext == null ? "" : previousContext;
        String current = utterance == null ? "" : utterance;
        String trailingTarget = trailingCorrectionTarget(current);
        if (!trailingTarget.isEmpty()) return findAreas(trailingTarget);
        LinkedHashSet<String> previousAreas = findAreas(previous);
        LinkedHashSet<String> currentAreas = findAreas(current);
        LinkedHashSet<String> correctedAreas = findAreas(correctionTail(current));
        if (currentAreas.isEmpty() && DestinationSpeechHypothesisPolicy
                .hasIndependentNamedTarget(current)) {
            return new LinkedHashSet<>();
        }
        if (isCorrection(current)) {
            if (!correctedAreas.isEmpty()) {
                LinkedHashSet<String> result = new LinkedHashSet<>();
                LinkedHashSet<String> oldAreas = new LinkedHashSet<>(previousAreas);
                oldAreas.addAll(findAreas(correctionPrefix(current)));
                int mostSpecificReplacement = Integer.MAX_VALUE;
                for (String area : correctedAreas) {
                    mostSpecificReplacement = Math.min(mostSpecificReplacement, areaLevel(area));
                }
                for (String area : oldAreas) {
                    if (areaLevel(area) < mostSpecificReplacement) addUniqueArea(result, area);
                }
                for (String area : correctedAreas) addUniqueArea(result, area);
                return result;
            }
            if (lastCorrectionTargetIsArea(correctionPrefix(current))) return new LinkedHashSet<>();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String area : previousAreas) addUniqueArea(result, area);
        for (String currentArea : currentAreas) {
            int currentLevel = areaLevel(currentArea);
            boolean conflictsAtSameLevel = false;
            for (String previousArea : result) {
                if (areaLevel(previousArea) == currentLevel
                        && !canonicalAreaToken(previousArea)
                        .equals(canonicalAreaToken(currentArea))) {
                    conflictsAtSameLevel = true;
                    break;
                }
            }
            if (conflictsAtSameLevel) {
                for (String previousArea : new ArrayList<>(result)) {
                    if (areaLevel(previousArea) >= currentLevel) result.remove(previousArea);
                }
            }
        }
        for (String area : currentAreas) addUniqueArea(result, area);
        return result;
    }

    private static void addUniqueArea(LinkedHashSet<String> areas, String candidate) {
        String canonical = canonicalAreaToken(candidate);
        for (String existing : areas) {
            if (canonicalAreaToken(existing).equals(canonical)) return;
        }
        areas.add(candidate);
    }

    private static LinkedHashSet<String> effectiveTypes(String previousContext, String utterance) {
        String previous = previousContext == null ? "" : previousContext;
        String current = utterance == null ? "" : utterance;
        String trailingTarget = trailingCorrectionTarget(current);
        if (!trailingTarget.isEmpty()) return findTypes(trailingTarget);
        LinkedHashSet<String> previousTypes = findTypes(previous);
        LinkedHashSet<String> currentTypes = findTypes(current);
        LinkedHashSet<String> correctedTypes = findTypes(correctionTail(current));
        if (!currentTypes.isEmpty()) {
            if (!previousTypes.isEmpty() && Collections.disjoint(previousTypes, currentTypes)) {
                previousTypes.clear();
            } else if (!currentTypes.contains("병원")) {
                previousTypes.remove("병원");
            }
        }
        if (isCorrection(current)) {
            if (!correctedTypes.isEmpty()) return correctedTypes;
            if (!findAreas(correctionTail(current)).isEmpty()) {
                LinkedHashSet<String> result = new LinkedHashSet<>(previousTypes);
                result.addAll(currentTypes);
                return result;
            }
            if (lastCorrectionTargetIsType(correctionPrefix(current))) return new LinkedHashSet<>();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(previousTypes);
        result.addAll(currentTypes);
        return result;
    }

    /**
     * A recognition phrase containing multiple unrelated place categories needs a full
     * restatement. Picking one category would turn a missed correction word into a bad search.
     */
    static boolean hasUnresolvedPlaceTypeConflict(String previousContext, String utterance) {
        return distinctSpecificPlaceTypes(String.join(" ",
                effectiveTypes(previousContext, utterance))).size() > 1;
    }

    /** A correction marker with no usable replacement is not a destination request. */
    static boolean hasIncompleteCorrection(String utterance) {
        String current = utterance == null ? "" : utterance;
        if (!isCorrection(current)) return false;
        String replacement = correctionTail(current);
        boolean hasReplacementType = !findTypes(replacement).isEmpty();
        boolean hasReplacementNamedTarget = DestinationSpeechHypothesisPolicy
                .hasIndependentNamedTarget(replacement);
        if (lastCorrectionTargetIsType(correctionPrefix(current))
                && !hasReplacementType && !hasReplacementNamedTarget) return true;
        if (replacement.isEmpty()) return true;
        if (!findAreas(replacement).isEmpty() || !findTypes(replacement).isEmpty()) return false;
        return !hasReplacementNamedTarget;
    }

    static int areaLevel(String area) {
        if (area == null) return 3;
        String value = area.trim();
        if (value.matches(".*(?:특별시|광역시|특별자치시|특별자치도|자치도|도|시)$")
                || value.matches("서울|부산|대구|인천|광주|대전|울산|세종|제주|경기|강원|충북|충남|전북|전남|경북|경남")) {
            return 1;
        }
        if (value.endsWith("구") || value.endsWith("군")) return 2;
        return 3;
    }

    private static String correctionPrefix(String text) {
        String value = text == null ? "" : text;
        Matcher leading = Pattern.compile("^\\s*(?:아니요|아뇨|아니)[,，\\s]+")
                .matcher(value);
        if (leading.find()) return "";
        Matcher matcher = Pattern.compile("말고요?|말구요?|빼고|빼구|아니고|아니라|대신|아니에요|틀렸").matcher(value);
        int start = -1;
        while (matcher.find()) start = matcher.start();
        return start < 0 ? "" : value.substring(0, start).trim();
    }

    private static boolean lastCorrectionTargetIsArea(String prefix) {
        String normalized = normalize(prefix);
        int areaPosition = -1;
        for (String area : findAreas(prefix)) {
            areaPosition = Math.max(areaPosition, normalized.lastIndexOf(normalize(area)));
        }
        int typePosition = -1;
        for (String type : findTypes(prefix)) {
            typePosition = Math.max(typePosition, normalized.lastIndexOf(normalize(type)));
        }
        return areaPosition >= 0 && areaPosition > typePosition;
    }

    private static boolean lastCorrectionTargetIsType(String prefix) {
        String normalized = normalize(prefix);
        int areaPosition = -1;
        for (String area : findAreas(prefix)) {
            areaPosition = Math.max(areaPosition, normalized.lastIndexOf(normalize(area)));
        }
        int typePosition = -1;
        for (String type : findTypes(prefix)) {
            typePosition = Math.max(typePosition, normalized.lastIndexOf(normalize(type)));
        }
        return typePosition >= 0 && typePosition > areaPosition;
    }

    static LinkedHashSet<String> findTypes(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String normalized = normalize(text);
        for (String type : PLACE_TYPES) {
            if (normalized.contains(normalize(type))) found.add(type);
        }
        return found;
    }

    static LinkedHashSet<String> distinctSpecificPlaceTypes(String text) {
        LinkedHashSet<String> types = findTypes(text);
        if (types.size() <= 1 || !types.contains("병원")) return types;
        types.remove("병원");
        boolean allRemainingAreHospitalSpecialties = true;
        for (String type : types) {
            if (!isHospitalSpecialty(type)) {
                allRemainingAreHospitalSpecialties = false;
                break;
            }
        }
        if (!allRemainingAreHospitalSpecialties) types.add("병원");
        return types;
    }

    private static boolean isHospitalSpecialty(String type) {
        return "피부과".equals(type) || "정형외과".equals(type) || "산부인과".equals(type)
                || "소아과".equals(type) || "치과".equals(type) || "안과".equals(type)
                || "내과".equals(type) || "이비인후과".equals(type)
                || "신경외과".equals(type) || "정신과".equals(type)
                || "대학병원".equals(type);
    }

    static String canonicalAreaToken(String value) {
        String normalized = normalize(value).replace("특별", "").replace("광역", "").replace("자치", "");
        switch (normalized) {
            case "서울": return "서울시";
            case "부산": return "부산시";
            case "대구": return "대구시";
            case "인천": return "인천시";
            case "광주": return "광주시";
            case "대전": return "대전시";
            case "울산": return "울산시";
            case "세종": return "세종시";
            case "제주": return "제주도";
            case "경기": return "경기도";
            case "강원": return "강원도";
            case "충북": return "충청북도";
            case "충남": return "충청남도";
            case "전북": return "전라북도";
            case "전남": return "전라남도";
            case "경북": return "경상북도";
            case "경남": return "경상남도";
            default: return normalized;
        }
    }

    static String normalizeAddressAreaEvidence(String value) {
        return normalize(value).replace("특별", "").replace("광역", "").replace("자치", "")
                .replace("충북", "충청북도").replace("충남", "충청남도")
                .replace("전북", "전라북도").replace("전남", "전라남도")
                .replace("경북", "경상북도").replace("경남", "경상남도");
    }

    static boolean hasConflictingAreas(Set<String> areas) {
        if (areas == null || areas.size() < 2) return false;
        Map<Integer, String> byLevel = new HashMap<>();
        for (String area : areas) {
            int level = areaLevel(area);
            String canonical = canonicalAreaToken(area);
            String existing = byLevel.putIfAbsent(level, canonical);
            if (existing != null && !existing.equals(canonical)) return true;
        }
        return false;
    }

    static boolean isGenericPlaceType(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        for (String type : PLACE_TYPES) {
            if (normalized.equals(normalize(type))) return true;
        }
        return false;
    }

    private static String correctionTail(String text) {
        Matcher leading = Pattern.compile("^\\s*(?:아니요|아뇨|아니)[,，\\s]+").matcher(text);
        if (leading.find()) return text.substring(leading.end()).trim();
        Matcher matcher = Pattern.compile("말고요?|말구요?|빼고|빼구|아니고|아니라|대신|아니에요|틀렸").matcher(text);
        int end = -1;
        while (matcher.find()) end = matcher.end();
        return end < 0 ? text : text.substring(end).trim();
    }

    private static String trailingCorrectionTarget(String text) {
        String value = text == null ? "" : text.trim();
        if (!Pattern.compile("(?:말고요?|말구요?|빼고|빼구|아니고요?|아니라|아니에요|아니예요|아닌데|대신|틀렸)[.!?。！？…\\s]*$")
                .matcher(value).find()) return "";
        return DestinationPhraseParser.destinationAfterCorrection(value);
    }

    private static boolean isCorrection(String text) {
        return Pattern.compile("^\\s*(?:아니요|아뇨|아니)[,，\\s]+.*").matcher(text).matches()
                || Pattern.compile("(?s).*(?:말고요?|말구요?|빼고|빼구|아니고|아니라|대신|아니에요|틀렸).*" )
                .matcher(text).matches();
    }

    private static String removeTerm(String query, String term) {
        return query.replaceAll(Pattern.quote(term), " ");
    }

    static String removeAreaTokens(String query, Set<String> areas) {
        if (query == null || query.trim().isEmpty() || areas.isEmpty()) return query == null ? "" : query.trim();
        Set<String> normalizedAreas = new HashSet<>();
        for (String area : areas) {
            normalizedAreas.add(normalize(area));
            normalizedAreas.add(canonicalAreaToken(area));
        }
        StringBuilder remaining = new StringBuilder();
        for (String token : query.trim().split("\\s+")) {
            String comparable = token.replaceAll("^[,，、.]+|[,，、.]+$", "");
            if (isExplicitAreaToken(normalize(comparable), normalizedAreas)) continue;
            if (remaining.length() > 0) remaining.append(' ');
            remaining.append(token);
        }
        return remaining.toString().trim();
    }

    private static boolean isExplicitAreaToken(String token, Set<String> areas) {
        for (String area : areas) {
            if (canonicalAreaToken(token).equals(area)) return true;
            if (token.equals(area)) return true;
            if (!token.startsWith(area)) continue;
            String suffix = token.substring(area.length());
            if (suffix.matches("(?:에서|에|의|으로|로|까지|근처|인근|부근|주변|쪽|일대|이요|요)")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN).replaceAll("[\\p{P}\\s]", "");
    }
}
