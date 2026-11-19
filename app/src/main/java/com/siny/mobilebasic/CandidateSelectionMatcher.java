package com.siny.mobilebasic;

/** Matches a distinctive spoken place name/address to one already verified search result. */
final class CandidateSelectionMatcher {
    private CandidateSelectionMatcher() {}

    static boolean matches(String discriminativePhrase, String name, String label, String address) {
        String phrase = CandidateSelectionParser.normalize(discriminativePhrase);
        if (phrase.length() < 2) return false;
        String normalizedName = CandidateSelectionParser.normalize(name);
        String normalizedLabel = CandidateSelectionParser.normalize(label);
        String normalizedAddress = CandidateSelectionParser.normalize(address);
        return !normalizedName.isEmpty()
                && (normalizedName.equals(phrase) || normalizedName.contains(phrase))
                || !normalizedLabel.isEmpty()
                && (normalizedLabel.equals(phrase) || normalizedLabel.contains(phrase))
                || normalizedAddress.contains(phrase);
    }
}
