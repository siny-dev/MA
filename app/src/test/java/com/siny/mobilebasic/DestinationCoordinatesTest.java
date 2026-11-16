package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationCoordinatesTest {
    @Test public void acceptsMainlandAndIslandKoreanDestinations() {
        assertTrue(DestinationCoordinates.isWithinSupportedArea(37.5665, 126.9780));
        assertTrue(DestinationCoordinates.isWithinSupportedArea(33.4996, 126.5312));
        assertTrue(DestinationCoordinates.isWithinSupportedArea(37.2426, 131.8638));
    }

    @Test public void rejectsInvalidAndClearlyOutOfAreaGeocoderResults() {
        assertFalse(DestinationCoordinates.isWithinSupportedArea(Double.NaN, 126.9));
        assertFalse(DestinationCoordinates.isWithinSupportedArea(37.5, Double.POSITIVE_INFINITY));
        assertFalse(DestinationCoordinates.isWithinSupportedArea(48.8566, 2.3522));
        assertFalse(DestinationCoordinates.isWithinSupportedArea(37.5, 132.01));
    }

    @Test public void rejectsNearbyNorthKoreanGeocoderResultsButKeepsSouthKoreanIslands() {
        assertFalse(DestinationCoordinates.isWithinSupportedArea(39.0392, 125.7625));
        assertFalse(DestinationCoordinates.isWithinSupportedArea(32.9, 126.5));
        assertTrue(DestinationCoordinates.isWithinSupportedArea(33.1169, 126.2689));
        assertTrue(DestinationCoordinates.isWithinSupportedArea(37.9667, 124.6300));
    }
}
