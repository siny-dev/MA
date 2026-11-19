package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class DestinationAiFallbackQueryTest {
    @Test public void explicitAreaAndPlaceTypeAllowLiteralFallback() {
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(
                "부산 금정구 서동에 있는 피부과");
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 서동에 있는 피부과", parsed.destination);

        assertEquals("부산 금정구 서동 피부과", fallback.query);
        assertEquals("부산 금정구 서동", fallback.requestedArea);
        assertEquals("", fallback.contextNotice);
    }

    @Test public void clarificationCanReusePreviouslySpokenAreaAndType() {
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 피부과", "서동", "서동");

        assertEquals("부산 금정구 서동 피부과", fallback.query);
        assertEquals("부산 금정구 서동", fallback.requestedArea);
        assertEquals("앞서 말씀하신 내용은 ‘부산 금정구 피부과’이고, 이번 말씀은 ‘서동’예요. 두 내용을 합쳐 ",
                fallback.contextNotice);
    }

    @Test public void latestSpokenCorrectionReplacesOldAreaAndType() {
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(
                "부산 금정구 서동 피부과 말고 서울 종로구 약국으로 가자");
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 서동 피부과",
                "부산 금정구 서동 피부과 말고 서울 종로구 약국으로 가자",
                parsed.destination);

        assertEquals("서울 종로구 약국", fallback.query);
        assertEquals("서울 종로구", fallback.requestedArea);
        assertEquals("", fallback.contextNotice);
    }

    @Test public void selectedNewDestinationBeforeTrailingCorrectionReplacesOldContext() {
        String utterance = "서울 종로구 약국으로 할게, 아까 그건 말고";
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(utterance);
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 피부과", utterance, parsed.destination);

        assertEquals("서울 종로구 약국", parsed.destination);
        assertEquals("서울 종로구 약국", fallback.query);
        assertEquals("서울 종로구", fallback.requestedArea);
        assertEquals("", fallback.contextNotice);
    }

    @Test public void trailingCorrectionWithoutExplicitNewSelectionFailsClosed() {
        String utterance = "서울 종로구 약국 말고";
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(utterance);

        assertEquals("", parsed.destination);
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 피부과", utterance, parsed.destination));
    }

    @Test public void trailingCorrectionAfterAnEarlierReplacementKeepsOnlyTheLatestDestination() {
        String utterance = "부산 금정구 피부과 말고 서울 종로구 약국으로 할게요, 아까 그건 말고";
        DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(utterance);
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 피부과", utterance, parsed.destination);

        assertEquals("서울 종로구 약국", parsed.destination);
        assertEquals("서울 종로구 약국", fallback.query);
    }

    @Test public void insufficientOrConflictingLocationDetailsFailClosed() {
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase("", "피부과", "피부과"));
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase("", "부산 금정구 서동", "서동"));
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase("", "아까 갔던 데", "아까 갔던 데"));
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase("",
                "부산 해운대구 피부과와 서울 강남구 피부과", "부산 해운대구 피부과 서울 강남구 피부과"));
    }

    @Test public void fallbackRejectsNeighborhoodWithoutParentAreaAndRequestsSpecificRegion() {
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase("", "서동 피부과", "서동 피부과"));
        assertEquals("그 동네는 여러 지역에 있을 수 있어요. 시나 구도 함께 말씀해 주세요. 예를 들어 부산 금정구 서동처럼요.",
                DestinationAiFallbackQuery.blockedPrompt("", "서동 피부과", "서동 피부과"));
    }

    @Test public void blockedFallbackPromptsForTheMissingSlotOrSingleCategory() {
        assertEquals("어떤 장소를 찾을까요? 동네와 장소 종류를 함께 말씀해 주세요. 예를 들어 부산 금정구 서동 피부과처럼요.",
                DestinationAiFallbackQuery.blockedPrompt(
                        "", "부산 금정구 서동", "서동"));
        assertEquals("어느 동네에서 찾을까요? 동네와 장소 종류를 함께 말씀해 주세요.",
                DestinationAiFallbackQuery.blockedPrompt("", "피부과", "피부과"));
        assertEquals("장소가 여러 가지로 들려요: 피부과와 약국. 찾으실 장소 종류 한 가지만 말씀해 주세요.",
                DestinationAiFallbackQuery.blockedPrompt(
                        "부산 금정구", "피부과와 약국", "피부과 약국"));
    }

    @Test public void latestExplicitPlaceTypeReplacesClarificationType() {
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구 피부과", "약국", "약국");

        assertEquals("부산 금정구 약국", fallback.query);
        assertEquals("부산 금정구", fallback.requestedArea);
        assertEquals("앞서 말씀하신 내용은 ‘부산 금정구’이고, 이번 말씀은 ‘약국’예요. 두 내용을 합쳐 ",
                fallback.contextNotice);
    }

    @Test public void latestExplicitPlaceTypeRequestsOnlyTheStillMissingNeighborhood() {
        assertEquals("어느 동네에서 찾을까요? 동네와 장소 종류를 함께 말씀해 주세요.",
                DestinationAiFallbackQuery.blockedPrompt(
                        "부산 금정구 피부과", "약국", "부산 금정구 피부과 약국"));
    }

    @Test public void fallbackRejectsMultiplePlaceTypesInOneCurrentRequest() {
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 피부과와 약국", "부산 금정구 피부과 약국"));
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 병원과 약국", "부산 금정구 병원 약국"));
        assertEquals("부산 금정구 병원 피부과", DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 병원 피부과", "부산 금정구 병원 피부과").query);
    }

    @Test public void fallbackNeverSearchesOnlyTheCategoryExcludedByAnIncompleteCorrection() {
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 서동 치과 빼고", "부산 금정구 서동 치과"));
        assertNull(DestinationAiFallbackQuery.fromSpokenPhrase(
                "", "부산 금정구 서동 피부과 말구", "부산 금정구 서동 피부과"));
    }

    @Test public void fallbackDisclosureNamesPriorAreaWhenCurrentTurnAddsPlaceType() {
        DestinationAiFallbackQuery.Result fallback = DestinationAiFallbackQuery.fromSpokenPhrase(
                "부산 금정구", "약국", "약국");

        assertEquals("부산 금정구 약국", fallback.query);
        assertEquals("앞서 말씀하신 내용은 ‘부산 금정구’이고, 이번 말씀은 ‘약국’예요. 두 내용을 합쳐 ",
                fallback.contextNotice);
    }
}
