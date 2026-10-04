package com.spotifyplusplus.lyrics.providers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Title agreement across scripts.
 *
 * <p>This is the comparison that decides whether a provider's hit is the playing song at all, and
 * it is what made a track NetEase carries unfindable: Spotify reported the Traditional title,
 * NetEase indexes the Simplified one, and every branch of {@code compareName} is a raw string
 * comparison.
 */
public class TrackMatchScorerTitleTest {
    private static TrackMatchScorer.Tier compare(String candidate, String target) {
        return TrackMatchScorer.compareName(candidate, TrackMatchScorer.normalizeName(target));
    }

    /** The identity gate's title half, with a total that is never what refuses the hit. */
    private static boolean accepted(String candidate, String target) {
        return TrackMatchScorer.accept(compare(candidate, target), null, 100d);
    }

    @Test
    public void theSameTitleInTheOtherScriptIsTheSameTitle() {
        assertEquals(TrackMatchScorer.Tier.PERFECT, compare("从奇迹中诞生", "從奇蹟中誕生"));
        assertEquals(TrackMatchScorer.Tier.PERFECT, compare("從奇蹟中誕生", "从奇迹中诞生"));
    }

    @Test
    public void scriptFoldingAlsoCarriesTheSubtitleCase() {
        // The exact shape of the failure: a Traditional release title that also carries a
        // subtitle, against the bare Simplified title the provider indexes.
        assertTrue(accepted("从奇迹中诞生", "從奇蹟中誕生（2026嗶哩嗶哩拜年季主題曲）"));
        assertTrue(accepted("從奇蹟中誕生（2026嗶哩嗶哩拜年季主題曲）", "从奇迹中诞生"));
    }

    @Test
    public void aScriptDifferenceWithAFullSubtitleAlsoPasses() {
        assertTrue(accepted("从奇迹中诞生（2026哔哩哔哩拜年季主题曲）",
                "從奇蹟中誕生（2026嗶哩嗶哩拜年季主題曲）"));
    }

    @Test
    public void aHitWithoutTheSubtitleIsStillAccepted() {
        assertTrue(accepted("从奇迹中诞生", "從奇蹟中誕生（2026嗶哩嗶哩拜年季主題曲）"));
    }

    @Test
    public void foldingDoesNotMakeDifferentSongsAcceptable() {
        assertFalse(accepted("反乌托邦", "从奇迹中诞生"));
        assertFalse(accepted("Dystopia", "Bohemian Rhapsody"));
        assertFalse(accepted("", "从奇迹中诞生"));
    }

    @Test
    public void aProviderWithoutTheTrackStillFails() {
        // Nothing to fold: both are already Simplified, and the provider simply has something else.
        assertFalse(accepted("星迹", "从奇迹中诞生"));
    }

    @Test
    public void anExactTitleAloneIsStillRefusedOverAnUnrelatedArtist() {
        // The conservative half of the trade, kept deliberately. Relaxing it was tried and reverted:
        // an exact title plus a matching runtime admitted a different recording whose credit was
        // merely spelled another way, and the reader got someone else's lyrics. A credit that
        // genuinely differs is AiLyricCandidateArbiter's call, not a threshold's.
        assertFalse(TrackMatchScorer.accept(TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.Tier.NONE, 100d));
        assertTrue(TrackMatchScorer.accept(TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.Tier.LOW, 100d));
    }

    @Test
    public void aGenericCreditIsNothingToCompareRatherThanAMismatch() {
        // Spotify files compilations under "Various Artists"; the provider credits the performer.
        // That is not a disagreement about who made the recording, so it must not refuse the hit.
        TrackMatchScorer.Target target = new TrackMatchScorer.Target(
                "从奇迹中诞生", "Various Artists", "", 200_000L);
        assertTrue(target.artistUnknown());
        assertTrue(target.artists.isEmpty());
        assertTrue(new TrackMatchScorer.Target("x", "群星", "", 0L).artistUnknown());
        assertTrue(new TrackMatchScorer.Target("x", "OST", "", 0L).artistUnknown());
        // A real name is still compared.
        assertFalse(new TrackMatchScorer.Target("x", "五月天", "", 0L).artistUnknown());
    }
}
