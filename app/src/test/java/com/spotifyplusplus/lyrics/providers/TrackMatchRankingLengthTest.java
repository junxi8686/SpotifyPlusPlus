package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Runtime can overrule the word-lyric preference once the gap says the hit is a different recording.
 *
 * <p>The owner's report: QQ Music returned a hit carrying word-level lyrics but of the wrong length,
 * and it was used; NetEase had the right length but only line-level lyrics, and it was not. The
 * ordering treated every runtime difference as "another pressing of the same song", where the
 * word-level pressing is simply worth more - true for a few seconds, false for a gap wide enough
 * that the scorer awards the runtime nothing at all.
 */
public class TrackMatchRankingLengthTest {

    private static TrackMatchScorer.Score score(double identity, double total) {
        TrackMatchScorer.Score s = new TrackMatchScorer.Score(
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT, total, identity);
        return s;
    }

    private static int compare(boolean aWord, long aDiff, boolean bWord, long bDiff) {
        return TrackMatchScorer.compareForRanking(score(20d, 20d), aWord, aDiff,
                score(20d, 20d), bWord, bDiff);
    }

    @Test
    public void aWrongLengthWordHitLosesToACorrectLengthLineHit() {
        // QQ: word-level lyrics, but a runtime far off the playing track.
        boolean qqWord = true;
        long qqDiff = 27000L;
        // NetEase: line-level only, but the runtime matches.
        boolean neteaseWord = false;
        long neteaseDiff = 200L;

        int result = compare(qqWord, qqDiff, neteaseWord, neteaseDiff);
        assertTrue("the correctly-lengthed source must win", result > 0);
        assertEquals("and symmetrically", -1, compare(neteaseWord, neteaseDiff, qqWord, qqDiff));
    }

    @Test
    public void aFewSecondsOfDifferenceStillLosesToWordLyrics() {
        // Both plausible pressings: the word-level one is still worth more, which is the behaviour
        // that was already right and must not regress.
        assertTrue(compare(true, 2000L, false, 100L) < 0);
        assertTrue(compare(false, 100L, true, 2000L) > 0);
        // Just inside the threshold: still a pressing, so word lyrics still decide.
        assertTrue(compare(true, TrackMatchScorer.LENGTH_DISQUALIFYING_MS - 1, false, 0L) < 0);
    }

    @Test
    public void atTheThresholdTheLengthWins() {
        assertTrue(compare(true, TrackMatchScorer.LENGTH_DISQUALIFYING_MS, false, 0L) > 0);
    }

    @Test
    public void anUnknownRuntimeIsNeverDisqualifying() {
        // -1 means either side's runtime was unknown, which must not be read as "way off".
        assertTrue("unknown length must not disqualify a word hit",
                compare(true, -1L, false, 50L) < 0);
        assertTrue("nor a line hit", compare(false, -1L, true, 30000L) < 0);
    }

    @Test
    public void bothWrongLengthFallsBackToTheWordPreference() {
        // Neither is the right recording; there is nothing to prefer on length, so the original
        // rule still applies.
        assertTrue(compare(true, 90000L, false, 60000L) < 0);
    }

    @Test
    public void identityStillOutranksEverything() {
        int result = TrackMatchScorer.compareForRanking(
                score(25d, 5d), false, 0L,
                score(10d, 25d), true, 90000L);
        assertTrue("a stronger identity wins regardless of length or word lyrics", result < 0);
    }

    @Test
    public void theGateIsNotWhereLengthIsRefused() {
        // Score.accepted() grades the tier, whose loosest step is 3.5 s - the right line for
        // preferring a candidate, far too tight for refusing one, since providers disagree by that
        // much on the same recording all the time. The refusal is in the rankers, at
        // LENGTH_REJECT_MS, so those two thresholds must stay separate and different.
        assertTrue("the refusal threshold must be looser than the preference threshold",
                TrackMatchScorer.LENGTH_REJECT_MS > TrackMatchScorer.LENGTH_DISQUALIFYING_MS);
        TrackMatchScorer.Score wrongLength = new TrackMatchScorer.Score(
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.NONE, 16.8d, 21d);
        assertTrue("a 3.5 s gap must not refuse a hit by itself - that would report correct "
                + "sources as missing the track", wrongLength.accepted());
    }
}
