package com.siny.mobilebasic.assistant;

/** Small bounded context for voice follow-ups such as “the clinic with a specialist”. */
public final class DestinationContext {
    private static final int MAX_CHARS = 500;

    private DestinationContext() { }

    public static String combine(String previousRequest, String latestUtterance) {
        String previous = normalize(previousRequest);
        String latest = normalize(latestUtterance);
        if (previous.isEmpty()) return limit(latest, MAX_CHARS);
        if (latest.isEmpty()) return limit(previous, MAX_CHARS);
        int remaining = Math.max(0, MAX_CHARS - previous.length() - 1);
        return previous + " " + limit(latest, remaining);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String limit(String value, int max) {
        if (max <= 0) return "";
        return value.length() <= max ? value : value.substring(0, max).trim();
    }
}
