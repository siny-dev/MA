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
    private static final Pattern TRAILING_DESTINATION_PARTICLE = Pattern.compile("(?:으로|에|까지)\\s*$");
    private static final Pattern[] ENDINGS = new Pattern[]{
            Pattern.compile("(?:에|으로|로|까지)?\\s*(?:가고\\s*싶(?:은데요?|어서요?|어|어요|다|습니다)|가려(?:고\\s*해요|고\\s*해|는데|는\\s*데)|가려고\\s*하는데|갈래(?:요)?|갈\\s*거야|가면\\s*(?:돼요|됩니다|돼|되나요)|가자|가\\s*주세요|가주세요|가\\s*줘|가줘|찾아\\s*줘|찾아\\s*주세요|찾아주세요|데려다\\s*줘|데려다\\s*주세요|데려다주세요|모셔다\\s*(?:줘|주세요)|모셔다줘|모셔다주세요|안내해\\s*줘|안내해\\s*주세요|안내해주세요)\\s*$"),
            Pattern.compile("\\s*(?:에\\s*가요|으로\\s*가요|로\\s*가요|까지\\s*가요|에\\s*갈게요|으로\\s*갈게요|로\\s*갈게요|까지\\s*갈게요)\\s*$")
    };

    private DestinationPhraseParser() {}

    public static Result parse(String utterance) {
        if (utterance == null || utterance.trim().isEmpty()) {
            return new Result("", "어디로 가고 싶으세요?", false, false);
        }
        String value = utterance.trim().replaceAll("[.!?。！？,，]+$", "").trim();
        value = value.replaceFirst("^(?:저는|저|엄마는|어머니는|할머니는)\\s*", "");
        value = value.replaceFirst("^(?:그럼|그러면|아니요|아뇨|아니)(?=\\s|[,，、]|$)\\s*[,，、]?\\s*", "");
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
        value = destinationAfterCorrection(value);
        value = TRAILING_DESTINATION_PARTICLE.matcher(value).replaceFirst("").trim();
        value = value.replaceAll("^(?:목적지는|목적지|장소는|장소|저기|거기)\\s*", "").trim();
        value = value.replaceAll("\\s+", " ");
        boolean modeSpecified = taxiMode || explicitTransitMode;
        if (value.length() < 2) return new Result("", "장소 이름이나 동네를 조금 더 자세히 말씀해 주세요.", taxiMode, modeSpecified);
        if (value.length() > 100) return new Result("", "장소 이름을 짧게 말씀해 주세요.", taxiMode, modeSpecified);
        String normalized = value.toLowerCase(Locale.KOREAN).replaceAll("[\\s·]", "");
        if (normalized.matches("(?:우리|저희)?(?:엄마|어머니|할머니|아빠|아버지|할아버지|부모님)?집")) {
            return new Result("", "집 주소가 아직 없어요. 도로명이나 동네를 함께 말씀해 주세요.", taxiMode, modeSpecified);
        }
        return new Result(value, "", taxiMode, modeSpecified);
    }

    static String destinationAfterCorrection(String value) {
        String[] markers = {"아니고요", "아니에요", "아니예요", "아니고", "아니라", "아닌데", "말고요", "말고"};
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
        return corrected;
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
