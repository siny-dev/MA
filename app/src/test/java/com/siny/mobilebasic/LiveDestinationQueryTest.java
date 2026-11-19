package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LiveDestinationQueryTest {
    @Test public void keepsRegionAndCategoryTogetherForTheObservedFailurePhrase() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "피부과", "");

        assertEquals(LiveDestinationQuery.Error.NONE, result.error);
        assertEquals("부산 금정구 서동 피부과", result.query);
    }

    @Test public void doesNotSearchNeighborhoodAloneEvenWhenCategoryIsKnown() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots("서동", "피부과", "");

        assertEquals(LiveDestinationQuery.Error.AMBIGUOUS_REGION, result.error);
        assertEquals("", result.query);
    }

    @Test public void doesNotSearchARegionWithoutPlaceIntent() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots("부산 금정구 서동", "", "");

        assertEquals(LiveDestinationQuery.Error.MISSING_PLACE, result.error);
        assertEquals("부산 금정구 서동", result.region);
        assertEquals("", result.category);
        assertFalse(result.isValid());

        LiveDestinationQuery.Result afterUserClarifies = LiveDestinationQuery.fromSlots(
                result.region, "피부과", "");
        assertEquals(LiveDestinationQuery.Error.NONE, afterUserClarifies.error);
        assertEquals("부산 금정구 서동 피부과", afterUserClarifies.query);
    }

    @Test public void doesNotMistakeNeighborhoodRepeatedAsPlaceNameForDestination() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "", "서동");

        assertEquals(LiveDestinationQuery.Error.MISSING_PLACE, result.error);
        assertFalse(result.isValid());
        assertEquals("", result.query);
    }

    @Test public void doesNotMistakeNeighborhoodRepeatedInCategorySlotForPlaceType() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "서동", "");

        assertEquals(LiveDestinationQuery.Error.MISSING_PLACE, result.error);
        assertFalse(result.isValid());
        assertEquals("", result.query);
    }

    @Test public void keepsTheRealPlacePhraseWhenTheCategorySlotRepeatsTheNeighborhood() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "서동", "피부과");

        assertEquals(LiveDestinationQuery.Error.NONE, result.error);
        assertEquals("부산 금정구 서동 피부과", result.query);
    }

    @Test public void keepsARealPlacePhraseEvenWhenItContainsTheNeighborhoodName() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "", "서동중앙피부과");

        assertEquals(LiveDestinationQuery.Error.NONE, result.error);
        assertEquals("부산 금정구 서동 서동중앙피부과", result.query);

        LiveDestinationQuery.Result pharmacy = LiveDestinationQuery.fromSlots(
                "부산 금정구 서동", "", "서동약국");
        assertEquals(LiveDestinationQuery.Error.NONE, pharmacy.error);
        assertEquals("부산 금정구 서동 서동약국", pharmacy.query);
    }

    @Test public void namedPlaceCanSupplyPlaceSlot() {
        LiveDestinationQuery.Result result = LiveDestinationQuery.fromSlots(
                "부산 금정구", "", "이종호 피부과");

        assertEquals("부산 금정구 이종호 피부과", result.query);
    }
}
