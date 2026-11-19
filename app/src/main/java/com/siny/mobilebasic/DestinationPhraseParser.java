package com.siny.mobilebasic;

import java.util.Locale;
import java.util.regex.Pattern;

/** Extracts a literal destination phrase; it never invents an address or coordinates. */
public final class DestinationPhraseParser {
    private static final Pattern TAXI_PREFIX = Pattern.compile(
            "^(?:택시(?:를)?\\s*(?:타고\\s*싶(?:어요|어|습니다|다)|타고|타서|로|불러(?:서|가지고|줘서|\\s*주세요|\\s*줘)?|"
                    + "잡아(?:서|\\s*주세요|\\s*줘)?|호출(?:해서|해\\s*주세요|해주세요|해줘|해)?)"
                    + "|(?i:카카오\\s*(?:t|티|택시))(?:\\s*(?:타고\\s*싶(?:어요|어|습니다|다)|타고|타서|로|불러(?:서|\\s*주세요|\\s*줘)?|잡아(?:서|\\s*주세요|\\s*줘)?|호출(?:해\\s*주세요|해주세요|해)?))?"
                    + "|콜택시(?:\\s*(?:불러(?:서|\\s*주세요|\\s*줘)?|호출(?:해\\s*주세요|해주세요|해)?))?)\\s*");
    private static final Pattern TAXI_SUFFIX = Pattern.compile(
            "[,，]?\\s*(?:"
                    + "택시(?:를)?(?:\\s*(?:타고\\s*싶(?:어요|어|습니다|다)|타고|타서|로))?"
                    + "|택시(?:를)?\\s*(?:불러(?:서|가지고|줘서|\\s*주세요|\\s*줘)|"
                    + "잡아(?:서|\\s*주세요|\\s*줘)|호출(?:해서|해\\s*주세요|해주세요|해줘|해))"
                    + "|(?i:카카오\\s*(?:t|티|택시))(?:\\s*(?:타고\\s*싶(?:어요|어|습니다|다)|타고|타서|로|불러(?:서|\\s*주세요|\\s*줘)|잡아(?:서|\\s*주세요|\\s*줘)|호출해\\s*주세요|호출해주세요|호출해))?"
                    + "|콜택시(?:\\s*(?:불러(?:서|\\s*주세요|\\s*줘)|호출(?:해\\s*주세요|해주세요|해)))?"
                    + ")\\s*$");
    private static final String NEGATED_TAXI_CLAUSE =
            "(?:택시|카카오\\s*(?:t|티|택시)|콜택시)(?:는|은|를)?\\s*(?:말고|아니고|대신|싫(?:고|어서|어|어요|습니다|다)|"
                    + "안\\s*(?:타고|타서|불러(?:\\s*줘)?|부르고|호출해(?:\\s*줘)?|호출하고)"
                    + "(?:\\s*(?:싶(?:어요|어|습니다|다)|않아|않아요|않습니다|않다))?|"
                    + "타지\\s*(?:말고|마|마요|않아|않아요)|"
                    + "(?:부르지|호출하지)\\s*(?:말고|마|마요|않아|않아요)|"
                    + "(?:타고|타서|부르고|호출하고)\\s*싶지\\s*(?:않아|않아요|않습니다|않다)?)";
    private static final Pattern NEGATED_TAXI_PREFIX = Pattern.compile(
            "^" + NEGATED_TAXI_CLAUSE + "\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_TAXI_SUFFIX = Pattern.compile(
            "[,，]?\\s*" + NEGATED_TAXI_CLAUSE + "\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_TAXI_ANY = Pattern.compile(
            NEGATED_TAXI_CLAUSE, Pattern.CASE_INSENSITIVE);
    private static final String NEGATED_TRANSIT_CLAUSE =
            "(?:대중교통|버스|지하철|전철|기차)(?:는|은|를)?\\s*(?:말고|아니고|대신|싫(?:고|어서|어|어요|습니다|다))";
    private static final Pattern NEGATED_TRANSIT_PREFIX = Pattern.compile(
            "^" + NEGATED_TRANSIT_CLAUSE + "\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_TRANSIT_SUFFIX = Pattern.compile(
            "[,，]?\\s*" + NEGATED_TRANSIT_CLAUSE + "\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSIT_PREFIX = Pattern.compile(
            "^(?:대중교통(?:으로|을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서)?|버스(?:를?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|를?\\s*타서|로)|지하철(?:을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서|로)|전철(?:을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서|로)|기차(?:를?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|를?\\s*타서|로))\\s*");
    private static final Pattern TRANSIT_SUFFIX = Pattern.compile(
            "[,，]?\\s*(?:대중교통(?:으로|을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서)?|버스(?:를?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|를?\\s*타서|로)|지하철(?:을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서|로)|전철(?:을?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|을?\\s*타서|로)|기차(?:를?\\s*타고(?:\\s*싶(?:어요|어|습니다|다))?|를?\\s*타서|로))\\s*$");
    private static final Pattern TRAILING_DESTINATION_PARTICLE = Pattern.compile("(?:으로|로|에|까지|쪽)\\s*$");
    private static final Pattern REQUEST_COMMAND_PREFIX = Pattern.compile(
            "^(?:(?:좀|한\\s*번(?:만)?)\\s*)?(?:찾아(?:\\s*봐|\\s*줘)|검색(?:을)?\\s*해(?:\\s*(?:봐|줘|주세요))?|"
                    + "알아\\s*봐|안내해(?:\\s*(?:줘|주세요))?|보여\\s*줘|데려가(?:\\s*줘)?|데려다\\s*줘|모셔다\\s*줘)\\s*[,，、:：]?\\s*");
    private static final Pattern TRAILING_SPOKEN_FILLER = Pattern.compile(
            "(?:\\s+(?:말이야|말이에요|말인데|그거))+$");
    private static final Pattern TRAILING_POLITE_FACILITY_ENDING = Pattern.compile(
            "(피부과|내과|안과|치과|정형외과|신경외과|이비인후과|소아과|산부인과|비뇨기과|"
                    + "한의원|약국|병원|의원|보건소)(?:이에요|예요|이요|요)$");
    private static final Pattern AMBIGUOUS_ACTION_INSIDE_DESTINATION = Pattern.compile(
            "(?:^|\\s)[가-힣0-9]+(?:특별시|광역시|시|군|구|읍|면|동|리)\\s+"
                    + "(?:가자|가요|가줘|데려가|데려다줘|모셔다줘|찾아봐|검색해줘|안내해줘)\\s+"
                    + "(?=(?:피부과|내과|안과|치과|정형외과|신경외과|이비인후과|소아과|산부인과|비뇨기과|"
                    + "한의원|약국|병원|의원|보건소))");
    private static final Pattern CORRECTION_MARKER = Pattern.compile(
            "(?:아니고요|아니에요|아니예요|아니고|아니라|아닌데|말고요|말구요|말고|말구|빼고|빼구|대신)");
    private static final Pattern ADMIN_AREA_TOKEN = Pattern.compile(
            "[가-힣0-9]+(?:특별시|광역시|시|군|구|읍|면|동|리)");
    private static final String GENERIC_REFERENCE_TARGET =
            "(?:곳|쪽|집|동네|근처|아래|데|후보|병원|약국|치과|피부과|정형외과|안과|내과|한의원|보건소|"
                    + "식당|음식점|카페|마트|시장|은행|가게|장소|주소)";
    private static final Pattern UNRESOLVED_DESTINATION_REFERENCE = Pattern.compile(
            "(?:(?:아까|전에|저번에|지난번에|그때)(?:갔던|말한|말했던|찾던|찾았던|다녀온|간|본)?(?:그)?"
                    + GENERIC_REFERENCE_TARGET + "|그" + GENERIC_REFERENCE_TARGET
                    + "|(?:거기|그곳|그쪽)(?:(?:에|에서|로|의)?(?:있는|근처|동네|쪽)?(?:"
                    + GENERIC_REFERENCE_TARGET + ")?)?|그아래(?:동네|쪽|곳)?(?:에|에서|있는|근처)?(?:"
                    + GENERIC_REFERENCE_TARGET + ")?)");
    private static final Pattern SELECTED_DESTINATION_BEFORE_TRAILING_CORRECTION = Pattern.compile(
            "^(.+?)(?:으로|로)\\s*(?:할게요?|할래요?|하자|가자|갈게요?|해줘요?|찾아줘요?)(?:[,，、].*)?$");
    private static final Pattern FACILITY_CATEGORY_SUFFIX = Pattern.compile(
            "(?:피부과|내과|안과|치과|정형외과|신경외과|이비인후과|소아과|산부인과|비뇨기과|"
                    + "한의원|약국|병원|의원|보건소|시장|마트|카페|식당|음식점|은행|우체국|주민센터)$");
    private static final Pattern GENERIC_FACILITY_ONLY = Pattern.compile(
            "(?:피부과|내과|안과|치과|정형외과|신경외과|이비인후과|소아과|산부인과|비뇨기과|"
                    + "한의원|약국|병원|의원|보건소|시장|마트|카페|식당|음식점|은행|우체국|주민센터)");
    private static final Pattern[] ENDINGS = new Pattern[]{
            Pattern.compile("(?:에|으로|로|까지|을|를)?\\s*(?:(?:좀|한\\s*번(?:만)?)\\s*)?(?:가고\\s*싶(?:은데요?|어서요?|어|어요|다|습니다)|가려(?:고\\s*해요|고\\s*해|는데|는\\s*데)|가려고\\s*하는데|갈래(?:요)?|갈\\s*거야|가면\\s*(?:돼요|됩니다|돼|되나요)|가자|가\\s*보자|가실래요?|가실까요?|가\\s*주세요|가주세요|가\\s*줘|가줘|(?:로|에|까지)?\\s*(?<![가-힣])해(?:\\s*줘|\\s*주세요|\\s*주십시오|\\s*요)|찾아\\s*줘|찾아\\s*주세요|찾아주세요|찾아\\s*봐(?:요|\\s*줘|\\s*주세요)?|검색(?:을)?(?:\\s*좀)?\\s*해(?:봐(?:요|\\s*줘|\\s*주세요)?|\\s*줘|\\s*주세요|요)?|알아\\s*봐(?:요|\\s*줘|\\s*주세요)?|알려\\s*줘|알려\\s*주세요|알려주세요|알려줘요|보여\\s*줘|보여\\s*주세요|보여주세요|데려가(?:요|\\s*줘|\\s*주세요|\\s*주십시오)?|데려다\\s*(?:줘|주세요|주실래요?)|데려다주실래요?|모셔\\s*(?:줘|주세요|주실래요?)|모셔다\\s*(?:줘|주세요|주실래요?)|모셔다줘|모셔다주세요|안내해\\s*줘|안내해\\s*주세요|안내해주세요)\\s*$"),
            Pattern.compile("\\s*(?:에\\s*가요|으로\\s*가요|로\\s*가요|까지\\s*가요|에\\s*갈게요|으로\\s*갈게요|로\\s*갈게요|까지\\s*갈게요)\\s*$")
    };

