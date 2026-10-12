package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The edition chosen between providers follows the script its lyrics are actually written in.
 *
 * <p>The script leads it, and the graded component is why: a document with no Traditional-only
 * character takes three points, a trace of them two, a fifth one, a Traditional edition none - while
 * a complete provider translation is worth two. A fully translated Traditional edition therefore
 * still finishes behind a plain Simplified one, which is the owner's intent: what gets read first is
 * the text, and the translation is a companion to it. What is pinned here is also the criterion that
 * used to be missing: the per-provider ranking reads the script of the *lines*, where a catalogue can
 * serve a Simplified track name over Traditional lyrics. Without it the enum order decided, so
 * whichever provider sat earlier won regardless of what its text looked like.
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
     *
     * <p>{@code translation} writes a provider translation onto every line, because the ranking
     * measures what a provider translated rather than whether one did: a row that claimed the flag
     * with nothing behind it would score nothing for translation and these tests would be asserting
     * the opposite of what they say.
     */
    private static String encode(String lyrics, boolean translation) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "track";
        for (String part : lyrics.split("\n")) {
            LyricsLine line = new LyricsLine();
            line.text = part;
            if (translation) {
                line.providerTranslatedText = "translated: " + part;
                line.providerTranslationLanguage = "en";
            }
            document.lines.add(line);
        }
        return CanonicalSourceCodec.encode(document, 1, "digest", 0L);
    }

    private static CatalogCandidate cand(SourceId source, String lyrics, boolean translation) {
        return cand(source, lyrics, translation, translation);
    }

    /**
     * As above, with the provider translation flag stated separately from the coverage.
     *
     * <p>{@code translation} is what the row claims; {@code translatedDocument} is whether its lines
     * actually carry one. The two differ so that the script grade can be measured with the
     * translation portion of the total held equal between the pair being compared; otherwise a row
     * that claimed a translation it does not have would make the comparison about the translation
     * component instead of about the script.
     */
    private static CatalogCandidate cand(SourceId source, String lyrics, boolean translation,
                                         boolean translatedDocument) {
        String id = source.id + "|item|d" + (nextId++) + "#" + nextId;
        return new CatalogCandidate(id, "track", source, "item", MatchMethod.STRONG_SEARCH, 0.9, 0L,
                TimingLevel.WORD, true, true, translation, false, false, false, false,
                "digest" + nextId, encode(lyrics, translatedDocument), "[]", new byte[0], 1, 1, 0L);
    }

    private static String winner(CatalogCandidate a, CatalogCandidate b) {
        return CatalogResolver.resolve(Arrays.asList(a, b), null,
                Collections.<SourceId>emptyList()).winner.candidateId;
    }

    @Test
    public void simplifiedLyricsBeatTraditionalOnesWhoseProviderRanksEarlier() {
        // QQ sits earlier in the enum than NetEase, so before this criterion QQ's Traditional text
        // won on provider order alone.
        CatalogCandidate traditional = cand(SourceId.QQ, "[00:01.00]離開地球表面說好的幸福呢", false);
        CatalogCandidate simplified = cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢", false);
        assertEquals("the Simplified edition must win",
                simplified.candidateId, winner(traditional, simplified));
        assertEquals("and symmetrically",
                simplified.candidateId, winner(simplified, traditional));
    }

    @Test
    public void aPartlyTraditionalEditionLosesToAFullySimplifiedOne() {
        // Both contain some Traditional text; only the share can order them.
        CatalogCandidate partly = cand(SourceId.QQ, "[00:01.00]離開地球表面說好的幸福呢 我们都一样", false);
        CatalogCandidate clean = cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢 我们都一样", false);
        assertEquals(clean.candidateId, winner(partly, clean));
    }

    @Test
    public void scriptOutranksAFullTranslation() {
        // The worked pair for the two components between them. A fully translated Traditional
        // document scores 4 fill + 0 script + 2 translation + 3 duration + 1 complete = 10 against
        // the untranslated Simplified one's 4 + 5 + 0 + 3 + 1 = 13, so the script grade wins even
        // against a complete translation - the ordering the owner set when the script component
        // became worth up to five and the translation stayed worth two. Both rows are search
        // matches, so neither collects the match points.
        CatalogCandidate translated = cand(SourceId.QQ, "[00:01.00]離開地球表面說好的幸福呢", true);
        CatalogCandidate plain = cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢", false);
        assertEquals("the plain Simplified row is three points ahead",
                CatalogResolver.score(translated) + 3, CatalogResolver.score(plain), 0.0001d);
        assertEquals("so it wins even though the translated row carries a full translation",
                plain.candidateId, winner(translated, plain));
    }

    @Test
    public void scriptOnlyDecidesWhenTheRestOfTheTableIsLevel() {
        // The script grade is the only thing between these two, because both editions carry the same
        // full translation and the same source - so the whole grade is the difference, and the seat
        // is the Simplified edition's whichever provider serves it.
        CatalogCandidate traditional = cand(SourceId.NETEASE, "[00:01.00]離開地球表面說好的幸福呢", true, true);
        CatalogCandidate simplified = cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢", true, true);
        assertTrue(CatalogResolver.lyricsTraditionalShare(traditional) > 0d);
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(simplified), 0.0001d);
        assertEquals("the whole script grade, nothing else",
                CatalogResolver.score(simplified) - CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.score(traditional), 0.0001d);
        assertEquals("so the Simplified edition takes the seat",
                simplified.candidateId, winner(traditional, simplified));
        assertEquals("and symmetrically", simplified.candidateId, winner(simplified, traditional));
    }

    @Test
    public void aDocumentWithNoHanCharactersReadsAsZeroNotAsTraditional() {
        // The important half: a Latin-script document must not measure as fully Traditional and
        // lose to everything. 0 here means "nothing to prefer".
        assertTrue(CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.QQ, "[00:01.00]Darling won't you break my heart", false)) == 0.0d);
        assertTrue(CatalogResolver.lyricsTraditionalShare(
                cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢", false)) == 0.0d);

        // What that zero does and does not buy. A Latin document pays nothing for script even though
        // it measures the same zero share, while a Simplified one takes the full grade - so an
        // English edition and a Simplified one are level on the measurement and three points apart
        // on the component: 0 is "no preference against you", not "the same as the preferred
        // script", and certainly not a perfect score for having no Han character to measure.
        CatalogCandidate english = cand(SourceId.QQ, "[00:01.00]Take me hand", false);
        CatalogCandidate simplified = cand(SourceId.NETEASE, "[00:01.00]离开地球表面说好的幸福呢", false);
        assertFalse("Latin text is not Simplified Chinese",
                CatalogResolver.isSimplifiedChinese(english));
        assertEquals("so its script component pays nothing", 0,
                CatalogResolver.points(english).script);
        assertEquals("and the Simplified row's takes the maximum",
                CatalogResolver.POINTS_SIMPLIFIED_MAX, CatalogResolver.points(simplified).script);
        assertEquals("which is the only component between them",
                CatalogResolver.score(english) + CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.score(simplified), 0.0001d);
        assertEquals("so the Simplified edition wins even though QQ leads the provider list",
                simplified.candidateId, winner(english, simplified));
        assertEquals("and symmetrically", simplified.candidateId, winner(simplified, english));
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
