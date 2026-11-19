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

    @Test public void changingOnlyTheClinicTypeKeepsThePreviouslySpokenNeighborhood() {
        String[][] cases = {
                {"부산 금정구 서동 피부과 말고 내과로 가자", "부산 금정구 서동 내과"},
                {"서동에 있는 피부과 아니고 내과로 데려가 줘", "서동 내과"},
                {"부산 금정구 서동 피부과 말고 내과요", "부산 금정구 서동 내과"},
                {"서동 피부과 말고 내과로 해 줘", "서동 내과"},
                {"아니 피부과 말고 내과로", "내과"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void aCorrectionCarriesOnlyAnExplicitPreviousFacilityLocality() {
        DestinationPhraseParser.Result corrected =
                DestinationPhraseParser.parseWithPreviousDestinationCorrection(
                        "아니 내과로", "부산 금정구 서동 피부과");
        assertEquals("부산 금정구 서동 내과", corrected.destination);

        assertEquals("내과", DestinationPhraseParser.parseWithPreviousDestinationCorrection(
                "아니 내과로", "서울역").destination);
        assertEquals("내과", DestinationPhraseParser.parseWithPreviousDestinationCorrection(
                "내과로", "부산 금정구 서동 피부과").destination);
        assertEquals("내과", DestinationPhraseParser.parse(
                "서울역 말고 내과로").destination);
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

    @Test public void refusesToSearchAnUnresolvedPreviousPlaceReference() {
        for (String utterance : new String[]{
                "아까 그곳으로 가자", "전에 갔던 병원으로 가줘", "그쪽으로 데려가",
                "그 약국으로 가자", "그 피부과로 데려가", "거기로 가고 싶어요",
                "거기 있는 피부과 한 번 검색해 줘", "그 아래 동네 피부과 데려가 줘",
                "그쪽 근처 병원 찾아봐", "거기 동네 피부과"}) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(utterance);
            assertFalse(utterance, result.isUsable());
            assertTrue(utterance, result.message.contains("장소 이름"));
        }
        assertTrue(DestinationPhraseParser.parse("병원에 가자").isUsable());
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

    @Test public void simulatedSeniorEverydayTakeMeTherePhrasesKeepCommandsOutOfSearchQuery() {
        String[][] cases = {
                {"부산 금정구 서동 피부과 데려가", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과로 데려가요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과로 데려가 주세요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과로 데려가 줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 좀 찾아봐", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 검색해줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 검색 좀 해줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 좀 알려줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과로 부탁해", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 좀 부탁해", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 알아봐 줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과를 좀 찾아봐 줘", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과를 검색해 주세요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과에 한번 데려다 주세요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 한 번 데려다 주실래요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과 한 번 모셔다 주세요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과로 가실래요", "부산 금정구 서동 피부과"},
                {"부산 금정구 서동 피부과를 안내해 주세요", "부산 금정구 서동 피부과"},
                {"서동에 있는 피부과로 데려가", "서동에 있는 피부과"},
                {"검색해 줘 서동 피부과 부산 금정구 말이야", "서동 피부과 부산 금정구"},
                {"피부과 서동 쪽 데려다 줘", "피부과 서동"},
                {"서동 피부과, 그거 검색해 줘", "서동 피부과"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void commandFirstAndConversationalCorrectionPhrasesKeepTheFullPlaceIntent() {
        String[][] cases = {
                {"데려다 줘, 서동 피부과", "서동 피부과"},
                {"찾아봐, 부산 금정구 서동 피부과", "부산 금정구 서동 피부과"},
                {"검색해 줘 서동 피부과 부산 금정구 말이야", "서동 피부과 부산 금정구"},
                {"금정구 아니고 그냥 서동 피부과 찾아 줘", "서동 피부과"}
        };
        for (String[] scenario : cases) {
            DestinationPhraseParser.Result result = DestinationPhraseParser.parse(scenario[0]);
            assertTrue(scenario[0], result.isUsable());
            assertEquals(scenario[0], scenario[1], result.destination);
        }
    }

    @Test public void asksWhenAnActionWordIsEmbeddedBetweenAreaAndFacility() {
        DestinationPhraseParser.Result result =
                DestinationPhraseParser.parse("서동 가자 피부과 찾아 줘");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("장소 이름"));
    }

    @Test public void conflictingRepeatedAreaCorrectionsAreHeldInsteadOfChoosingTheLastArea() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse(
                "금정구 아니고 해운대구 말고 서동 피부과 찾아 줘");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("동네가 여러 곳"));

        DestinationPhraseParser.Result areaDroppedByLastCorrection = DestinationPhraseParser.parse(
                "금정구 아니고 해운대구 말고 피부과 찾아 줘");
        assertFalse(areaDroppedByLastCorrection.isUsable());
        assertTrue(areaDroppedByLastCorrection.message.contains("동네가 여러 곳"));

        assertEquals("서울 종로구 약국", DestinationPhraseParser.parse(
                "부산 금정구 서동 피부과 말고 서울 종로구 약국").destination);
        assertEquals("해운대구 피부과", DestinationPhraseParser.parse(
                "금정구 말고 해운대구 피부과").destination);
        assertEquals("서울 종로구 약국", DestinationPhraseParser.parse(
                "부산 금정구 피부과 말고 서울 종로구 약국 말고 약국").destination);
        assertEquals("부산 동래구 약국", DestinationPhraseParser.parse(
                "부산 금정구 피부과 말고 서울 종로구 약국 말고 부산 동래구 약국").destination);

        for (String utterance : new String[]{
                "부산 금정구 피부과 말고 서울 종로구 말고 약국",
                "금정구 말고 해운대구 말고 약국"}) {
            DestinationPhraseParser.Result unresolved = DestinationPhraseParser.parse(utterance);
            assertFalse(utterance, unresolved.isUsable());
        }
    }

    @Test public void asksWhichPlaceTypeWhenCorrectionAlsoAddsAnotherCategory() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse(
                "부산 금정구 피부과 말고 내과 그리고 약국");
        assertFalse(result.isUsable());
        assertTrue(result.message.contains("장소 종류가 여러 가지"));

        DestinationPhraseParser.Result hospitalAndPharmacy = DestinationPhraseParser.parse(
                "부산 금정구 병원 그리고 약국");
        assertFalse(hospitalAndPharmacy.isUsable());
    }

    @Test public void repeatedFacilityCorrectionsKeepExplicitLocalityAndOnlyFinalFacility() {
        DestinationPhraseParser.Result result = DestinationPhraseParser.parse(
                "부산 금정구 서동 피부과 말고 내과 아니고 이비인후과로");
        assertTrue(result.isUsable());
        assertEquals("부산 금정구 서동 이비인후과", result.destination);
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
        for (String marker : new String[]{"빼고", "말구", "대신"}) {
            assertEquals(marker, "강남역", DestinationPhraseParser.destinationAfterCorrection(
                    "서울역 " + marker + " 강남역"));
        }
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
