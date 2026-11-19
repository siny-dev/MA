package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a grounded NAVER query from slots understood by the live voice model. */
final class LiveDestinationQuery {
    enum Error { NONE, MISSING_REGION, AMBIGUOUS_REGION, MISSING_PLACE }

    static final class Result {
        final Error error;
        final String region;
        final String category;
        final String placeName;
        final String query;

        private Result(Error error, String region, String category, String placeName, String query) {
            this.error = error;
            this.region = region;
            this.category = category;
            this.placeName = placeName;
            this.query = query;
        }

        boolean isValid() { return error == Error.NONE; }
    }

    private LiveDestinationQuery() {}

    static Result fromSlots(String region, String category, String placeName) {
        String cleanRegion = clean(region);
        String cleanCategory = clean(category);
        String cleanPlace = clean(placeName);
        if (cleanRegion.isEmpty()) return new Result(Error.MISSING_REGION, "", cleanCategory, cleanPlace, "");
        if (!hasCityOrDistrict(cleanRegion)) {
            return new Result(Error.AMBIGUOUS_REGION, cleanRegion, cleanCategory, cleanPlace, "");
        }
        // A locality accidentally placed in the category slot is not a place type.
        // Drop that slot before deciding whether there is enough intent to search.
        if (duplicatesRegionSuffix(cleanRegion, cleanCategory)) cleanCategory = "";
        if (cleanCategory.isEmpty() && cleanPlace.isEmpty()) {
            return new Result(Error.MISSING_PLACE, cleanRegion, "", "", "");
        }
        // A model may misclassify the neighborhood as both region and destination
        // (for example region="부산 금정구 서동", place_name="서동"). That is
        // still only a region; never turn it into a place search or route target.
        if (cleanCategory.isEmpty() && duplicatesRegionSuffix(cleanRegion, cleanPlace)) {
            return new Result(Error.MISSING_PLACE, cleanRegion, "", "", "");
        }
        List<String> parts = new ArrayList<>();
        parts.add(cleanRegion);
        if (!cleanCategory.isEmpty()) parts.add(cleanCategory);
        if (!cleanPlace.isEmpty() && !cleanPlace.equals(cleanCategory)
                && !duplicatesRegionSuffix(cleanRegion, cleanPlace)) parts.add(cleanPlace);
        StringBuilder query = new StringBuilder();
        for (String part : parts) {
            if (query.length() > 0) query.append(' ');
            query.append(part);
        }
        return new Result(Error.NONE, cleanRegion, cleanCategory, cleanPlace, query.toString());
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\s+", " ");
        clean = clean.replaceAll("(?i)^(대한민국|한국)\\s+", "");
        clean = clean.replaceAll("(에 있는|에서|으로|로)$", "").trim();
        return clean;
    }

    private static boolean hasCityOrDistrict(String region) {
        String lower = region.toLowerCase(Locale.ROOT);
        return lower.matches(".*(특별시|광역시|자치시|자치도|특별자치도|시|군|구|도)(?:\\s|$|[가-힣0-9]).*")
                || lower.equals("서울") || lower.equals("부산") || lower.equals("대구")
                || lower.equals("인천") || lower.equals("광주") || lower.equals("대전")
                || lower.equals("울산") || lower.equals("세종") || lower.equals("제주");
    }

    private static boolean duplicatesRegionSuffix(String region, String placeName) {
        if (region == null || placeName == null || region.isEmpty() || placeName.isEmpty()) return false;
        String compactRegion = region.replaceAll("\\s+", "");
        String compactPlace = placeName.replaceAll("\\s+", "");
        return compactRegion.equals(compactPlace) || compactRegion.endsWith(compactPlace);
    }
}
