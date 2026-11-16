package com.siny.mobilebasic;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps explicit areas and place categories from disappearing during AI intent normalization. */
final class DestinationQueryGuard {
    private static final Pattern ADMIN_AREA = Pattern.compile(
            "(?<![가-힣0-9])(?:서울|부산|대구|인천|광주|대전|울산|세종|제주|경기|강원|충북|충남|전북|전남|경북|경남"
                    + "|경기도|강원도|충청북도|충청남도|전라북도|전라남도|경상북도|경상남도|제주도"
                    + "|[가-힣0-9]{1,12}(?:특별시|광역시|특별자치시|특별자치도|자치도|시|구|군|읍|면|동|리))"
                    + "(?=$|\\s|[,，、.]|에|에서|의|으로|로|까지|근처|인근|부근|주변|쪽|일대|역|요|이요)");
    /** Common speech words that happen to end in Korean administrative-unit syllables. */
    private static final Set<String> NON_AREA_WORDS = new HashSet<>(Arrays.asList(
            "택시", "다시", "혹시", "잠시", "역시", "임시", "실시", "표시", "무시",
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
        if (!currentTypes.isEmpty() && !currentTypes.contains("병원")) previousTypes.remove("병원");
        boolean corrected = isCorrection(current);
        String correctionTail = correctionTail(current);
        LinkedHashSet<String> tailAreas = findAreas(correctionTail);
        LinkedHashSet<String> tailTypes = findTypes(correctionTail);
        LinkedHashSet<String> requiredAreas = new LinkedHashSet<>();
        if (corrected && !tailAreas.isEmpty()) requiredAreas.addAll(tailAreas);
        else { requiredAreas.addAll(previousAreas); requiredAreas.addAll(currentAreas); }
        LinkedHashSet<String> requiredTypes = new LinkedHashSet<>();
        if (corrected && !tailTypes.isEmpty()) requiredTypes.addAll(tailTypes);
        else { requiredTypes.addAll(previousTypes); requiredTypes.addAll(currentTypes); }

        String normalizedAiQuery = aiQuery.trim();
        if (corrected && !tailAreas.isEmpty()) {
            for (String oldArea : previousAreas) normalizedAiQuery = removeTerm(normalizedAiQuery, oldArea);
        }
        if (corrected && !tailTypes.isEmpty()) {
            for (String oldType : previousTypes) normalizedAiQuery = removeTerm(normalizedAiQuery, oldType);
        }
        normalizedAiQuery = normalizedAiQuery.replaceAll("\\s+", " ").trim();
        // Put the explicit geography in the speaker's original order. Appending a missing
        // city after an AI-returned district can turn "서울 종로구" into "종로구 서울".
        String remainder = removeAreaTokens(normalizedAiQuery, requiredAreas);
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

    private static LinkedHashSet<String> findAreas(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = ADMIN_AREA.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!NON_AREA_WORDS.contains(candidate)) found.add(candidate);
        }
        return found;
    }

    private static LinkedHashSet<String> findTypes(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String normalized = normalize(text);
        for (String type : PLACE_TYPES) {
            if (normalized.contains(normalize(type))) found.add(type);
        }
        return found;
    }

    private static String correctionTail(String text) {
        Matcher leading = Pattern.compile("^\\s*(?:아니요|아뇨|아니)[,，\\s]+").matcher(text);
        if (leading.find()) return text.substring(leading.end()).trim();
        Matcher matcher = Pattern.compile("말고|아니고|아니라|대신|아니에요|틀렸").matcher(text);
        int end = -1;
        while (matcher.find()) end = matcher.end();
        return end < 0 ? text : text.substring(end).trim();
    }

    private static boolean isCorrection(String text) {
        return Pattern.compile("^\\s*(?:아니요|아뇨|아니)[,，\\s]+.*").matcher(text).matches()
                || Pattern.compile("(?s).*(?:말고|아니고|아니라|대신|아니에요|틀렸).*" )
                .matcher(text).matches();
    }

    private static String removeTerm(String query, String term) {
        return query.replaceAll(Pattern.quote(term), " ");
    }

    private static String removeAreaTokens(String query, Set<String> areas) {
        if (query == null || query.trim().isEmpty() || areas.isEmpty()) return query == null ? "" : query.trim();
        Set<String> normalizedAreas = new HashSet<>();
        for (String area : areas) normalizedAreas.add(normalize(area));
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
