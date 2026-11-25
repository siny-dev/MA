package com.siny.mobilebasic.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public final class DestinationConversationTest {
    private PlaceCandidate clinic() {
        return new PlaceCandidate("서동이종호피부과", "병원 > 피부과", "부산 금정구 서동 123",
                "부산 금정구 서동로 10", 35.2134, 129.1056);
    }

    @Test public void positiveConfirmationLeavesOnlyVerifiedCandidateForHandoff() {
        DestinationConversation flow = new DestinationConversation();
        flow.beginSearch();
        assertEquals("서동에 있는 서동이종호피부과 맞나요?", flow.confirm(clinic()));
        assertTrue(clinic().confirmationDetails().contains("부산 금정구 서동로 10"));
        assertEquals(DestinationConversation.Reply.YES, flow.replyToConfirmation("네, 맞아요"));
        flow.markHandedOff();
        assertEquals(DestinationConversation.State.HANDED_OFF, flow.state());
        assertEquals("서동이종호피부과", flow.candidate().name);
    }

    @Test public void plainNoClearsCandidateAndDoesNotRepeatSameQuestion() {
        DestinationConversation flow = new DestinationConversation();
        flow.confirm(clinic());
        assertEquals(DestinationConversation.Reply.NO, flow.replyToConfirmation("아니요"));
        assertEquals(DestinationConversation.State.IDLE, flow.state());
        assertNull(flow.candidate());
    }

    @Test public void commonKoreanPositivePhrasesAllowOnlyTheCurrentCandidate() {
        for (String answer : Arrays.asList("네", "예", "응", "맞아요", "네 맞습니다", "맞는 것 같아요", "네 그렇게 해주세요")) {
            DestinationConversation flow = new DestinationConversation();
            flow.confirm(clinic());
            assertEquals(answer, DestinationConversation.Reply.YES, flow.replyToConfirmation(answer));
            flow.markHandedOff();
            assertEquals(DestinationConversation.State.HANDED_OFF, flow.state());
        }
    }

    @Test public void assistantCanClassifyNaturalConversationalAcceptance() {
        DestinationConversation flow = new DestinationConversation();
        flow.confirm(clinic());
        assertEquals(DestinationConversation.Reply.UNCLEAR, flow.replyToConfirmation("네, 거기로 가자"));
        assertEquals(DestinationConversation.Reply.YES, flow.applyAssistantConfirmation("yes"));
        flow.markHandedOff();
        assertEquals(DestinationConversation.State.HANDED_OFF, flow.state());
    }

    @Test public void assistantRejectionAndCorrectionNeverKeepTheRejectedCandidate() {
        DestinationConversation rejected = new DestinationConversation();
        rejected.confirm(clinic());
        assertEquals(DestinationConversation.Reply.NO, rejected.applyAssistantConfirmation("no"));
        assertNull(rejected.candidate());

        DestinationConversation corrected = new DestinationConversation();
        corrected.confirm(clinic());
        assertEquals(DestinationConversation.Reply.CORRECTION, corrected.applyAssistantConfirmation("correction"));
        assertNull(corrected.candidate());
    }

    @Test public void commonKoreanNegativePhrasesNeverHandOffTheRejectedCandidate() {
        for (String answer : Arrays.asList("아니", "아니요", "아니오", "아니에요", "그게 아니야")) {
            DestinationConversation flow = new DestinationConversation();
            flow.confirm(clinic());
            assertEquals(answer, DestinationConversation.Reply.NO, flow.replyToConfirmation(answer));
            assertNull(flow.candidate());
            assertEquals(DestinationConversation.State.IDLE, flow.state());
        }
    }

    @Test public void spokenCorrectionStartsFreshSearchInsteadOfRepeatingTheRejectedPlace() {
        DestinationConversation flow = new DestinationConversation();
        flow.confirm(clinic());
        assertEquals(DestinationConversation.Reply.CORRECTION,
                flow.replyToConfirmation("아니 피부과 말고 약국"));
        assertEquals(DestinationConversation.State.IDLE, flow.state());
        assertNull(flow.candidate());
    }

    @Test public void correctionSayingAddressWasWrongDoesNotLoopOnSameConfirmation() {
        DestinationConversation flow = new DestinationConversation();
        flow.confirm(clinic());
        assertEquals(DestinationConversation.Reply.CORRECTION,
                flow.replyToConfirmation("주소가 잘못됐어요"));
        assertNull(flow.candidate());
        assertEquals(DestinationConversation.State.IDLE, flow.state());
    }

    @Test public void unclearConfirmationCanOnlyBeRepeatedOnce() {
        DestinationConversation flow = new DestinationConversation();
        flow.confirm(clinic());
        assertEquals(DestinationConversation.Reply.UNCLEAR, flow.replyToConfirmation("잘 모르겠어"));
        assertEquals(DestinationConversation.Reply.ABORTED, flow.replyToConfirmation("음"));
        assertEquals(DestinationConversation.State.IDLE, flow.state());
        assertNull(flow.candidate());
    }

    @Test public void staleConfirmationExpiresAndCannotAcceptLateYes() {
        AtomicLong clock = new AtomicLong(1_000L);
        DestinationConversation flow = new DestinationConversation(clock::get);
        flow.confirm(clinic());
        clock.addAndGet(121_000_000_000L);
        assertTrue(flow.expirePendingDestinationIfStale());
        assertEquals(DestinationConversation.State.IDLE, flow.state());
        assertNull(flow.candidate());
        assertEquals(DestinationConversation.Reply.UNCLEAR, flow.replyToConfirmation("네"));
    }

    @Test public void staleMultipleChoiceExpiresBeforeAnOldNumberCanSelectAPlace() {
        AtomicLong clock = new AtomicLong(1_000L);
        DestinationConversation flow = new DestinationConversation(clock::get);
        flow.offerChoices(Arrays.asList(clinic(), new PlaceCandidate("다른 피부과", "병원 > 피부과",
                "부산 금정구 서동 222", "부산 금정구 서동로 20", 35.2140, 129.1060)));
        clock.addAndGet(121_000_000_000L);
        assertTrue(flow.expirePendingDestinationIfStale());
        assertEquals(DestinationConversation.State.IDLE, flow.state());
        assertNull(flow.selectByVoice("1번"));
    }

    @Test public void userCanChooseAmongMultipleNaverPlacesByVoice() {
        DestinationConversation flow = new DestinationConversation();
        PlaceCandidate first = clinic();
        PlaceCandidate second = new PlaceCandidate("서동우리피부과", "병원 > 피부과", "부산 금정구 서동 222",
                "부산 금정구 서동로 20", 35.2140, 129.1060);
        assertEquals(2, flow.offerChoices(Arrays.asList(first, second)).size());
        String question = flow.selectByVoice("두 번째");
        assertTrue(question.contains("서동우리피부과"));
        assertEquals(DestinationConversation.State.AWAITING_CONFIRMATION, flow.state());
        assertEquals("서동우리피부과", flow.candidate().name);
    }

    @Test public void placesWithoutARealKoreanAddressAndCoordinateCannotBeNavigatedTo() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceCandidate("임의 장소", "", "", "", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceCandidate("해외 장소", "", "주소", "", 40.0, 127.0));
    }
}