    private DestinationPhraseParser() {}

    public static Result parse(String utterance) {
        if (utterance == null || utterance.trim().isEmpty()) {
            return new Result("", "어디로 가고 싶으세요?", false, false);
        }
        String value = utterance.trim().replaceAll("[.!?。！？,，]+$", "").trim();
        value = value.replaceFirst("^(?:저는|저|엄마는|어머니는|할머니는)\\s*", "");
        value = value.replaceFirst("^(?:그럼|그러면|아니요|아뇨|아니|네|예|응)(?=\\s|[,，、]|$)\\s*[,，、]?\\s*", "");
        value = REQUEST_COMMAND_PREFIX.matcher(value).replaceFirst("").trim();
        boolean taxiMode = hasTaxiIntent(value);
        boolean explicitTransitMode = hasTransitIntent(value);
        value = NEGATED_TAXI_PREFIX.matcher(value).replaceFirst("").trim();
        value = NEGATED_TRANSIT_PREFIX.matcher(value).replaceFirst("").trim();
        value = value.replaceFirst("^[,，、\\s]+", "").trim();
        value = NEGATED_TAXI_SUFFIX.matcher(value).replaceFirst("").trim();
        value = NEGATED_TRANSIT_SUFFIX.matcher(value).replaceFirst("").trim();
        value = TAXI_PREFIX.matcher(value).replaceFirst("").trim();
        value = value.replaceFirst("^[,，、\\s]+", "").trim();
        value = TRANSIT_PREFIX.matcher(value).replaceFirst("").trim();
        value = value.replaceFirst("^[,，、\\s]+", "").trim();
        for (Pattern ending : ENDINGS) value = ending.matcher(value).replaceFirst("").trim();
        value = NEGATED_TAXI_SUFFIX.matcher(value).replaceFirst("").trim();
        if (taxiMode) {
            java.util.regex.Matcher taxiSuffix = TAXI_SUFFIX.matcher(value);
            if (taxiSuffix.find()) {
                value = taxiSuffix.replaceFirst("").trim();
                value = value.replaceFirst("(?:으로|에)\\s*$", "").trim();
            }
        }
        value = NEGATED_TRANSIT_SUFFIX.matcher(value).replaceFirst("").trim();
        value = TRANSIT_SUFFIX.matcher(value).replaceFirst("").trim();
        value = NEGATED_TAXI_SUFFIX.matcher(value).replaceFirst("").trim();
        value = NEGATED_TRANSIT_SUFFIX.matcher(value).replaceFirst("").trim();
        for (Pattern ending : ENDINGS) value = ending.matcher(value).replaceFirst("").trim();
        // A transport/negation suffix can be removed after the verb ending was already
        // processed (for example, "서울역으로 택시 말고"). Clean the now-dangling particle.
        boolean modeSpecified = taxiMode || explicitTransitMode;
        if (hasAmbiguousMultipleAreaCorrections(value)) {
            return new Result("", "말씀하신 동네가 여러 곳이라 확인이 필요해요. 가려는 시·구와 동네를 다시 말씀해 주세요.",
                    taxiMode, modeSpecified);
        }
        String previousPhrase = previousPhraseBeforeLatestCorrection(value);
        String correctedPhrase = destinationAfterCorrection(value);
        correctedPhrase = TRAILING_POLITE_FACILITY_ENDING.matcher(correctedPhrase)
                .replaceFirst("$1").trim();
        correctedPhrase = stripLeadingJustFiller(correctedPhrase);
        String chainedFacilityCorrection = preserveLocalityAcrossFacilityCorrectionChain(
                value, correctedPhrase);
        if (!chainedFacilityCorrection.equals(correctedPhrase)) {
            correctedPhrase = chainedFacilityCorrection;
        } else if (!previousPhrase.isEmpty() && !correctedPhrase.equals(value)) {
            correctedPhrase = preserveLocalityForFacilityCorrection(previousPhrase, correctedPhrase);
        }
        value = correctedPhrase;
        value = TRAILING_DESTINATION_PARTICLE.matcher(value).replaceFirst("").trim();
        value = value.replaceAll("(?:\\s+(?:좀|부탁해(?:요)?|부탁드려요?))+$", "").trim();
        value = TRAILING_SPOKEN_FILLER.matcher(value).replaceFirst("").trim();
        value = value.replaceAll("[,，、\\s]+$", "").trim();
        value = TRAILING_DESTINATION_PARTICLE.matcher(value).replaceFirst("").trim();
        if (isUnresolvedDestinationReference(value)) {
            return new Result("", "어느 곳인지 확인할 수 없어요. 동네나 장소 이름을 말씀해 주세요.",
                    taxiMode, modeSpecified);
        }
        value = value.replaceAll("^(?:목적지는|목적지|장소는|장소|저기|거기)\\s*", "").trim();
        value = TRAILING_DESTINATION_PARTICLE.matcher(value).replaceFirst("").trim();
        value = value.replaceAll("\\s+", " ");
        if (AMBIGUOUS_ACTION_INSIDE_DESTINATION.matcher(value).find()) {
            return new Result("", "장소 이름에 들어간 말인지, 이동하자는 말씀인지 확인해 주세요.",
                    taxiMode, modeSpecified);
        }
        if (hasConflictingPlaceTypes(value)) {
            return new Result("", "찾으실 장소 종류가 여러 가지예요. 한 가지만 말씀해 주세요.",
                    taxiMode, modeSpecified);
        }
        if (value.length() < 2) return new Result("", "장소 이름이나 동네를 조금 더 자세히 말씀해 주세요.", taxiMode, modeSpecified);
        if (value.length() > 100) return new Result("", "장소 이름을 짧게 말씀해 주세요.", taxiMode, modeSpecified);
        String normalized = value.toLowerCase(Locale.KOREAN).replaceAll("[\\s·]", "");
        if (normalized.matches("(?:우리|저희)?(?:엄마|어머니|할머니|아빠|아버지|할아버지|부모님)?집")) {
            return new Result("", "집 주소가 아직 없어요. 도로명이나 동네를 함께 말씀해 주세요.", taxiMode, modeSpecified);
        }
        return new Result(value, "", taxiMode, modeSpecified);
    }

