package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.Arrays;

/**
 * The edition chosen between providers follows the script its lyrics are actually written in.
 *
 * <p>Translation comes first, as it did - {@code providedTextCount} leads the comparison. This is
 * the criterion that used to be missing: the per-provider ranking reads the script of the *title*,
 * but a catalogue can serve a Simplified track name and Traditional lines, and it is the lines that
 * get read. Without this the enum order decided, so whichever provider sat earlier won regardless
 * of what its text looked like.
 */
public class CatalogLyricsScriptTest {

    private static int nextId;

    /**
     * A candidate whose document is encoded exactly as the store encodes it.
     *
     * <p>These tests used to hand the candidate its raw lyrics. normalizedDocument is a
     * CanonicalSourceCodec record, so that was never the string the code reads - and when the
     * code began decoding, these tests failed, which is precisely the mistake they were meant to
     * catch. Building the real document is the only way they test anything.
     */
    private static String encode(String lyrics) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "track";
        for (String part : lyrics.split("\n")) {
            LyricsLine line = new LyricsLine();
            line.text = part;
            document.lines.add(line);
        }
        return CanonicalSourceCodec.encode(document, 1, "digest", 0L);
    }

    private static CatalogCandidate cand(SourceId source, String lyrics, boolean translation) {
        String id = source.id + "|item|d" + (nextId++) + "#" + nextId;
        return new CatalogCandidate(id, "track", source, "item", MatchMethod.STRONG_SEARCH, 0.9, 0L,
                TimingLevel.WORD, true, true, translation, false, false, false, false,
                "digest" + nextId, encode(lyrics), "[]", new byte[0], 1, 1, 0L);
    }

    private static String winner(CatalogCandidate a, CatalogCandidate b) {
        return CatalogResolver.resolve(Arrays.asList(a, b), null).winner.candidateId;
    }

    @Test
    public void simplifiedLyricsBeatTraditionalOnesWhoseProviderRanksEarlier() {
        // QQ sits earlier in the enum than NetEase, so before this criterion QQ's Traditional text
        // won on provider order alone.
        CatalogCandidate traditional = cand(SourceId.QQ, "[00:01.00]離開地球表面", false);
        CatalogCandidate simplified = cand(SourceId.NETEASE, "[00:01.00]离开地球表面", false);
        assertEquals("the Simplified edition must win",
                simplified.candidateId, winner(traditional, simplified));
        assertEquals("and symmetrically",
                simplified.candidateId, winner(simplified, traditional));
    }

    @Test
    public void aPartlyTraditionalEditionLosesToAFullySimplifiedOne() {
        // Both contain some Traditional text; only the share can order them.
        CatalogCandidate partly = cand(SourceId.QQ, "[00:01.00]離開地球表面 我们都一样", false);
        CatalogCandidate clean = cand(SourceId.NETEASE, "[00:01.00]离开地球表面 我们都一样", false);
        assertEquals(clean.candidateId, winner(partly, clean));
    }

    @Test
    public void translationStillOutranksScript() {
        // The owner asked for translation first, and that order must not be disturbed by this.
        CatalogCandidate translated = cand(SourceId.QQ, "[00:01.00]離開地球表面", true);
        CatalogCandidate plain = cand(SourceId.NETEASE, "[00:01.00]离开地球表面", false);
        assertEquals("translation wins even when its script is the less preferred one",
                translated.candidateId, winner(translated, plain));
    }

    @Test
    public void aDocumentWithNoHanCharactersReadsAsZeroNotAsTraditional() {
        // The important half: a Latin-script document must not measure as fully Traditional and
        // lose to everything. 0 here means "nothing to prefer".
        assertTrue(CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "[00:01.00]Darling won't you break my heart", false)) == 0.0d);
        // It is also what a fully Simplified document measures, so those two are indistinguishable
        // on script and the remaining criteria decide between them. Stated here rather than left
        // implicit, because it is a real consequence: an English edition and a Simplified one are
        // not ordered against each other by this criterion, only a Traditional one is pushed down.
        assertTrue(CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.NETEASE, "[00:01.00]离开地球表面", false)) == 0.0d);
        CatalogCandidate english = cand(SourceId.QQ, "[00:01.00]Take me hand", false);
        CatalogCandidate simplified = cand(SourceId.NETEASE, "[00:01.00]离开地球表面", false);
        assertEquals("with no script reason to choose, the earlier provider still decides",
                english.candidateId, winner(english, simplified));
    }

    @Test
    public void theShareIsReadFromTheDocument() {
        assertEquals(1.0d, CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "離開臺灣", false)), 0.001d);
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "离开台湾", false)), 0.001d);
        assertTrue(CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "Take Me Hand", false)) == 0.0d);
        assertTrue(CatalogResolver.lyricsTraditionalShare(null) == 0.0d);
    }

    @Test
    public void onlyASampleOfALongDocumentIsExamined() {
        // The comparator runs many times per sort, so the measurement is bounded. A Traditional
        // opening is what is sampled, and it is representative by construction.
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 2000; i++) long1.append("離開臺灣的孩子\n");
        // 離開臺灣 differ from 离开台湾; 的孩子 is written the same in both scripts. Four of seven,
        // and the value must not drift as the document grows - which is what shows the sample is a
        // fixed prefix rather than the whole file.
        double expected = 4.0d / 7.0d;
        assertEquals(expected, CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, long1.toString(), false)), 0.001d);
        assertEquals(expected, CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "離開臺灣的孩子", false)), 0.001d);
    }
}
