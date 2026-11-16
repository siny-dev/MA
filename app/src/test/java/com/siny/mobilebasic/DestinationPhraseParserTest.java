package com.siny.mobilebasic;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DestinationPhraseParserTest {
    @Test public void extractsRouteDestination() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("수원역에 가고 싶어요.");
        assertTrue(result.isUsable());
        assertEquals("수원역", result.destination);
    }

    @Test public void keepsPlaceTypeWhenSpeechNamesANeighborhoodDermatologyClinic() {
        for (String utterance : new String[]{
                "서동의 피부과", "부산 금정구 서동에 있는 피부과",
                "부산 금정구 서동 피부과 가자", "서동 피부과 좀 찾아줘"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertTrue(utterance, result.isUsable());
            assertTrue(utterance, result.destination.contains("피부과"));
            assertFalse(utterance, "서동".equals(result.destination));
        }
    }

    @Test public void keepsLatestExplicitDestinationCorrection() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse(
                "부산 금정구 서동 피부과 말고 서울 종로구 약국으로 가자");
        assertTrue(result.isUsable());
        assertEquals("서울 종로구 약국", result.destination);
    }

    @Test public void extractsDestinationWithPoliteRequest() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("저는 서울역으로 가고 싶습니다");
        assertTrue(result.isUsable());
        assertEquals("서울역", result.destination);
    }

    @Test public void extractsDestinationWithCasualRequest() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("부산역에 가고 싶어");
        assertTrue(result.isUsable());
        assertEquals("부산역", result.destination);
    }

    @Test public void asksForMoreWhenDestinationIsMissing() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("거기로 가고 싶어요");
        assertFalse(result.isUsable());
        assertFalse(result.message.isEmpty());
    }

    @Test public void doesNotGuessSavedHomeAddress() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("엄마 집에 가고 싶어요");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("집 주소"));
    }

    @Test public void doesNotGuessFamilyHomeFromLongerPhrase() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("우리 엄마 집에 가고 싶어요");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("집 주소"));
    }

    @Test public void rejectsLongUtterance() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("가".repeat(101));
        assertFalse(result.isUsable());
    }

    @Test public void extractsDestinationWithSpacedPoliteVerb() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("잠실역으로 데려다 주세요");
        assertTrue(result.isUsable());
        assertEquals("잠실역", result.destination);
    }

    @Test public void extractsDestinationWithSimpleGoRequest() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("서울역 가 주세요");
        assertTrue(result.isUsable());
        assertEquals("서울역", result.destination);
    }

    @Test public void doesNotGuessParentHomeAddress() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("아버지 집으로 가주세요");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("집 주소"));
    }

    @Test public void supportsPlainPlaceName() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse("강남역");
        assertTrue(result.isUsable());
        assertEquals("강남역", result.destination);
        assertFalse(result.modeSpecified);
    }

    @Test public void taxiIntentSetsExplicitTaxiModeAndKeepsOnlyPlaceName() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("택시 타고 서울역으로 가고 싶어요");
        assertTrue(result.isUsable());
        assertTrue(result.modeSpecified);
        assertTrue(result.taxiMode);
        assertEquals("서울역", result.destination);
    }

    @Test public void taxiRequestAfterTheDestinationDoesNotLeakIntoPlaceSearch() {
        for (String utterance : new String[]{
                "서울역 택시 불러줘", "서울역 택시 타고 가고 싶어요", "서울역으로 카카오 T 호출해 주세요",
                "카카오 택시로 서울역에 가고 싶어요", "서울역, 택시 잡아 줘",
                "서울역으로 카카오 티 호출해", "서울역 카카오 택시 불러줘",
                "서울역 카카오 T 잡아줘"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertTrue(utterance, result.isUsable());
            assertTrue(utterance, result.modeSpecified);
            assertTrue(utterance, result.taxiMode);
            assertEquals(utterance, "서울역", result.destination);
        }
    }

    @Test public void negatedTaxiAndTransitModeOverridePreviouslySelectedTaxiMode() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("택시 말고 버스로 서울역 가고 싶어요");
        assertTrue(result.isUsable());
        assertTrue(result.modeSpecified);
        assertFalse(result.taxiMode);
        assertEquals("서울역", result.destination);
    }

    @Test public void transitModeAfterTheDestinationIsStillExplicit() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("서울역 가고 싶어요, 택시 말고");
        assertTrue(result.isUsable());
        assertTrue(result.modeSpecified);
        assertFalse(result.taxiMode);
        assertEquals("서울역", result.destination);
    }

    @Test public void taxiPhraseOnPlaceNameDoesNotOverrideExplicitTransitMode() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("지하철로 택시 정류장에 가고 싶어요");
        assertTrue(result.isUsable());
        assertTrue(result.modeSpecified);
        assertFalse(result.taxiMode);
        assertEquals("택시 정류장", result.destination);
    }

    @Test public void commonPoliteTaxiNegationsCancelAPreviouslySelectedTaxiMode() {
        for (String utterance : new String[]{
                "택시 안 타고 서울역", "택시를 안 타고 서울역", "택시 타고 싶지 않아 서울역",
                "서울역 가고 싶어요, 택시 말고", "서울역 가고 싶어요, 카카오 T는 말고",
                "택시 부르지 마 서울역", "서울역 가고 싶어요, 택시 호출하지 마요",
                "서울역으로 택시 안 불러줘", "택시 안 타고 싶어요 서울역"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertFalse(utterance, result.taxiMode);
            assertTrue(utterance, result.modeSpecified);
        }
    }

    @Test public void removingATransportSuffixAlsoRemovesTheDestinationParticle() {
        for (String utterance : new String[]{
                "서울역으로 택시 말고", "서울역으로 택시 부르지 마", "서울역으로 버스로"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertTrue(utterance, result.isUsable());
            assertFalse(utterance, result.taxiMode);
            assertTrue(utterance, result.modeSpecified);
            assertEquals(utterance, "서울역", result.destination);
        }
    }

    @Test public void simulatedNegatedTaxiRequestsDoNotLeaveCommandWordsInTheSearch() {
        String[][] cases = {
                {"택시 안 타고 싶어요, 서울역", "서울역"},
                {"택시 안 불러줘, 서울역", "서울역"},
                {"택시 호출하지 말고, 서울역으로", "서울역"},
                {"서울역으로 가고 싶어요, 택시 말고, 버스로", "서울역"},
                {"서울역, 택시 안 불러줘", "서울역"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertTrue(scenario[0], result.modeSpecified);
            assertFalse(scenario[0], result.taxiMode);
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void simulatedEverydayTransportCorrectionsAndDestinationParticles() {
        String[][] cases = {
                {"강남역까지 가주세요", "강남역", "unspecified"},
                {"택시 타고 강남역까지", "강남역", "taxi"},
                {"택시 타고 강남역까지 데려다줘", "강남역", "taxi"},
                {"전철 타고 강남역", "강남역", "transit"},
                {"택시 대신 버스로 강남역", "강남역", "transit"},
                {"택시 싫고 버스로 강남역", "강남역", "transit"},
                {"버스 말고 택시 타고 강남역", "강남역", "taxi"},
                {"강남역, 버스 말고 택시 타고", "강남역", "taxi"},
                {"강남역까지 택시 불러줘", "강남역", "taxi"},
                {"버스 대신 택시로 강남역", "강남역", "taxi"},
                {"강남역 버스 말고, 택시로", "강남역", "taxi"},
                {"강남역 대중교통 타고", "강남역", "transit"},
                {"강남역, 택시 대신, 대중교통으로", "강남역", "transit"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
            if ("unspecified".equals(scenario[2])) {
                assertFalse(scenario[0], result.modeSpecified);
            } else {
                assertTrue(scenario[0], result.modeSpecified);
                assertEquals(scenario[0], "taxi".equals(scenario[2]), result.taxiMode);
            }
        }
    }

    @Test public void fillerWordsAndCommonSpokenDirectionsDoNotLeakIntoGeocoderQueries() {
        for (String[] scenario : new String[][]{
                {"그럼 강남역으로 가주세요", "강남역"},
                {"아니요, 수원역으로 갈래요", "수원역"},
                {"수원역으로 가면 돼요", "수원역"},
                {"서울역까지 모셔다 주세요", "서울역"},
                {"버스 타고 서울역까지 가면 돼요", "서울역"}
        }) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void transportChoiceCanComeBeforeOrAfterThePlaceAndWantingPhrase() {
        String[][] cases = {
                {"택시 타고 싶어요, 서울역", "서울역", "taxi"},
                {"버스 타고 싶어요, 서울역", "서울역", "transit"},
                {"서울역 가려는데 택시 타고 싶어요", "서울역", "taxi"},
                {"서울역 가려는데 버스 타고 싶어요", "서울역", "transit"},
                {"대중교통 타고 싶어요, 수원역", "수원역", "transit"},
                {"서울역으로 가고 싶은데 택시 타고 싶어요", "서울역", "taxi"},
                {"서울역으로 가고 싶어서 버스 타고 싶어요", "서울역", "transit"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
            assertTrue(scenario[0], result.modeSpecified);
            assertEquals(scenario[0], "taxi".equals(scenario[2]), result.taxiMode);
        }
    }

    @Test public void transportPhrasesAtEitherEndDoNotBecomePartOfThePlaceName() {
        for (String utterance : new String[]{
                "버스로 서울역 가고 싶어요", "서울역 가고 싶어요, 버스로", "서울역 택시 안 타고 가고 싶어요"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertTrue(utterance, result.modeSpecified);
            assertFalse(utterance, result.taxiMode);
            assertEquals(utterance, "서울역", result.destination);
        }
    }

    @Test public void naturalTransitRequestsWithoutObjectParticlesKeepThePlaceNameClean() {
        String[][] cases = {
                {"버스 타고 서울역 가고 싶어요", "서울역"},
                {"지하철 타서 강남역", "강남역"},
                {"대중교통 타고 수원역으로 가요", "수원역"},
                {"부산역 가고 싶어요, 기차 타고", "부산역"},
                {"택시 말고 버스 타고 서울역", "서울역"},
                {"택시 말고, 버스 타고 서울역", "서울역"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertTrue(scenario[0], result.modeSpecified);
            assertFalse(scenario[0], result.taxiMode);
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void correctionTailExtractionKeepsOnlyTheCorrectedPhrase() {
        assertEquals("강남역", DestinationPhraseParser.destinationAfterCorrection("서울역 말고 강남역"));
    }

    @Test public void aSpokenDestinationCorrectionUsesOnlyTheLatestPlaceInLocalFallback() {
        String[][] cases = {
                {"서울역 말고 강남역으로 가주세요", "강남역", "default"},
                {"서울역이 아니고 강남역에 가고 싶어요", "강남역", "default"},
                {"서울역 아니에요, 수원역으로 모셔다 주세요", "수원역", "default"},
                {"서울역 말고 강남역 택시 불러줘", "강남역", "taxi"},
                {"아니고요, 강남역으로 가주세요", "강남역", "default"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
            if ("default".equals(scenario[2])) {
                assertFalse(scenario[0], result.modeSpecified);
            } else {
                assertTrue(scenario[0], result.modeSpecified);
                assertEquals(scenario[0], "taxi".equals(scenario[2]), result.taxiMode);
            }
        }
    }
}
