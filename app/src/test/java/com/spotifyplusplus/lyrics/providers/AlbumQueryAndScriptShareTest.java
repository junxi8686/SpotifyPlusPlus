package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.SpotifyTrack;

import org.junit.Test;

import java.util.List;

/**
 * The two behaviours taken from the reference implementation's handling:
 *
 * <ul>
 *   <li>its first query is title + artist + album, which we never asked;</li>
 *   <li>its script preference is {@code TraditionalChineseConfidence} - a share - where ours was a
 *       yes/no answer that could not order two Traditional editions against each other.</li>
 * </ul>
 */
public class AlbumQueryAndScriptShareTest {

    private static SpotifyTrack track(String title, String artist, String album) {
        return new SpotifyTrack(title, artist, album, "spotify:track:test", 0L, "", 0L, null,
                200000L, false);
    }

    // --- The album reaches the plan ------------------------------------------------------

    @Test
    public void theAlbumIsAskedAlongsideTitleAndArtist() {
        List<String> queries = SearchQueryVariants.queries(
                track("Take Me Hand", "Cecile Corbel", "Take Me Hand"));
        assertTrue("the album must be part of some query: " + queries,
                queries.contains("Take Me Hand Cecile Corbel Take Me Hand"));
    }

    @Test
    public void theAlbumQuerySitsAfterTheSimplifiedOne() {
        // The owner's first requirement is which script gets read, and the album query does not
        // change that, so Simplified still leads.
        List<String> queries = SearchQueryVariants.queries(
                track("離開地球表面", "五月天", "後青春期的詩"));
        assertEquals("离开地球表面 五月天", queries.get(0));
        assertTrue("and the album query follows it: " + queries,
                queries.indexOf("離開地球表面 五月天 後青春期的詩") > 0);
    }

    @Test
    public void aBlankAlbumDoesNotProduceADanglingQuery() {
        List<String> queries = SearchQueryVariants.queries(track("Song", "Artist", ""));
        assertTrue(queries.toString(), queries.contains("Song Artist"));
        for (String query : queries) {
            assertEquals("no query may end or begin with a space: " + queries,
                    query.trim(), query);
            assertTrue("no double spaces: " + queries, !query.contains("  "));
        }
    }

    @Test
    public void theAlbumDoesNotPushThePlanPastItsCap() {
        // MAX_QUERIES rose by one when the album query was added, so nothing already in the plan is
        // truncated off the end.
        List<String> queries = SearchQueryVariants.queries(
                track("離開地球表面 (Live)", "五月天", "後青春期的詩"));
        assertTrue(queries.size() <= SearchQueryVariants.MAX_QUERIES);
        assertTrue("the trailing variants must survive the new entry: " + queries,
                queries.contains("离开地球表面 (Live) 五月天")
                        || queries.contains("離開地球表面 (Live) 五月天"));
    }

    // --- Script preference is a share, not a flag ----------------------------------------

    @Test
    public void onlyTheCharactersThatActuallyDifferCount() {
        // 離開 differs from 离开; 地球表面 is written the same in both scripts. So this title is 2 of
        // 5, not "fully Traditional" - precisely the distinction a boolean threw away, since it
        // answered "contains Traditional" for this and for a title that is Traditional throughout.
        // 離開地球表面 is six characters: 離開 differ from 离开, 地球表面 is written the same in
        // both scripts. So the share is two of six, not "fully Traditional" - precisely the
        // distinction a boolean threw away, since it answered "contains Traditional" for this and
        // for a title that is Traditional throughout.
        assertEquals(2.0d / 6.0d,
                ChineseScriptVariants.traditionalConfidence("離開地球表面"), 0.001d);
    }

    @Test
    public void aTitleTraditionalThroughoutReadsAsOne() {
        // 離開臺灣 is 离开台湾 in Simplified: every Han character differs.
        assertEquals(1.0d, ChineseScriptVariants.traditionalConfidence("離開臺灣"), 0.001d);
    }

    @Test
    public void aSimplifiedTitleReadsAsZero() {
        assertEquals(0.0d, ChineseScriptVariants.traditionalConfidence("离开地球表面"), 0.001d);
    }

    @Test
    public void textWithNoHanCharactersReadsAsZero() {
        // Otherwise an English or Japanese title would rank as fully Traditional and lose to
        // everything, which is worse than having no preference at all.
        assertEquals(0.0d, ChineseScriptVariants.traditionalConfidence("Take Me Hand"), 0.001d);
        assertEquals(0.0d, ChineseScriptVariants.traditionalConfidence("君の名前を呼んでいる"), 0.001d);
        assertTrue(ChineseScriptVariants.traditionalConfidence("") == 0.0d);
        assertTrue(ChineseScriptVariants.traditionalConfidence(null) == 0.0d);
    }

    @Test
    public void twoTraditionalTitlesAreOrderedAgainstEachOther() {
        // This is the whole point: a boolean called both of these "not Simplified" and could not
        // choose between them. 周杰倫 is 二 of three Han characters Traditional; the other is all
        // three, so the first is the more Simplified edition and must sort ahead.
        double partly = ChineseScriptVariants.traditionalConfidence("周杰倫");
        double fully = ChineseScriptVariants.traditionalConfidence("周杰倫說");
        assertTrue("partial Traditional must score below full: " + partly + " vs " + fully,
                partly < fully);
        assertTrue(partly > 0.0d && partly < 1.0d);
    }

    @Test
    public void theShareIgnoresPunctuationAndLatinText() {
        // Only Han characters count, so a bracket or an English word cannot dilute the share.
        double withNoise = ChineseScriptVariants.traditionalConfidence("離開地球表面 (Live)");
        double plain = ChineseScriptVariants.traditionalConfidence("離開地球表面");
        assertEquals(plain, withNoise, 0.001d);
    }
}
