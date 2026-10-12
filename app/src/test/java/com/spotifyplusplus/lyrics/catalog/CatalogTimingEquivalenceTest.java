package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.SyllableSegment;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.providers.LyricsParser;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two in-line timing tiers score level under the points table, and the providers really do send
 * one span per character for Chinese text.
 *
 * <p>This exists because the picker's Chinese labels call both tiers 逐字 while the retired tiered
 * comparison used to separate them for every document. SpicyLyrics.org leads the provider list and
 * serves Syllable; QQ and NetEase serve Word; ordering the two tiers apart meant the Traditional
 * Syllable document beat the Simplified Word ones every time, so the second half of
 * 时间轴精度优先，同级别时简体优先 - the Simplified preference at equal level - was unreachable for a
 * Chinese song.
 *
 * <p>{@code CatalogResolver.timingPoints} now pays both tiers four for every script, so the
 * collapse is no longer a rule the ranking applies: the tiers simply cannot differ by timing any
 * more, and what a seat turns on is the script point and the timeline. The premise behind that is
 * pinned here through the providers' own payloads rather than restated in a hand-built fixture - the
 * SpicyLyrics.org envelope and the NetEase YRC body below go through the real parsers, and the
 * end-to-end case builds its candidates with the real catalog adapter, which is the code that turns
 * a document type into a timing level.
 */
public class CatalogTimingEquivalenceTest {

