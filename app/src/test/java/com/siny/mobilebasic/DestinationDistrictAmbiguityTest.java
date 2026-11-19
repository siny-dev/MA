package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DestinationDistrictAmbiguityTest {
    @Test public void warnsWhenSameSpokenNeighborhoodCandidatesSpanDistricts() {
        String reminder = DestinationDistrictAmbiguity.reminder("부산 서동 피부과", Arrays.asList(
                "부산광역시 금정구 서동 123-4",
                "부산광역시 동래구 서동 234-5"));

        assertTrue(reminder.contains("여러 구"));
        assertTrue(reminder.contains("구 이름"));
    }

    @Test public void doesNotAskForDistrictWhenUserAlreadySpecifiedIt() {
        assertEquals("", DestinationDistrictAmbiguity.reminder("부산 금정구 서동 피부과", Arrays.asList(
                "부산광역시 금정구 서동 123-4",
                "부산광역시 동래구 서동 234-5")));
    }

    @Test public void singleDistrictCandidateIsExplicitlyIdentifiedWhenUserOmittedDistrict() {
        assertEquals("구를 말씀하지 않으셔서, 검색된 주소의 동래구를 확인해 주세요. ",
                DestinationDistrictAmbiguity.confirmationNote("부산 서동 피부과",
                        "부산광역시 동래구 서동로 123"));
    }

    @Test public void singleDistrictCandidateNeedsNoExtraNoticeWhenDistrictWasSpoken() {
        assertEquals("", DestinationDistrictAmbiguity.confirmationNote("부산 금정구 서동 피부과",
                "부산광역시 금정구 서동로 123"));
    }

    @Test public void doesNotAskForDistrictForOneDistrictOrBroadCitySearch() {
        assertEquals("", DestinationDistrictAmbiguity.reminder("부산 서동 피부과", Arrays.asList(
                "부산광역시 금정구 서동 123-4",
                "부산광역시 금정구 서동 234-5")));
        assertEquals("", DestinationDistrictAmbiguity.reminder("부산 피부과", Arrays.asList(
                "부산광역시 금정구 서동 123-4",
                "부산광역시 동래구 온천동 234-5")));
    }
}
