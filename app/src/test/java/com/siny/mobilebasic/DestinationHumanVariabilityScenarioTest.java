package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Deterministic, adversarial conversation scenarios through the production decision parsers. */
public final class DestinationHumanVariabilityScenarioTest {
    private static final String[] AREAS = {
            "서울 종로구", "서울 강남구", "부산 금정구 서동", "부산 동래구 온천동",
            "대구 수성구 범어동", "인천 남동구 구월동", "광주 북구 운암동", "대전 서구 둔산동",
            "울산 남구 삼산동", "세종 나성동", "제주 제주시 연동", "경기 수원시 영통구",
            "강원도 강릉시", "충북 청주시 흥덕구", "충남 천안시 서북구", "전북 전주시 완산구",
            "전남 순천시", "경북 포항시 북구", "경남 창원시 성산구", "경기 고양시 일산동구"
    };

    private static final String[] PLACE_TYPES = {
            "피부과", "정형외과", "치과", "안과", "내과", "약국", "시장", "주민센터",
            "우체국", "복지관", "대학병원", "보건소"
    };

    @Test public void variedSpeechAndAiCollapsesRetainExplicitAreaAndPlaceType() {
        String[] speechFrames = {
                "%s에 있는 %s", "%s %s 가자", "%s의 %s 좀 찾아줘",
                "저기 %s, %s", "%s 근처에 %s 찾아주세요", "엄마가 %s 쪽 %s로 가고 싶어"
        };
        int cases = 0;
        for (String area : AREAS) {
            for (String type : PLACE_TYPES) {
                for (String frame : speechFrames) {
                    String speech = String.format(Locale.KOREAN, frame, area, type);
                    DestinationPhraseParser.Result parsed = DestinationPhraseParser.parse(speech);
                    assertTrue("Parser rejected: " + speech, parsed.isUsable());

                    // Model common failure modes: it returns only the neighborhood or only the type.
                    String collapsed = cases % 2 == 0 ? lastArea(area) : type;
                    String query = DestinationQueryGuard.preserveExplicitIntent("", speech, collapsed);
                    String normalized = normalize(query);
                    assertTrue("Area lost for: " + speech + " => " + query,
                            normalized.contains(normalize(area)));
                    assertTrue("Place type lost for: " + speech + " => " + query,
                            normalized.contains(normalize(type)));
                    cases++;
                }
            }
        }
        assertTrue("Scenario matrix unexpectedly shrank", cases >= 1400);
    }

    @Test public void repeatedCorrectionsAlwaysKeepTheLatestAreaAndCategory() {
        int cases = 0;
        String[] correctionFrames = {
                "%s %s 말고 %s %s", "%s %s 아니고 %s %s", "아니요, %3$s %4$s로"
        };
        for (int oldArea = 0; oldArea < AREAS.length; oldArea++) {
            for (int newArea = 0; newArea < AREAS.length; newArea++) {
                if (oldArea == newArea) continue;
                for (int oldType = 0; oldType < PLACE_TYPES.length; oldType++) {
                    for (int newType = 0; newType < PLACE_TYPES.length; newType++) {
                        if (oldType == newType) continue;
                        for (int frame = 0; frame < correctionFrames.length; frame++) {
                            String utterance = String.format(Locale.KOREAN, correctionFrames[frame],
                                    AREAS[oldArea], PLACE_TYPES[oldType],
                                    AREAS[newArea], PLACE_TYPES[newType]);
                            String previous = AREAS[oldArea] + " " + PLACE_TYPES[oldType];
                            // Deliberately stale model output tests that corrections dominate it.
                            String query = DestinationQueryGuard.preserveExplicitIntent(
                                    previous, utterance, previous);
                            String normalized = normalize(query);
                            assertTrue("Latest area lost: " + utterance + " => " + query
                                            + "; expected=" + AREAS[newArea] + "; normalized=" + normalized,
                                    normalized.contains(normalize(AREAS[newArea])));
                            assertTrue("Latest category lost: " + utterance + " => " + query,
                                    normalized.contains(normalize(PLACE_TYPES[newType])));
                            assertFalse("Old area remained: " + utterance + " => " + query,
                                    normalized.contains(normalize(AREAS[oldArea]))
                                            && !AREAS[oldArea].equals(AREAS[newArea]));
                            assertFalse("Old category remained: " + utterance + " => " + query,
                                    normalized.contains(normalize(PLACE_TYPES[oldType]))
                                            && !PLACE_TYPES[oldType].equals(PLACE_TYPES[newType]));
                            cases++;
                        }
                    }
                }
            }
        }
        assertTrue("Correction matrix unexpectedly shrank", cases >= 100000);
    }

