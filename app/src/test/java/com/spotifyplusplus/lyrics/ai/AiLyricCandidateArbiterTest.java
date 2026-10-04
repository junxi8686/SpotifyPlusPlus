package com.spotifyplusplus.lyrics.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AiLyricCandidateArbiterTest {
    @Test
    public void aBareNumberIsTheAnswer() {
        assertEquals(2, AiLyricCandidateArbiter.parsePick("3", 6));
        assertEquals(0, AiLyricCandidateArbiter.parsePick("1", 6));
    }

    @Test
    public void zeroMeansNoneOfThem() {
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("0", 6));
    }

    @Test
    public void aSentenceAroundTheNumberIsStillAnAnswer() {
        // Refusing a reply that ignored the "number only" instruction would cost a second paid
        // call to learn the same thing.
        assertEquals(1, AiLyricCandidateArbiter.parsePick("The answer is 2.", 6));
        assertEquals(4, AiLyricCandidateArbiter.parsePick("**5** — same recording", 6));
    }

    @Test
    public void anOutOfRangeNumberIsNotAnAnswer() {
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("9", 6));
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("100", 6));
    }

    @Test
    public void garbageIsAnEmptyAnswerNotAnException() {
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("", 6));
        assertEquals(-1, AiLyricCandidateArbiter.parsePick(null, 6));
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("none of these", 6));
        assertEquals(-1, AiLyricCandidateArbiter.parsePick("3", 0));
    }

    @Test
    public void askingWithoutCandidatesNeverReachesTheModel() {
        // Null context and an empty list both short-circuit before any request is built.
        assertEquals(-1, AiLyricCandidateArbiter.pick(null, "t", "a", 0L,
                java.util.Collections.<AiLyricCandidateArbiter.Candidate>emptyList()));
    }
}