    private static boolean isUnresolvedDestinationReference(String value) {
        String compact = value == null ? "" : value.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\p{P}\\s]", "");
        return UNRESOLVED_DESTINATION_REFERENCE.matcher(compact).matches();
    }

    private static boolean hasConflictingPlaceTypes(String value) {
        return DestinationQueryGuard.distinctSpecificPlaceTypes(value).size() > 1;
    }

    private static boolean hasParentForLocalArea(java.util.Set<String> areas) {
        boolean hasNeighborhood = false;
        boolean hasBroaderArea = false;
        for (String area : areas) {
            if (DestinationQueryGuard.areaLevel(area) >= 3) hasNeighborhood = true;
            else hasBroaderArea = true;
        }
        return !hasNeighborhood || hasBroaderArea;
    }

    private static boolean hasAmbiguousMultipleAreaCorrections(String utterance) {
        java.util.regex.Matcher correctionMatcher = CORRECTION_MARKER.matcher(utterance);
        int previousCorrectionEnd = -1;
        int lastCorrectionStart = -1;
        int lastCorrectionEnd = -1;
        int correctionCount = 0;
        while (correctionMatcher.find()) {
            correctionCount++;
            previousCorrectionEnd = lastCorrectionEnd;
            lastCorrectionStart = correctionMatcher.start();
            lastCorrectionEnd = correctionMatcher.end();
        }
        if (correctionCount >= 2 && previousCorrectionEnd >= 0) {
            String betweenCorrections = utterance.substring(previousCorrectionEnd, lastCorrectionStart);
            String finalPhrase = utterance.substring(lastCorrectionEnd).trim();
            if (ADMIN_AREA_TOKEN.matcher(finalPhrase).find()) {
                Result explicitFinalDestination = parse(finalPhrase);
                if (explicitFinalDestination.isUsable()
                        && hasParentForLocalArea(DestinationQueryGuard.findAreas(
                        explicitFinalDestination.destination))
                        && !DestinationQueryGuard.hasConflictingAreas(
                        DestinationQueryGuard.findAreas(explicitFinalDestination.destination))) {
                    // A complete, internally consistent final destination supersedes all
                    // earlier locations, even when the utterance mentions several areas.
                    return false;
                }
            }
            boolean intermediateArea = ADMIN_AREA_TOKEN.matcher(betweenCorrections).find();
            boolean finalArea = ADMIN_AREA_TOKEN.matcher(finalPhrase).find();
            boolean finalFacility = GENERIC_FACILITY_ONLY.matcher(finalPhrase).find();
            boolean intermediateFacility =
                    FACILITY_CATEGORY_SUFFIX.matcher(betweenCorrections.trim()).find();
            if (intermediateArea && intermediateFacility && finalFacility && !finalArea) {
                Result intermediateDestination = parse(betweenCorrections.trim());
                if (intermediateDestination.isUsable()
                        && !DestinationQueryGuard.hasConflictingAreas(
                        DestinationQueryGuard.findAreas(intermediateDestination.destination))) {
                    // The last area-bearing replacement is complete; a later category-only
                    // correction changes just its facility, not the earlier discarded area.
                    return false;
                }
            }
            // A second correction can silently remove the only surviving locality,
            // e.g. "금정구 아니고 해운대구 말고 피부과". Hold instead of searching
            // the specialty without its area. If the intermediate replacement already
            // contains a complete area+facility, the final facility-only correction can
            // safely retain that newer locality ("... 서울 종로구 약국 말고 약국").
            if (intermediateArea && !intermediateFacility && finalFacility && !finalArea) return true;
        }
        if (correctionCount < 2) return false;
        java.util.HashSet<String> areas = new java.util.HashSet<>();
        java.util.regex.Matcher areaMatcher = ADMIN_AREA_TOKEN.matcher(utterance);
        while (areaMatcher.find()) areas.add(areaMatcher.group());
        return areas.size() >= 3;
    }

    private static String preserveLocalityAcrossFacilityCorrectionChain(String utterance,
                                                                        String finalPhrase) {
        java.util.regex.Matcher corrections = CORRECTION_MARKER.matcher(utterance);
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> ends = new java.util.ArrayList<>();
        while (corrections.find()) {
            starts.add(corrections.start());
            ends.add(corrections.end());
        }
        if (starts.size() < 2) return finalPhrase;

        Result original = parse(utterance.substring(0, starts.get(0)).trim());
        if (!original.isUsable()) return finalPhrase;
        java.util.regex.Matcher originalFacility = FACILITY_CATEGORY_SUFFIX.matcher(original.destination);
        if (!originalFacility.find()) return finalPhrase;
        Result lastReplacement = parse(finalPhrase);
        if (!lastReplacement.isUsable()
                || !GENERIC_FACILITY_ONLY.matcher(lastReplacement.destination).matches()) {
            return finalPhrase;
        }

        for (int i = 0; i < starts.size(); i++) {
            int segmentEnd = i + 1 < starts.size() ? starts.get(i + 1) : utterance.length();
            Result replacement = parse(utterance.substring(ends.get(i), segmentEnd).trim());
            if (!replacement.isUsable()
                    || !GENERIC_FACILITY_ONLY.matcher(replacement.destination).matches()) {
                return finalPhrase;
            }
            if (i + 1 == starts.size()
                    && !replacement.destination.equals(lastReplacement.destination)) return finalPhrase;
        }

        String locality = originalFacility.replaceFirst("").trim()
                .replaceAll("(?:에\\s*있는|\\s+있는|\\s+쪽)$", "").trim();
        if (locality.isEmpty()) return finalPhrase;
        return locality + " " + lastReplacement.destination;
    }

    static String destinationAfterCorrection(String value) {
        String[] markers = {"아니고요", "아니에요", "아니예요", "아니고", "아니라", "아닌데",
                "말고요", "말구요", "말고", "말구", "빼고", "빼구", "대신"};
        int correctionPosition = -1;
        int correctionEnd = -1;
        for (String correction : markers) {
            int position = value.lastIndexOf(correction);
            if (position < 0 || position <= correctionPosition) continue;
            int end = position + correction.length();
            boolean startsAtBoundary = position == 0
                    || Character.isWhitespace(value.charAt(position - 1))
                    || ",，、".indexOf(value.charAt(position - 1)) >= 0;
            boolean endsAtBoundary = end == value.length()
                    || Character.isWhitespace(value.charAt(end))
                    || ",，、".indexOf(value.charAt(end)) >= 0;
            if (startsAtBoundary && endsAtBoundary) {
                correctionPosition = position;
                correctionEnd = end;
            }
        }
        if (correctionPosition < 0) return value;
        String corrected = value.substring(correctionEnd).trim()
                .replaceFirst("^[,，、\\s]+", "")
                .replaceAll("\\s+", " ");
        if (corrected.isEmpty()) {
            String selectedBeforeCorrection = value.substring(0, correctionPosition).trim();
            if (containsCorrectionMarker(selectedBeforeCorrection)) {
                selectedBeforeCorrection = destinationAfterCorrection(selectedBeforeCorrection);
            }
            java.util.regex.Matcher selected = SELECTED_DESTINATION_BEFORE_TRAILING_CORRECTION
                    .matcher(selectedBeforeCorrection);
            if (selected.matches()) return selected.group(1).trim().replaceAll("\\s+", " ");
        }
        return corrected;
    }

    private static boolean containsCorrectionMarker(String value) {
        for (String marker : new String[]{"아니고요", "아니에요", "아니예요", "아니고", "아니라", "아닌데",
                "말고요", "말구요", "말고", "말구", "빼고", "빼구", "대신"}) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String previousPhraseBeforeLatestCorrection(String value) {
        String[] markers = {"아니고요", "아니에요", "아니예요", "아니고", "아니라", "아닌데",
                "말고요", "말구요", "말고", "말구", "빼고", "빼구", "대신"};
        int correctionPosition = -1;
        for (String marker : markers) {
            int position = value.lastIndexOf(marker);
            if (position > correctionPosition) correctionPosition = position;
        }
        if (correctionPosition <= 0) return "";
        String previous = value.substring(0, correctionPosition).trim();
        previous = previous.replaceFirst("^(?:아니요?|아뇨)[,，、\\s]+", "").trim();
        return previous;
    }

    private static String preserveLocalityForFacilityCorrection(String previousPhrase,
                                                                 String correctedPhrase) {
        Result corrected = parse(correctedPhrase);
        if (!corrected.isUsable()
                || !GENERIC_FACILITY_ONLY.matcher(corrected.destination).matches()) {
            return correctedPhrase;
        }
        Result previous = parse(previousPhrase);
        if (!previous.isUsable()) return correctedPhrase;
        java.util.regex.Matcher previousFacility =
                FACILITY_CATEGORY_SUFFIX.matcher(previous.destination);
        if (!previousFacility.find()) return correctedPhrase;
        String locality = previousFacility.replaceFirst("").trim();
        locality = locality.replaceAll("(?:에\\s*있는|\\s+있는|\\s+쪽)$", "").trim();
        if (locality.isEmpty()) return correctedPhrase;
        return locality + " " + corrected.destination;
    }

    static Result parseWithPreviousDestinationCorrection(String utterance, String previousDestination) {
        Result current = parse(utterance);
        if (!current.isUsable() || previousDestination == null || previousDestination.trim().isEmpty()) {
            return current;
        }
        String normalizedUtterance = utterance.trim();
        if (!normalizedUtterance.matches(".*(?:^|\\s|[,，、])(?:아니|아니요|아뇨|아니고|아니고요|아니라|아닌데|말고|말고요|대신)(?:\\s|[,，、]|$).*")) {
            return current;
        }
        if (!GENERIC_FACILITY_ONLY.matcher(current.destination).matches()) return current;
        Result previous = parse(previousDestination);
        if (!previous.isUsable()) return current;
        java.util.regex.Matcher previousFacility = FACILITY_CATEGORY_SUFFIX.matcher(previous.destination);
        if (!previousFacility.find()) return current;
        String locality = previousFacility.replaceFirst("").trim()
                .replaceAll("(?:에\\s*있는|\\s+있는|\\s+쪽)$", "").trim();
        if (locality.isEmpty()) return current;
        return new Result(locality + " " + current.destination, "", current.taxiMode,
                current.modeSpecified);
    }

    private static String stripLeadingJustFiller(String phrase) {
        String value = phrase == null ? "" : phrase.trim();
        if (!value.startsWith("그냥 ")) return value;
        String remainder = value.substring("그냥 ".length()).trim();
        if (GENERIC_FACILITY_ONLY.matcher(remainder).find()
                || remainder.matches(".*(?:특별시|광역시|시|군|구|읍|면|동|리)(?:\\s|$).*$")) {
            return remainder;
        }
        return value;
    }

    private static boolean hasTransitIntent(String utterance) {
        String compact = utterance.toLowerCase(Locale.KOREAN).replaceAll("[\\p{P}\\s]", "");
        return NEGATED_TAXI_ANY.matcher(utterance).find()
                || compact.contains("택시타고싶지") || compact.contains("택시를타고싶지")
                || compact.contains("택시불러주고싶지") || compact.contains("카카오t") && compact.contains("말고")
                || compact.contains("대중교통")
                || compact.contains("버스로") || compact.contains("버스타고")
                || compact.contains("버스를타고") || compact.contains("지하철로")
                || compact.contains("지하철타고") || compact.contains("지하철을타고")
                || compact.contains("버스타서") || compact.contains("버스를타서")
                || compact.contains("지하철타서") || compact.contains("지하철을타서")
                || compact.contains("전철로") || compact.contains("전철타고") || compact.contains("전철을타고")
                || compact.contains("전철타서") || compact.contains("전철을타서")
                || compact.contains("기차로") || compact.contains("기차타고") || compact.contains("기차타서")
                || compact.contains("기차를타고") || compact.contains("기차를타서");
    }

    private static boolean hasTaxiIntent(String utterance) {
        if (NEGATED_TAXI_ANY.matcher(utterance).find()
                || utterance.matches(".*(?:택시|카카오\\s*(?:t|티|택시)|콜택시)(?:는|은|를)?\\s*(?:안\\s*(?:타고|타서|불러|부르고|호출해)|(?:타고|타서)\\s*싶지\\s*(?:않아|않아요|않습니다|않다)?).*")) {
            return false;
        }
        String compact = utterance.toLowerCase(Locale.KOREAN).replaceAll("[\\p{P}\\s]", "");
        return compact.contains("택시타고") || compact.contains("택시를타고")
                || compact.contains("택시타서") || compact.contains("택시로")
                || compact.contains("택시불러") || compact.contains("택시를불러")
                || compact.contains("택시잡아") || compact.contains("택시호출") || compact.contains("카카오t")
                || compact.contains("카카오택시")
                || compact.contains("카카오티") || compact.contains("콜택시");
    }

    public static final class Result {
        public final String destination;
        public final String message;
        public final boolean taxiMode;
        public final boolean modeSpecified;
        Result(String destination, String message, boolean taxiMode, boolean modeSpecified) {
            this.destination = destination;
            this.message = message;
            this.taxiMode = taxiMode;
            this.modeSpecified = modeSpecified;
        }
        public boolean isUsable() { return !destination.isEmpty(); }
    }
}
