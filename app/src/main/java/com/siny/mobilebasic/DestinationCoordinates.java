package com.siny.mobilebasic;

/** Rejects non-finite and out-of-area geocoder results before a navigation handoff. */
final class DestinationCoordinates {
    private DestinationCoordinates() {}

    static boolean isWithinSupportedArea(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= 33.00 && latitude <= 38.70
                && longitude >= 124.50 && longitude <= 132.00;
    }
}