    @Test public void ordinaryWordsAndTransportTalkAreNotMistakenForAdministrativeAreas() {
        String[][] cases = {
                {"택시 타고 피부과 가고 싶어", "피부과"},
                {"피부과 다시 찾아줘", "피부과"},
                {"피부과 가려고 기다리고 있어", "피부과"},
                {"운동 후 피부과 가자", "피부과"},
                {"지하철역 출구 근처 약국", "지하철역 약국"},
                {"혹시 피부과 찾아줄래", "피부과"},
                {"부산에서 택시 타고 피부과 가자", "부산 피부과"}
        };
        for (String[] scenario : cases) {
            String query = DestinationQueryGuard.preserveExplicitIntent("", scenario[0], scenario[1]);
            assertEquals("False location inferred from: " + scenario[0],
                    normalize(scenario[1]), normalize(query));
        }
    }

    @Test public void mixedConfirmationsAndSpeechRecognitionUncertaintyNeverAutoApprove() {
        String[] unresolved = {
                "네?", "예?", "응, 어디라고?", "네, 주소가 어디야", "맞아요?",
                "네, 그런 것 같기도 해", "글쎄요", "잠깐만", "나중에", "잘 모르겠어요",
                "네, 그런데", "어, 네", "네, 맞는지 모르겠어요", "네, 저기가 맞나요",
                "첫 번째일까요 두 번째일까요", "네, 다시 말해 주세요", "아마 맞을 거예요",
                "주소가 맞는지 확인해 줘", "거기 말고 다른 데", "네, 다른 곳으로 해줘",
                "맞아요, 아니 잠깐", "아니면 저쪽인가", "그렇게 할까요", "음"
        };
        for (String response : unresolved) {
            DestinationConfirmationParser.Decision decision =
                    DestinationConfirmationParser.classify(response);
            assertTrue("Unsafe or overconfident classification for: " + response,
                    decision != DestinationConfirmationParser.Decision.YES);
        }

        String[] affirmativeButSpecific = {
                "네, 맞아요", "예, 그렇게 해주세요", "응, 안내 시작해", "좋아요, 출발해 주세요",
                "그렇습니다, 그곳으로 안내해 주세요", "맞습니다, 길 안내 시작해 주세요"
        };
        for (String response : affirmativeButSpecific) {
            assertEquals(response, DestinationConfirmationParser.Decision.YES,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void conflictingNBestTranscriptsStayUnresolvedRegardlessOfOrder() {
        String[] yes = {"네, 맞아요", "예, 그렇게 해주세요", "응, 출발해"};
        String[] no = {"아니요, 다른 곳이에요", "취소할게요", "아뇨, 그곳 말고"};
        for (String yesText : yes) {
            for (String noText : no) {
                assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                        DestinationConfirmationParser.classifyHypotheses(
                                java.util.Arrays.asList(yesText, noText)));
                assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                        DestinationConfirmationParser.classifyHypotheses(
                                java.util.Arrays.asList(noText, yesText)));
            }
        }
    }

    private static String lastArea(String area) {
        String[] parts = area.split("\\s+");
        return parts[parts.length - 1];
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\s]", "");
    }
}
