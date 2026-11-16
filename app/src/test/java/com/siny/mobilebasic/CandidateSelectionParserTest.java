package com.siny.mobilebasic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CandidateSelectionParserTest {
    @Test public void recognizesSingleSpokenAndNumericChoices() {
        assertEquals(0, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("첫 번째요"), 3));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("2번으로 할게요"), 3));
        assertEquals(2, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("세 번째"), 3));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal("둘째", 3));
        assertEquals(3, CandidateSelectionParser.findExplicitOrdinal("넷째요", 5));
    }

    @Test public void followsTheCorrectionInsteadOfChoosingTheFirstMentionedPlace() {
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("첫 번째 말고 두 번째"), 3));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("1번 아니고 2번"), 3));
        assertEquals("강남역", CandidateSelectionParser.relevantPhrase("서울역 아니고 강남역"));
        assertEquals("", CandidateSelectionParser.relevantPhrase("다섯 번째 말고"));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal(
                CandidateSelectionParser.relevantPhrase("여기 말고 두 번째"), 3));
    }

    @Test public void refusesConflictingOrUnavailableChoices() {
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal(
                        CandidateSelectionParser.relevantPhrase("첫 번째, 두 번째"), 3));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("다섯번째", 3));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("다섯째", 3));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("12번", 5));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("02번", 5));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("2번지", 5));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("2번길", 5));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("2번가로", 5));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal("2번가주세요", 5));
        assertEquals(1, CandidateSelectionParser.findExplicitOrdinal("2번이요", 5));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("1번출구", 3));
        assertEquals(CandidateSelectionParser.AMBIGUOUS,
                CandidateSelectionParser.findExplicitOrdinal("두번째호선", 3));
    }
}
