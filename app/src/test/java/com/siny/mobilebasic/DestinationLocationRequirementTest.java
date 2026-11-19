package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DestinationLocationRequirementTest {
    @Test public void nearbyRequestRequiresAnExplicitNeighborhood() {
        assertTrue(DestinationLocationRequirement.requiresNeighborhood("", "가까운 피부과"));
        assertTrue(DestinationLocationRequirement.requiresNeighborhood("", "우리 동네 주변 약국"));
    }

    @Test public void nearbyIntentSurvivesAnsweringWithOnlyTheRequestedArea() {
        assertTrue(DestinationLocationRequirement.requiresNeighborhood(
                "근처 피부과", "부산 금정구 서동"));
    }

    @Test public void explicitCorrectionCancelsEarlierNearbyIntent() {
        assertFalse(DestinationLocationRequirement.requiresNeighborhood(
                "근처 피부과", "근처 말고 부산역"));
        assertTrue(DestinationLocationRequirement.requiresNeighborhood(
                "근처 피부과", "피부과 말고 부산 서동 근처 약국"));
    }

    @Test public void ordinaryAreaAndCategoryRequestDoesNotRequireNearbyHandling() {
        assertFalse(DestinationLocationRequirement.requiresNeighborhood(
                "", "부산 금정구 서동 피부과"));
    }

    @Test public void genericFacilitySearchNeedsAnAreaButNamedPlacesDoNot() {
        for (String query : new String[]{"피부과", "내과", "약국", "병원"}) {
            assertTrue(query,
                    DestinationLocationRequirement.requiresAreaForGenericPlaceType(query));
        }
        assertFalse(DestinationLocationRequirement.requiresAreaForGenericPlaceType(
                "부산 금정구 서동 피부과"));
        assertFalse(DestinationLocationRequirement.requiresAreaForGenericPlaceType(
                "좋은이웃 피부과"));
    }
}