    private static final String TRACK_ID = "4CP3p2wc4F1XIWJ6C3w2KH";
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "離開地球表面", "五月天", "離開地球表面", "spotify:track:" + TRACK_ID,
            0, "", 0, null, 259000, false);

    /** The same two Chinese lines as the YRC body in the tests below, in Simplified. */
    private static final String SIMPLIFIED_LINES =
            "[1000,1200](1000,200,0)离(1200,200,0)开(1400,200,0)地"
                    + "(1600,200,0)球(1800,200,0)表(2000,200,0)面\\n"
                    + "[3000,1200](3000,200,0)说(3200,200,0)好(3400,200,0)的"
                    + "(3600,200,0)幸(3800,200,0)福(4000,200,0)呢\\n";

    private static int nextId;

    @Test
    public void bothInLineTiersScoreFourForEveryScript() {
        // The collapse, as the table states it. There is no script and no tier for which one in-line
        // tier outscores the other, which is what makes the pair interchangeable for a Chinese song.
        assertEquals(TimingLevel.SYLLABLE.ordinal(), TimingLevel.SYLLABLE.comparisonRank(true));
        assertEquals(TimingLevel.SYLLABLE.ordinal(), TimingLevel.WORD.comparisonRank(true));
        assertEquals(TimingLevel.LINE.ordinal(), TimingLevel.LINE.comparisonRank(true));
        assertEquals(TimingLevel.UNSYNCED.ordinal(), TimingLevel.UNSYNCED.comparisonRank(true));

        CatalogCandidate hanSyllable = row(SourceId.NETEASE, TimingLevel.SYLLABLE, "離開地球表面說好的幸福呢");
        CatalogCandidate hanWord = row(SourceId.NETEASE, TimingLevel.WORD, "离开地球表面说好的幸福呢");
        assertEquals("one rank for Han, so no timing difference at all",
                0.0d, timingDiff(hanSyllable, hanWord), 0.0001d);

        assertTrue(compare(hanWord, row(SourceId.NETEASE, TimingLevel.LINE, "离开地球表面说好的幸福呢")) < 0);
        assertTrue(compare(row(SourceId.NETEASE, TimingLevel.LINE, "离开地球表面说好的幸福呢"),
                row(SourceId.NETEASE, TimingLevel.UNSYNCED, "离开地球表面说好的幸福呢")) < 0);
    }

    @Test
    public void theTwoInLineTiersAreScoredLevelForLatinToo() {
        // The limit of the old collapse. Latin word timing is genuinely finer than Latin syllable
        // timing - one span per word is not one span per syllable - and the table pays them the same
        // anyway, because the owner's table prices the fill a row draws and not how many spans went
        // into it. The old comparison kept the declaration order here.
        assertEquals("for Latin the word tier keeps its own, later rank",
                TimingLevel.WORD.ordinal(), TimingLevel.WORD.comparisonRank(false));
        assertTrue("while the syllable tier stays ahead of it",
                TimingLevel.SYLLABLE.comparisonRank(false) < TimingLevel.WORD.comparisonRank(false));

        CatalogCandidate syllable = row(SourceId.NETEASE, TimingLevel.SYLLABLE, "Take me hand");
        CatalogCandidate word = row(SourceId.NETEASE, TimingLevel.WORD, "Take me hand");
        assertEquals("so they score level and the stable id elects between them",
                0.0d, timingDiff(syllable, word), 0.0001d);
    }

    @Test
    public void aSimplifiedWordTimedEditionScoresAheadOfATraditionalSyllableTimedOne() {
        // The reported case. SpicyLyrics.org is first on the provider list and its document is
        // syllable-timed and Traditional; NetEase's is word-timed and Simplified. The two tiers score
        // level, so the script grade is the only component between them and it decides - against the
        // provider list.
        CatalogCandidate traditional = row(SourceId.SPICY_ORG, TimingLevel.SYLLABLE, "離開地球表面說好的幸福呢");
        CatalogCandidate simplified = row(SourceId.NETEASE, TimingLevel.WORD, "离开地球表面说好的幸福呢");

        assertEquals("the two tiers score level", 0.0d, timingDiff(traditional, simplified), 0.0001d);
        assertEquals("the Simplified edition is the full script grade ahead",
                CatalogResolver.score(traditional) + CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.score(simplified), 0.0001d);
        assertEquals("and must be elected",
                simplified.candidateId, winner(traditional, simplified));
        assertEquals("and symmetrically",
                simplified.candidateId, winner(simplified, traditional));
        assertTrue("decided against the provider list, which holds SpicyLyrics.org first",
                compare(traditional, simplified) > 0);
        assertTrue("while the provider list still decides when neither script is preferred",
                compare(traditional,
                        row(SourceId.NETEASE, TimingLevel.WORD, "離開地球表面說好的幸福呢")) < 0);
    }

    @Test
    public void aSeatedTraditionalHanRowIsDisplacedByASimplifiedWordTimedChallenger() {
        // Hysteresis uses the same totals as a fresh comparison, so a seated Han syllable-timed row
        // does not hold its seat against a Han word-timed challenger that scores higher: the script
        // point is free to upgrade the seat. This is the half of the rule that used to be unreachable
        // - the old comparison read the tier ordinal here and returned before ever measuring the
        // script, so a Simplified document could never displace a Traditional one at the head of the
        // list.
        CatalogCandidate traditional = row(SourceId.SPICY_ORG, TimingLevel.SYLLABLE, "離開地球表面說好的幸福呢");
        CatalogCandidate simplified = row(SourceId.NETEASE, TimingLevel.WORD, "离开地球表面说好的幸福呢");

        assertEquals("the two tiers score level here",
                0.0d, timingDiff(simplified, traditional), 0.0001d);
        assertTrue("the Traditional lines measure a positive Traditional share",
                CatalogResolver.lyricsTraditionalShare(traditional) > 0d);
        assertEquals("and the Simplified edition of the same lines measures none",
                0.0d, CatalogResolver.lyricsTraditionalShare(simplified), 0.0001d);
        assertTrue("the script point, not the tier, is what makes the challenger better",
                CatalogResolver.strictlyBetterRank(simplified, traditional, null));
        assertEquals("and a seated Traditional row is displaced, not held",
                simplified.candidateId, CatalogResolver.resolveWithIncumbent(
                        Arrays.asList(traditional, simplified), null, traditional, null)
                        .winner.candidateId);
    }

    @Test
    public void chineseInlineTimingIsOneSpanPerCharacterInBothTiers() {
        // The premise, through the providers' own payloads and the real parsers: a NetEase YRC line
        // and a SpicyLyrics.org Syllable line carrying the same Chinese text both arrive as one span
        // per character, so a row built from either fills character by character. That is why the
        // two document types are one rank for Chinese.
        LyricsDocument word = new LyricsParser(null).parseNeteaseWordLyrics(null, TRACK,
                "{\"yrc\":{\"lyric\":\"" + SIMPLIFIED_LINES + "\"}}");
        LyricsDocument syllable = spicyDocument("離開地球表面", "說好的幸福呢");

        assertNotNull(word);
        assertEquals("Word", word.type);
        assertEquals("离开地球表面", word.lines.get(0).text);
        assertEquals(6, word.lines.get(0).syllables.size());
        assertOneCharacterPerSpan(word.lines.get(0));

        assertEquals("Syllable", syllable.type);
        assertEquals("離開地球表面", syllable.lines.get(0).text);
        assertEquals(6, syllable.lines.get(0).syllables.size());
        assertOneCharacterPerSpan(syllable.lines.get(0));
    }

    @Test
    public void theReportedPairSeatsTheSimplifiedDocument() {
        // End to end on the reported pair: the real payloads, the real parsers, the real catalog
        // adapter - which is what turns a document type into a timing level - and the ranking rule.
        CatalogCandidate traditional = CatalogAdapters.buildCandidate(SourceId.SPICY_ORG, TRACK,
                spicyDocument("離開地球表面", "說好的幸福呢"), MatchMethod.EXACT_SPOTIFY_ID, TRACK_ID,
                "", 1, System.currentTimeMillis());
        CatalogCandidate simplified = CatalogAdapters.buildCandidate(SourceId.NETEASE, TRACK,
                new LyricsParser(null).parseNeteaseWordLyrics(null, TRACK,
                        "{\"yrc\":{\"lyric\":\"" + SIMPLIFIED_LINES + "\"}}"),
                MatchMethod.EXACT_SPOTIFY_ID, TRACK_ID, "", 1, System.currentTimeMillis());

        assertNotNull(traditional);
        assertNotNull(simplified);
        assertEquals(TimingLevel.SYLLABLE, traditional.timingLevel);
        assertEquals(TimingLevel.WORD, simplified.timingLevel);
        assertEquals("both in-line, so both pay the same fill points",
                CatalogResolver.POINTS_IN_LINE, CatalogResolver.timingPoints(traditional.timingLevel));
        assertEquals(CatalogResolver.POINTS_IN_LINE,
                CatalogResolver.timingPoints(simplified.timingLevel));
        assertEquals("and the rule scores them level on timing",
                0.0d, timingDiff(traditional, simplified), 0.0001d);
        assertEquals("so the Simplified document takes the seat",
                simplified.candidateId, winner(traditional, simplified));
        assertEquals("and a seated Traditional row is displaced, not held",
                simplified.candidateId, CatalogResolver.resolveWithIncumbent(
                        Arrays.asList(traditional, simplified), null, traditional, null)
                        .winner.candidateId);
    }

    private static void assertOneCharacterPerSpan(LyricsLine line) {
        for (SyllableSegment segment : line.syllables) {
            assertEquals("span \"" + segment.text + "\" is not one character",
                    1, segment.text.codePointCount(0, segment.text.length()));
        }
    }

    /**
     * An Apple-Music-shaped SpicyLyrics.org Syllable document, one syllable element per character,
     * timed like the NetEase YRC body above.
     */
    private static LyricsDocument spicyDocument(String... lines) {
        JsonArray content = new JsonArray();
        double start = 1.0;
        for (String text : lines) {
            JsonArray syllables = new JsonArray();
            for (int i = 0; i < text.length(); i++) {
                JsonObject syllable = new JsonObject();
                syllable.addProperty("Text", text.substring(i, i + 1));
                syllable.addProperty("IsPartOfWord", false);
                syllable.addProperty("StartTime", start + i * 0.2);
                syllable.addProperty("EndTime", start + (i + 1) * 0.2);
                syllables.add(syllable);
            }
            JsonObject lead = new JsonObject();
            lead.addProperty("Text", text);
            lead.addProperty("StartTime", start);
            lead.addProperty("EndTime", start + text.length() * 0.2);
            lead.add("Syllables", syllables);
            JsonObject item = new JsonObject();
            item.addProperty("Type", "Vocal");
            item.add("Lead", lead);
            content.add(item);
            start += 2.0;
        }
        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("Type", "Syllable");
        lyrics.add("Content", content);
        lyrics.addProperty("id", TRACK_ID);
        lyrics.addProperty("source", "apple_music");
        JsonObject envelope = new JsonObject();
        envelope.addProperty("Status", 200);
        envelope.add("Body", lyrics);
        return new LyricsParser(null).parseSpicyOrgLyrics(null, TRACK, envelope.toString());
    }

    /**
     * A candidate whose document is encoded exactly as the store encodes it. Every field the
     * comparison reads is stated, so a difference between two rows is always a difference this test
     * asked for.
     */
    private static CatalogCandidate row(SourceId source, TimingLevel timing, String... lines) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        document.type = timing == TimingLevel.SYLLABLE ? "Syllable"
                : timing == TimingLevel.WORD ? "Word"
                : timing == TimingLevel.LINE ? "Line" : "Static";
        for (String text : lines) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = 1000L * document.lines.size();
            line.endMs = line.startMs + 1000L;
            document.lines.add(line);
        }
        String id = source.id + "|" + TRACK_ID + "|e" + (nextId++) + "#" + nextId;
        return new CatalogCandidate(id, TRACK_ID, source, TRACK_ID, MatchMethod.EXACT_SPOTIFY_ID,
                0.8, 0L, timing, true, true, false, false, false, false, false,
                "digest" + nextId, CanonicalSourceCodec.encode(document, 1, "digest", 0L),
                "[]", new byte[0], 1, 1, 0L);
    }

    private static String winner(CatalogCandidate a, CatalogCandidate b) {
        return CatalogResolver.resolve(Arrays.asList(a, b), null,
                Collections.<SourceId>emptyList()).winner.candidateId;
    }

    private static int compare(CatalogCandidate a, CatalogCandidate b) {
        return CatalogResolver.compare(a, b, null);
    }

    /**
     * The difference the two timing tiers make, as the table pays for them: the fill points, and
     * nothing else.
     *
     * <p>Deliberately the timing component and not the total. Comparing totals would fold in the
     * script point and the graded duration rank, so the pair this test is about - the same words in
     * Traditional and in Simplified - would differ for reasons that have nothing to do with timing,
     * and an assertion that the two tiers score level would be measuring something else. The graded
     * duration component ranks rows against each other, so it is not a property of one row at all
     * and has no place in a per-tier comparison.
     */
    private static double timingDiff(CatalogCandidate a, CatalogCandidate b) {
        if (a == null || b == null) return 0d;
        return timingScore(a) - timingScore(b);
    }

    private static double timingScore(CatalogCandidate candidate) {
        return CatalogResolver.timingPoints(candidate.timingLevel);
    }
}
