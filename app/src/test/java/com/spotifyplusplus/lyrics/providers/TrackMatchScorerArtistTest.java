package com.spotifyplusplus.lyrics.providers;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * The last-resort credited-name comparison, which decides whether a provider's hit is usable at all.
 *
 * <p>Every case here is a real pair of spellings for one act: a rejected artist is not a lower
 * rank, it refuses the whole hit, so a catalogue spelling a name one character differently reads
 * as "this service does not have the song".
 */
public class TrackMatchScorerArtistTest {
    private static TrackMatchScorer.Tier tier(String target, String... found) {
        List<String> left = java.util.Collections.singletonList(target.toLowerCase(Locale.ROOT));
        List<String> right = new java.util.ArrayList<>();
        for (String name : found) right.add(name.toLowerCase(Locale.ROOT));
        return TrackMatchScorer.compareArtists(left, right);
    }

    @Test
    public void oneCharacterApartOnAMultiArtistCreditIsTheSameAct() {
        // Spotify and NetEase spell Bilibili's annual-show credit differently by one character,
        // and NetEase credits three acts where Spotify credits one.
        assertEquals(TrackMatchScorer.Tier.MEDIUM,
                tier("哔哩哔哩拜年季", "哔哩哔哩拜年纪", "幽舞越山", "hanser"));
    }

    @Test
    public void theSameActInTheOtherScriptIsAccepted() {
        // 周杰倫 in one catalogue, 周杰伦 in the other. Folding the script makes them equal.
        assertEquals(TrackMatchScorer.Tier.MEDIUM,
                tier("周杰倫", "周杰伦"));
        assertEquals(TrackMatchScorer.Tier.MEDIUM,
                tier("五月天", "五月天樂團"));
    }

    @Test
    public void aGenuinelyDifferentShortNameIsStillRejected() {
        // Three characters is the floor: below it a high ratio is ordinary, and the two names
        // below share two of three characters while being different acts.
        assertEquals(TrackMatchScorer.Tier.NONE, tier("五月天", "五月花"));
    }

    @Test
    public void anExactCreditStillWinsOutright() {
        assertEquals(TrackMatchScorer.Tier.PERFECT,
                tier("宇多田ヒカル", "宇多田ヒカル"));
    }

    @Test
    public void aSharedCreditAmongSeveralIsStillAcceptedAsBefore() {
        assertEquals(TrackMatchScorer.Tier.MEDIUM,
                tier("hanser", "哔哩哔哩拜年纪", "幽舞越山", "hanser"));
    }

    @Test
    public void aCompletelyUnrelatedLongNameStaysRejected() {
        assertEquals(TrackMatchScorer.Tier.NONE,
                tier("五月天", "周杰伦"));
        assertEquals(TrackMatchScorer.Tier.NONE,
                tier("radiohead", "the beatles"));
    }
}
