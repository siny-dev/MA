package com.siny.mobilebasic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adds address detail only when two spoken destination labels would sound identical. */
final class CandidateVoiceLabelBuilder {
    private CandidateVoiceLabelBuilder() {}

    static List<String> build(List<Place> places) {
        ArrayList<String> labels = new ArrayList<>();
        for (Place place : places) {
            labels.add(place.nameIsFallback
                    ? join(place.area, place.roadAndNumber)
                    : join(place.name, place.area));
        }

        refineDuplicates(places, labels, false);
        refineDuplicates(places, labels, true);
        return labels;
    }

    private static void refineDuplicates(List<Place> places, List<String> labels, boolean useFullAddress) {
        Map<String, Integer> counts = new HashMap<>();
        for (String label : labels) {
            String key = CandidateSelectionParser.normalize(label);
            counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
        }
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (counts.get(CandidateSelectionParser.normalize(label)) < 2) continue;
            Place place = places.get(i);
            if (place.nameIsFallback) {
                labels.set(i, place.fullAddress == null ? "" : place.fullAddress.trim());
            } else if (useFullAddress) {
                labels.set(i, join(place.name, place.fullAddress));
            } else {
                labels.set(i, join(label, place.roadAndNumber));
            }
        }
    }

    private static String join(String left, String right) {
        String first = left == null ? "" : left.trim();
        String second = right == null ? "" : right.trim();
        if (first.isEmpty()) return second;
        if (second.isEmpty() || CandidateSelectionParser.normalize(first)
                .contains(CandidateSelectionParser.normalize(second))) return first;
        return first + ", " + second;
    }

    static final class Place {
        final String name;
        final String area;
        final String roadAndNumber;
        final String fullAddress;
        final boolean nameIsFallback;

        Place(String name, String area, String roadAndNumber, String fullAddress,
              boolean nameIsFallback) {
            this.name = name;
            this.area = area;
            this.roadAndNumber = roadAndNumber;
            this.fullAddress = fullAddress;
            this.nameIsFallback = nameIsFallback;
        }
    }
}
