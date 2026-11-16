package com.siny.mobilebasic;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class DestinationConfirmationParserTest {
    @Test public void recognizesExplicitKoreanYesAnswers() {
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classify("네, 맞아요."));
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classify("맞습니다"));
    }

    @Test public void bareAcknowledgementsDoNotApproveAPlace() {
        for (String response : new String[]{"네", "예", "응", "어"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.UNCLEAR,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void recognizesNegativeAnswersBeforePositiveWords() {
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("아니요, 다른 곳이에요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("다른 병원으로 갈래요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("다른 후보를 보여 주세요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("아니요, 5번 말고"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("다섯 번째 말고"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("그 병원 말고"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("여기 말고"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("네, 그런데 저기는 말고요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("틀렸어요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("취소할게요"));
    }

    @Test public void choiceConnectorsAreNotMistakenForRefusals() {
        for (String response : new String[]{
                "이곳일까요, 아니면 저곳일까요?", "아니면 저쪽으로 안내해 주세요",
                "맞는 곳인지 아니면 다른 곳인지 모르겠어요", "아닐까요?"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.UNCLEAR,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void doesNotTreatUnclearSpeechAsConsent() {
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("어느 쪽이죠?"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네이버 지도"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify(""));
    }

    @Test public void aRecognizedNoAlwaysWinsOverAnEarlierYesOrFiller() {
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("네, 아니요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("어, 아니에요"));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classify("맞아요, 그런데 다른 곳이에요"));
    }

    @Test public void anAffirmationFollowedByUncertaintyDoesNotStartAService() {
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네, 잘 모르겠어요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("맞아요, 글쎄요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("응, 확실하지 않아요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네, 맞는 것 같아요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네, 잠깐만요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네, 다음에 할게요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("맞아요, 조금 더 생각할게요"));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classify("네, 지금은 안 돼요"));
    }

    @Test public void questionsAndRequestsToRepeatNeverCountAsTaxiOrRouteConsent() {
        for (String response : new String[]{
                "네? 뭐라고요?", "예, 어디라고요?", "어, 어느 동네죠?",
                "맞아요? 주소가 어디인데요?", "네, 다시 말씀해 주세요",
                "네, 확인해 주세요", "네, 목적지가 뭐예요?", "응, 왜요?"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.UNCLEAR,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void acceptsClearConsentWithACompleteShortCommand() {
        for (String response : new String[]{
                "네, 그렇게 해주세요", "예, 출발해 주세요", "응, 시작해", "좋아요", "그렇습니다",
                "네, 그곳으로 가요", "네, 거기로 가 주세요", "맞아요, 안내 시작해 주세요",
                "예, 이 주소로 길 안내 시작해 주세요", "응, 택시 불러줘"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.YES,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void doesNotAcceptUnrelatedOrIncompleteRoutingCommands() {
        for (String response : new String[]{
                "네, 그곳으로 갈까요", "네, 안내를 다시 해주세요", "네, 주소를 알려주세요",
                "네, 택시는 나중에 부를게요", "네, 그곳으로 갈지 모르겠어요"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.UNCLEAR,
                    DestinationConfirmationParser.classify(response));
        }
    }

    @Test public void resolvesNBestConfirmationWithoutIgnoringConflicts() {
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classifyHypotheses(
                        Arrays.asList("네, 그렇게 해주세요", "네, 거기로 안내해 주세요")));
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classifyHypotheses(
                        Arrays.asList("어", "네, 그렇게 해주세요", "네, 맞아요")));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classifyHypotheses(
                        Arrays.asList("아니요, 다른 곳이에요", "네, 맞아요", "네, 그렇게 해주세요")));
        assertEquals(DestinationConfirmationParser.Decision.UNCLEAR,
                DestinationConfirmationParser.classifyHypotheses(
                        Arrays.asList("네, 맞아요", "아니요, 다른 곳이에요")));
        assertEquals(DestinationConfirmationParser.Decision.NO,
                DestinationConfirmationParser.classifyHypotheses(
                        Arrays.asList("아니요, 취소해 주세요", "아니요")));
    }

    @Test public void questionWordEndingsRemainUnconfirmedEvenWithoutQuestionPunctuation() {
        for (String response : new String[]{
                "네, 주소가 어떻게 돼요", "네, 맞죠", "네, 여기 맞는 거죠",
                "예, 이 주소인가요", "어, 몇 번지예요"}) {
            assertEquals(response, DestinationConfirmationParser.Decision.UNCLEAR,
                    DestinationConfirmationParser.classify(response));
        }
        assertEquals(DestinationConfirmationParser.Decision.YES,
                DestinationConfirmationParser.classify("네, 그렇죠"));
    }
}
