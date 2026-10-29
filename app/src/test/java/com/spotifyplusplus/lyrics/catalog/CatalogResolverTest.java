package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.catalog.CatalogRanker.Resolution;
import com.spotifyplusplus.lyrics.catalog.CatalogResolver.Points;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.language.SpicyTextDetection;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The automatic ranking rule, one component at a time and then the seats it produces.
 *
 * <p>This is the only rule, so this is the whole ranking contract. The tiered comparison that used
 * to sit beside it was removed by the owner, which is why there is no longer a file that compares
 * two algorithms against each other.
 *
 * <p>The table: eliminate a document that runs ten seconds or more past the recording's end, then
 * pay {@code +4} for in-line timing ({@link TimingLevel#SYLLABLE} and {@link TimingLevel#WORD}
 * alike, every script), {@code +2} for line timing, {@code +0 .. +5} for the graded Simplified
 * script (nothing Traditional in a Chinese sample takes five, a trace four, and a sample that is not
 * Chinese at all takes none), {@code +0 .. +2} for the share of lines carrying a provider
 * translation, {@code +0 .. +3} for the graded duration rank, {@code +1} for a complete document,
 * and {@code +2} for an exact Spotify id match - {@code +1} for a provider's own identity mapping,
 * nothing for a search, and nothing at all for Spotify's own provider, which owns the id. The
 * maximum is seventeen.
 */
public class CatalogResolverTest {

    private static final String TRACK_ID = "4CP3p2wc4F1XIWJ6C3w2KH";

    /**
     * 離開地球表面說好的幸福呢 / 离开地球表面说好的幸福呢 - the same twelve character line in Traditional
     * and Simplified. Twelve rather than six because the gate needs ten Han characters before it will
     * judge a script at all, and three of the twelve differ between the scripts.
     */
    private static final String TRADITIONAL = "離開地球表面說好的幸福呢";
    private static final String SIMPLIFIED = "离开地球表面说好的幸福呢";
    /** Traditional 說, the Traditional-only character the one-in-four-hundred sample carries. */
    private static final String TRADITIONAL_ONE_CHARACTER = "說";
    /** Two Traditional-only characters of eleven Han: the share is half. */
    private static final String PARTLY_TRADITIONAL = "離開地球表面 我们都一样";
    /** Han, and every character written the same in both scripts: no variant character at all. */
    private static final String NEUTRAL = "我今天很好明天也很好";
    /**
     * Japanese: kanji plus kana. Every kanji here is written the same in Japanese and Simplified
     * Chinese, so the Traditional share is exactly zero - which is the bug the language gate exists
     * for. No Chinese text contains kana.
     */
    private static final String JAPANESE = "君の名前を呼んでいる";
    /** Korean: Hangul plus two Han characters, which is Korean text and not Chinese either. */
    private static final String KOREAN = "오늘도 音樂을 들어요";
    /** Chinese with one Japanese phrase in it: one kana among fourteen characters. */
    private static final String CHINESE_WITH_KANA = "我们都一样在人群中寻找自己さ";
    /** An English line with twelve Chinese characters in it: Han by density is under a quarter. */
    private static final String ENGLISH_WITH_CHINESE =
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz 我的未来不是梦啊我们都要";
    /** Four Han characters: too few for the gate to judge a script from. */
    private static final String TOO_FEW_HAN = "我的未来";
    private static final String LATIN = "Take me hand";

    /** What the retired tiered rule called "a different edit": ten seconds past the recording. */
    private static final long OVERRUN_MS = 10000L;

    private static int nextId;

    // --- The table, component by component ---

    @Test
    public void theComponentsArePricedAsSpecifiedAndSumToTheMaximum() {
        assertEquals("in-line timing, word and syllable alike, for every script",
                4, CatalogResolver.timingPoints(TimingLevel.SYLLABLE));
        assertEquals(4, CatalogResolver.timingPoints(TimingLevel.WORD));
        assertEquals("line timing", 2, CatalogResolver.timingPoints(TimingLevel.LINE));
        assertEquals("no timeline pays nothing", 0,
                CatalogResolver.timingPoints(TimingLevel.UNSYNCED));
        assertEquals("the maximum is the sum of the components, not a literal",
                Math.max(CatalogResolver.POINTS_IN_LINE, CatalogResolver.POINTS_LINE)
                        + CatalogResolver.POINTS_SIMPLIFIED_MAX
                        + CatalogResolver.POINTS_TRANSLATION_MAX
                        + CatalogResolver.POINTS_DURATION_MAX
                        + CatalogResolver.POINTS_COMPLETE
                        + CatalogResolver.POINTS_MATCH_EXACT
                        + CatalogResolver.POINTS_LANGUAGE_MATCH,
                CatalogResolver.MAX_SCORE);
        assertEquals("which is twenty: four for the fill, five Simplified, two translation,"
                        + " three duration, one completeness, two for an exact match, three for the"
                        + " track's own language",
                20, CatalogResolver.MAX_SCORE);
    }

    @Test
    public void aCompleteRowThatPaysEveryComponentScoresTheMaximum() {
        // The ceiling, reached: in-line timing, a Chinese sample with no Traditional character, a
        // full provider translation, the closest timeline, a complete document and an exact Spotify
        // id on a source that had to resolve the id rather than being handed it - and the track's own
        // document in the same round, which is what the language component needs to have a language
        // to compare against.
        CatalogCandidate track = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate all = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        List<CatalogResolver.Points> round =
                CatalogResolver.points(Arrays.asList(track, all));
        assertEquals(CatalogResolver.MAX_SCORE, round.get(1).total, 0.0001d);
        assertEquals(4, round.get(1).timing);
        assertEquals(5, round.get(1).script);
        assertEquals(2.0d, round.get(1).translation, 0.0001d);
        assertEquals(3, round.get(1).duration);
        assertEquals(1, round.get(1).complete);
        assertEquals(2, round.get(1).match);
        assertEquals("and the language component on top of them all",
                CatalogResolver.POINTS_LANGUAGE_MATCH, round.get(1).languageMatch);
        assertEquals("for the same family the track's own document is written in",
                round.get(0).family, round.get(1).family);
        assertFalse(CatalogResolver.eliminated(all));
    }

    // --- Match quality ---

    @Test
    public void theMatchComponentPaysTwoForAnExactIdOneForAProviderMappingAndNothingForSearch() {
        // The component the classic rule carried as a tier and this table had dropped: without it two
        // exact-id matches finish behind a fuzzy one with no way to tell that from the log. Exactness
        // is a payment here, not a gate, and it is deliberately two - one point less than the gap
        // between the in-line and line timing tiers, so it cannot outweigh a tier of granularity.
        CatalogCandidate exact = row(SourceId.QQ, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate mapped = row(SourceId.QQ, MatchMethod.EXACT_PROVIDER_MAPPING,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate searched = row(SourceId.QQ, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate karaoke = row(SourceId.QQ, MatchMethod.KARAOKE_SUBSTITUTION,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate weak = row(SourceId.QQ, MatchMethod.WEAK,
                TimingLevel.WORD, LATIN, 0d, true, 0L);

        assertEquals("an exact Spotify id match", 2, CatalogResolver.points(exact).match);
        assertEquals("a provider's own identity mapping, e.g. a QQ songmid", 1,
                CatalogResolver.points(mapped).match);
        assertEquals("a strong search", 0, CatalogResolver.points(searched).match);
        assertEquals("a karaoke substitution confirms nothing about the recording", 0,
                CatalogResolver.points(karaoke).match);
        assertEquals("a weak match, which the resolver refuses anyway", 0,
                CatalogResolver.points(weak).match);
        assertEquals("and no candidate at all", 0, CatalogResolver.matchPoints(null));
        assertEquals("the constant is the exact-match payment", CatalogResolver.POINTS_MATCH_EXACT,
                CatalogResolver.points(exact).match);
        assertEquals("two above a provider mapping, which is one above a search",
                2.0d, CatalogResolver.score(exact) - CatalogResolver.score(searched), 0.0001d);
    }

    /**
     * The exemption the owner asked for, both directions and with everything else identical: the
     * same exact-id candidate scores two points as any other source and nothing as Spotify's own
     * provider, because that provider is handed the Spotify id and cannot return anything but an
     * exact match on it. Spotting the track it was handed is not work it did.
     */
    @Test
    public void spotifyNativesExactMatchIsWorthNothingWhileEveryOtherSourcesIsWorthTwo() {
        SourceId[] otherSources = {
                SourceId.SPICY_ORG, SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE,
        };
        for (SourceId source : otherSources) {
            CatalogCandidate exact = row(source, MatchMethod.EXACT_SPOTIFY_ID,
                    TimingLevel.WORD, LATIN, 0d, true, 0L);
            assertTrue("an exact match is evidence for " + source,
                    CatalogResolver.exactMatchIsEvidence(source));
            assertEquals("and pays two for " + source, 2, CatalogResolver.points(exact).match);
        }
        CatalogCandidate nativeExact = row(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertFalse("Spotify's own provider owns the id it is given",
                CatalogResolver.exactMatchIsEvidence(SourceId.SPOTIFY_NATIVE));
        assertEquals("so its exact match pays nothing", 0,
                CatalogResolver.points(nativeExact).match);

        for (MatchMethod method : MatchMethod.values()) {
            CatalogCandidate nativeRow = row(SourceId.SPOTIFY_NATIVE, method,
                    TimingLevel.WORD, LATIN, 0d, true, 0L);
            assertEquals("spotify_native pays nothing for " + method, 0,
                    CatalogResolver.points(nativeRow).match);
        }

        // The two rows scored as one round, with a Chinese Spotify document in it so the language
        // component cancels between them: this pins the match exemption, not the language one.
        List<CatalogResolver.Points> pair = CatalogResolver.points(Arrays.asList(
                row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d, true, 0L),
                row(SourceId.QQ, MatchMethod.EXACT_SPOTIFY_ID, TimingLevel.WORD, LATIN, 0d, true, 0L),
                row(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID, TimingLevel.WORD, LATIN,
                        0d, true, 0L)));
        assertEquals("and the difference between the same two rows is the whole exemption",
                2.0d, pair.get(1).total - pair.get(2).total, 0.0001d);
        assertEquals("with the language component cancelling for the two Latin rows",
                0, pair.get(1).languageMatch);
        assertEquals(0, pair.get(2).languageMatch);
    }

    @Test
    public void aSearchMatchIsScoredNormallyAndNeverEliminatedForBeingFuzzy() {
        // The owner's reason for not gating on match method: a fuzzy match for a different song
        // fails the duration component anyway, so it cannot be preferred; penalising the provenance
        // on top of that only drops correct documents for obscure releases, which are the ones
        // search alone finds. What the search forgoes is the match points, and nothing else.
        CatalogCandidate exact = row(SourceId.QQ, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate searched = row(SourceId.QQ, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, LATIN, 0d, true, 0L);

        assertEquals("an exact id match is worth two more", 2.0d,
                CatalogResolver.score(exact) - CatalogResolver.score(searched), 0.0001d);
        assertEquals("which is the whole of the difference", 0,
                CatalogResolver.points(searched).match);
        assertFalse(CatalogResolver.eliminated(searched));
        assertTrue("a search match for a different edit is still eliminated by its runtime",
                CatalogResolver.eliminated(row(SourceId.QQ, MatchMethod.STRONG_SEARCH,
                        TimingLevel.WORD, LATIN, 0d, true, 12000L)));
        assertEquals("and a search match takes a seat on its points",
                searched.candidateId,
                CatalogResolver.resolve(Collections.singletonList(searched), null, null)
                        .winner.candidateId);
    }

    /**
     * The arithmetic the owner changed, as the pair it is about: a tier of lyric granularity is
     * worth two points and an exact id is worth two, so an exact-id line-timed row and a
     * search-found word-timed row now arrive level where three points of exactness won it by one.
     * What separates them when the timelines differ is the duration component - the granularity is
     * no longer swamped.
     */
    @Test
    public void anExactIdCannotBridgeTheGapBetweenTheTimingTiers() {
        CatalogCandidate exactLine = row(SourceId.SPICY_ORG, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate searchedWord = row(SourceId.QQ, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        List<Points> level = CatalogResolver.points(Arrays.asList(exactLine, searchedWord));

        assertEquals("two points of granularity against two points of exactness",
                CatalogResolver.timingPoints(TimingLevel.WORD) - CatalogResolver.timingPoints(TimingLevel.LINE),
                CatalogResolver.POINTS_MATCH_EXACT);
        assertEquals("so the pair is level, where three points of exactness took it by one",
                level.get(0).total, level.get(1).total, 0.0001d);
        assertEquals("and level rows fall through to the order, which holds SpicyLyrics.org first",
                exactLine.candidateId,
                CatalogResolver.resolve(Arrays.asList(searchedWord, exactLine), null, null)
                        .winner.candidateId);

        // A closer timeline keeps the word-timed row ahead of the exact-id one, against the same
        // order: that is the case the owner wants reachable.
        CatalogCandidate closerWord = row(SourceId.QQ, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, SIMPLIFIED, 0d, true, 900L);
        CatalogCandidate fartherLine = row(SourceId.SPICY_ORG, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, SIMPLIFIED, 0d, true, -6000L);
        List<Points> decided = CatalogResolver.points(Arrays.asList(closerWord, fartherLine));
        assertEquals("the word-timed row is a point ahead on the closer timeline",
                decided.get(1).total + 1, decided.get(0).total, 0.0001d);
        assertEquals("and takes the seat", closerWord.candidateId,
                CatalogResolver.resolve(Arrays.asList(fartherLine, closerWord), null, null)
                        .winner.candidateId);
    }

    @Test
    public void unsyncedTimingScoresNothingForItsFill() {
        // No timeline at all: no fill points, and no duration points either, even though this row's
        // delta is zero. A document whose lines carry no end time is reported with zero overrun by
        // the adapter, so it would otherwise look like a perfect fit and take the closest rank from
        // every timed document in the round.
        CatalogCandidate unsynced = row(SourceId.QQ, TimingLevel.UNSYNCED, LATIN, 0d, true, 0L);
        assertEquals("no timeline pays none of the timing component", 0,
                CatalogResolver.points(unsynced).timing);
        assertEquals("and none of the duration component either", 0,
                CatalogResolver.points(unsynced).duration);
        assertEquals("so it carries only the completeness and exact-match points", 3,
                CatalogResolver.score(unsynced), 0.0001d);
        assertEquals("even beside a timed row that is 38 seconds short",
                0, CatalogResolver.durationPoints(Arrays.asList(unsynced,
                        row(SourceId.LRCLIB, TimingLevel.LINE, LATIN, 0d, true, -38925L)))[0]);
    }

    // --- The graded duration component ---

    /**
     * The round that decided the component's shape: five sources, every one of them between 24 and
     * 39 seconds short of the recording's end, as measured on a device. A banded gradient - three
     * points within two seconds, two within five, one within ten, nothing beyond - scores every row
     * here zero, which is the outcome the owner rejected: the component would have done no work on
     * that track at all. Graded by rank it separates them, and this is the assertion that fails if
     * anyone ever reinstates fixed bands.
     */
    @Test
    public void theDurationComponentSeparatesARoundWhereEverySourceIsTensOfSecondsOut() {
        List<CatalogCandidate> round = Arrays.asList(
                row(SourceId.NETEASE, TimingLevel.LINE, SIMPLIFIED, 0d, true, -24105L),
                row(SourceId.LRCLIB, TimingLevel.LINE, SIMPLIFIED, 0d, true, -32615L),
                row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d, true, -36165L),
                row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, -38667L),
                row(SourceId.SPICY_ORG, TimingLevel.SYLLABLE, SIMPLIFIED, 0d, true, -38925L));

        int[] duration = CatalogResolver.durationPoints(round);
        assertEquals("the closest timeline takes three", 3, duration[0]);
        assertEquals("the next two", 2, duration[1]);
        assertEquals("the next one", 1, duration[2]);
        assertEquals("and the two behind them nothing", 0, duration[3]);
        assertEquals(0, duration[4]);
        assertTrue("the component is not dead on this round: " + Arrays.toString(duration),
                duration[0] != duration[1] && duration[1] != duration[2]
                        && duration[2] != duration[3]);

        List<Points> scored = CatalogResolver.points(round);
        assertEquals("netease: line 2, script 5, duration 3, complete 1, exact 2, own language 3",
                16.0d, scored.get(0).total, 0.0001d);
        assertEquals("lrclib: the same, one duration rank down", 15.0d, scored.get(1).total, 0.0001d);
        assertEquals("spotify_native: the same rank, but its exact match is exempt",
                12.0d, scored.get(2).total, 0.0001d);
        assertEquals("qq's word-level fill earns back what its duration rank costs it",
                15.0d, scored.get(3).total, 0.0001d);
        assertEquals(15.0d, scored.get(4).total, 0.0001d);
        for (int i = 1; i < scored.size(); i++) {
            final int index = i;
            assertTrue("the closest source scores strictly highest, not level with " + i,
                    scored.get(0).total > scored.get(index).total);
        }
        assertEquals("so the seat is its, although the fixed list prefers SpicyLyrics.org",
                round.get(0).candidateId,
                CatalogResolver.resolve(reversed(round), null, null).winner.candidateId);
    }

    @Test
    public void theDurationComponentIsAssignedByRankWithinTheRound() {
        // The shape, on a round whose deltas straddle the retired thresholds: the closest row takes
        // the maximum whatever its absolute delta is, the next one less, and rows tied on distance
        // share the better value so the assignment cannot depend on arrival order. Every row holds
        // the same timing, script, translation, completeness and match - all four are sources whose
        // exact matches are evidence - so the duration component is the only thing between them.
        List<CatalogCandidate> round = Arrays.asList(
                row(SourceId.AMLL, TimingLevel.LINE, SIMPLIFIED, 0d, true, 968L),
                row(SourceId.SPICY_ORG, TimingLevel.LINE, SIMPLIFIED, 0d, true, -7309L),
                row(SourceId.QQ, TimingLevel.LINE, SIMPLIFIED, 0d, true, -7309L),
                row(SourceId.NETEASE, TimingLevel.LINE, SIMPLIFIED, 0d, true, -24885L),
                row(SourceId.LRCLIB, TimingLevel.LINE, SIMPLIFIED, 0d, true, -27135L));

        int[] duration = CatalogResolver.durationPoints(round);
        assertEquals("the closest, inside a second", 3, duration[0]);
        assertEquals("the tied pair share the better value", 2, duration[1]);
        assertEquals(2, duration[2]);
        assertEquals("then one", 1, duration[3]);
        assertEquals("and nothing from the fourth distance on", 0, duration[4]);
        assertEquals("the assignment does not depend on arrival order",
                Arrays.toString(sorted(CatalogResolver.durationPoints(round))),
                Arrays.toString(sorted(CatalogResolver.durationPoints(reversed(round)))));

        List<Points> scored = CatalogResolver.points(round);
        assertEquals("thirteen points of everything else, and the rank on top",
                13.0d, scored.get(0).total, 0.0001d);
        assertEquals(12.0d, scored.get(1).total, 0.0001d);
        assertEquals(12.0d, scored.get(2).total, 0.0001d);
        assertEquals(11.0d, scored.get(3).total, 0.0001d);
        assertEquals(10.0d, scored.get(4).total, 0.0001d);
        assertEquals("and the ranked list is the same round's order",
                Arrays.asList(round.get(0).candidateId, round.get(1).candidateId,
                        round.get(2).candidateId, round.get(3).candidateId, round.get(4).candidateId),
                idsOf(CatalogResolver.ranked(round, null)));
    }

    @Test
    public void anEliminatedRowNeitherEarnsTheDurationComponentNorTakesARankFromASurvivor() {
        // A row that runs ten seconds past the recording's end is a different edit and cannot be
        // seated whatever it scores; if it held the closest rank it would push a survivor down a
        // point for a document that is not the same recording.
        CatalogCandidate eliminatedClose = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true,
                12000L);
        CatalogCandidate survivorFar = row(SourceId.LRCLIB, TimingLevel.LINE, SIMPLIFIED, 0d, true,
                -38925L);

        int[] duration = CatalogResolver.durationPoints(
                Arrays.asList(eliminatedClose, survivorFar));
        assertEquals("the eliminated row pays nothing", 0, duration[0]);
        assertEquals("and the survivor still takes the closest rank", 3, duration[1]);
        assertTrue(CatalogResolver.eliminated(eliminatedClose));
        assertEquals("so the survivor is seated", survivorFar.candidateId,
                CatalogResolver.resolve(Arrays.asList(eliminatedClose, survivorFar), null, null)
                        .winner.candidateId);
    }

    // --- Translation coverage ---

    @Test
    public void theTranslationComponentIsProportionalToTheLinesTheProviderTranslated() {
        // The replaced component was a yes/no: a provider translation was worth one point whole. It
        // is now worth up to two, in proportion to how much of the document was translated, and the
        // share is what makes more coverage always beat less.
        assertEquals("no translation pays nothing", 0.0d,
                CatalogResolver.translationPoints(0d), 0.0001d);
        assertEquals("a quarter of the lines pay a quarter of the maximum", 0.5d,
                CatalogResolver.translationPoints(0.25d), 0.0001d);
        assertEquals("a half translation pays half", 1.0d,
                CatalogResolver.translationPoints(0.5d), 0.0001d);
        assertEquals("three quarters pay three quarters", 1.5d,
                CatalogResolver.translationPoints(0.75d), 0.0001d);
        assertEquals("a complete translation pays the maximum", 2.0d,
                CatalogResolver.translationPoints(1d), 0.0001d);
        assertEquals("and nothing pays more than the maximum", 2.0d,
                CatalogResolver.translationPoints(1.5d), 0.0001d);

        assertEquals("four translated lines out of eight", 1.0d,
                CatalogResolver.points(eightLines(SourceId.NETEASE, 4)).translation, 0.0001d);
        assertEquals("one line out of eight", 0.25d,
                CatalogResolver.points(eightLines(SourceId.NETEASE, 1)).translation, 0.0001d);
        assertEquals("and two out of five", 0.8d,
                CatalogResolver.points(fiveLines(SourceId.NETEASE, 2)).translation, 0.0001d);
    }

    @Test
    public void moreTranslatedLinesAlwaysBeatFewerAndTheFlagAloneDoesNot() {
        // The monotonicity the owner asked for, as an assertion: every step up in coverage is a step
        // up in score, so more translated text can never lose to less of it. The last pair is the
        // one that would have been a tie under the old yes/no component: both rows carry
        // hasProviderTranslation, and only one of them translated the document.
        double previous = -1d;
        for (int translated = 0; translated <= 8; translated++) {
            double score = CatalogResolver.score(eightLines(SourceId.NETEASE, translated));
            assertTrue("coverage " + translated + " of 8 must beat " + (translated - 1),
                    score > previous);
            previous = score;
        }

        CatalogCandidate fullyTranslated = eightLines(SourceId.NETEASE, 8);
        CatalogCandidate barelyTranslated = eightLines(SourceId.NETEASE, 1);
        assertTrue("both rows claim the provider translation flag",
                fullyTranslated.hasProviderTranslation && barelyTranslated.hasProviderTranslation);
        assertEquals("and the one that translated one line is a point and three quarters behind",
                1.75d, CatalogResolver.score(fullyTranslated)
                        - CatalogResolver.score(barelyTranslated), 0.0001d);
    }

    @Test
    public void aDocumentWithNoContentLinesScoresNoTranslationRatherThanFailing() {
        // A document that is nothing but an interlude marker. There is no line a provider could have
        // translated, so there is no coverage to measure and the component pays nothing - it does not
        // divide by zero and it does not read as a fully translated document.
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        LyricsLine interlude = new LyricsLine();
        interlude.text = "♪";
        interlude.interlude = true;
        document.lines.add(interlude);

        CatalogCandidate only = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, document, true, 0L);
        assertEquals(0.0d, CatalogResolver.points(only).translation, 0.0001d);
        assertEquals("the rest of the table is unaffected: line 2, duration 3, complete 1, exact 2",
                8.0d, CatalogResolver.score(only), 0.0001d);
    }

    // --- Across every source ---

    @Test
    public void everySourceIsScoredByTheSameComponentsExceptTheProviderThatOwnsTheId() {
        // The owner's concern, as code: the graded script, the duration rank and the translation
        // share must be computed for all six sources, not only for the source that happens to carry
        // translations. Every row below holds the same text, timing and delta, so the only thing that
        // varies is the provider - and the only component that reads the provider is the match
        // points, which Spotify's own source does not get.
        SourceId[] sources = {
                SourceId.SPICY_ORG, SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
                SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE,
        };
        for (SourceId source : sources) {
            CatalogCandidate alignedAndTranslated =
                    row(source, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 968L);
            // Scored as the round it is, with the track's own document beside it: a row on its own
            // cannot pay the language component, because there is no track language to compare to.
            List<CatalogResolver.Points> round = CatalogResolver.points(Arrays.asList(
                    row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d, true, 968L),
                    alignedAndTranslated));
            int language = CatalogResolver.POINTS_LANGUAGE_MATCH;
            assertEquals("the maximum script grade is paid to " + source,
                    CatalogResolver.POINTS_SIMPLIFIED_MAX, round.get(1).script);
            assertEquals("the closest rank is paid to " + source, CatalogResolver.POINTS_DURATION_MAX,
                    round.get(1).duration);
            assertEquals("the translation maximum is paid to " + source, 2.0d,
                    round.get(1).translation, 0.0001d);
            boolean ownsTheId = source == SourceId.SPOTIFY_NATIVE;
            assertEquals("and the match points follow the exemption for " + source,
                    ownsTheId ? 0 : CatalogResolver.POINTS_MATCH_EXACT, round.get(1).match);
            assertEquals("so the total is what every component but the match adds up to for " + source,
                    4 + CatalogResolver.POINTS_SIMPLIFIED_MAX + CatalogResolver.POINTS_TRANSLATION_MAX
                            + CatalogResolver.POINTS_DURATION_MAX + CatalogResolver.POINTS_COMPLETE
                            + language + (ownsTheId ? 0 : CatalogResolver.POINTS_MATCH_EXACT),
                    round.get(1).total, 0.0001d);
            if (ownsTheId) {
                assertEquals("which is the maximum short of the exact-match points",
                        CatalogResolver.MAX_SCORE - CatalogResolver.POINTS_MATCH_EXACT,
                        round.get(1).total, 0.0001d);
            } else {
                assertEquals("which is the maximum", CatalogResolver.MAX_SCORE,
                        round.get(1).total, 0.0001d);
            }

            // The other half: an untimed, untranslated document forfeits those two, whichever
            // provider it is. Untimed rather than merely late, because a late row is still the
            // closest row of the round of one it is being scored in.
            CatalogCandidate untimedAndUntranslated =
                    row(source, TimingLevel.UNSYNCED, SIMPLIFIED, 0d, true, -7309L);
            assertEquals("no duration points for " + source, 0,
                    CatalogResolver.points(untimedAndUntranslated).duration);
            assertEquals("and no translation points for " + source, 0.0d,
                    CatalogResolver.points(untimedAndUntranslated).translation, 0.0001d);
        }
    }

    // --- The language component ---

    /**
     * The owner's device bug, pinned end to end: the model pack is installed, the detector declines
     * (its own abstention thresholds, not this module's), and the chain must fall through to the
     * script family instead of paying nobody. With the fallback in place a Latin candidate beats a
     * Japanese one on an English track, which is the complaint that started this work.
     */
    @Test
    public void aDecliningTierOneStillFallsThroughToTheScriptFamily() {
        com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(
                text -> com.spotifyplusplus.lyrics.language.DocumentLanguage.UNKNOWN);
        try {
            CatalogCandidate englishTrack = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d,
                    true, 0L);
            CatalogCandidate latinLine = row(SourceId.QQ, TimingLevel.LINE, LATIN, 0d, true, 0L);
            // In-line timing, so it leads on the fill: only the language component can stop it.
            CatalogCandidate japaneseWord = row(SourceId.AMLL, TimingLevel.WORD, JAPANESE, 0d, true, 0L);

            List<CatalogResolver.Points> scored =
                    CatalogResolver.points(Arrays.asList(englishTrack, japaneseWord, latinLine));
            assertEquals("the track's document answered at the family tier",
                    "fam:latin", scored.get(0).trackFamily);
            assertEquals("tier 1 declined and tier 2 answered",
                    "tier1-declined+tier2", scored.get(1).languageSource);
            assertEquals("the Japanese document is Japanese at the family tier",
                    "fam:ja", scored.get(1).family);
            assertEquals("so it scores nothing for the language", 0, scored.get(1).languageMatch);
            assertEquals("while the Latin document, declined by tier 1 too, is fam:latin",
                    "fam:latin", scored.get(2).family);
            assertEquals("and matches", CatalogResolver.POINTS_LANGUAGE_MATCH,
                    scored.get(2).languageMatch);
            assertEquals("so the Japanese source does not win the English track",
                    latinLine.candidateId,
                    CatalogResolver.resolve(Arrays.asList(englishTrack, japaneseWord, latinLine), null,
                            null).winner.candidateId);
            assertEquals("on exactly the language component", 11.0d, scored.get(2).total, 0.0001d);
            assertEquals("against a fill one tier higher", 10.0d, scored.get(1).total, 0.0001d);
        } finally {
            com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(null);
            com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        }
    }

    /**
     * Tier 1 answering takes precedence over the family, and the family of the text would have said
     * something else: the model separates languages a script cannot, which is the whole reason it is
     * tried first.
     */
    @Test
    public void tierOneAnsweringTakesPrecedenceOverTheFamily() {
        com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(text -> "fr");
        try {
            CatalogCandidate track = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d, true, 0L);
            CatalogCandidate candidate = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
            List<CatalogResolver.Points> scored =
                    CatalogResolver.points(Arrays.asList(track, candidate));
            assertEquals("the model's answer, not fam:latin", "fr", scored.get(1).family);
            assertEquals("tier1", scored.get(1).languageSource);
            assertEquals("and it matches the French track", CatalogResolver.POINTS_LANGUAGE_MATCH,
                    scored.get(1).languageMatch);
        } finally {
            com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(null);
            com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        }
    }

    /**
     * The preference still applies when the verdict came from the family tier, and a document both
     * tiers decline is paid nothing and is not eliminated - the chain ends closed.
     */
    @Test
    public void whenBothTiersDeclineTheComponentPaysNothingAndEliminatesNobody() {
        com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(
                text -> com.spotifyplusplus.lyrics.language.DocumentLanguage.UNKNOWN);
        try {
            CatalogCandidate track = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d, true, 0L);
            CatalogCandidate englishCandidate = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
            CatalogCandidate chineseCandidate =
                    row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
            List<CatalogCandidate> round = Arrays.asList(track, englishCandidate, chineseCandidate);

            assertEquals("the default preference does not match a Chinese document to a Latin track",
                    0, CatalogResolver.points(round).get(2).languageMatch);
            assertEquals("and 优先中文翻译 does", CatalogResolver.POINTS_LANGUAGE_MATCH,
                    CatalogResolver.points(round, true).get(2).languageMatch);
            assertFalse("a mismatch is never an elimination, whichever preference is set",
                    CatalogResolver.points(round, true).get(2).eliminated);
            assertFalse(CatalogResolver.points(round).get(2).eliminated);

            // Both tiers declining: punctuation carries no letters, so neither tier has anything.
            CatalogCandidate punctuation =
                    row(SourceId.LRCLIB, TimingLevel.LINE, "!!! ...", 0d, true, 0L);
            List<CatalogResolver.Points> silent =
                    CatalogResolver.points(Arrays.asList(track, punctuation));
            assertEquals("no verdict from either tier", "", silent.get(1).family);
            assertEquals("both declined", "tier1-declined+none", silent.get(1).languageSource);
            assertEquals(0, silent.get(1).languageMatch);
            assertFalse(silent.get(1).eliminated);
        } finally {
            com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(null);
            com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        }
    }

    /**
     * The component the owner asked for, in the shape that matters: a Chinese track where a
     * Simplified candidate matches the track's own document and a Japanese one does not, and an
     * English track where a Latin candidate matches and a Chinese one does not. The Japanese row is
     * the reported case - a Japanese source winning an English track - and the Chinese row on the
     * English track is its mirror.
     *
     * <p>No pack is installed in this JVM, so both sides are classified by script family, which is
     * tier 2; {@link com.spotifyplusplus.lyrics.language.DocumentLanguage} decides which tier runs.
     */
    @Test
    public void theLanguageComponentPaysThreeForTheTracksOwnLanguageAndNothingForAnother() {
        CatalogCandidate chineseTrack = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d,
                true, 0L);
        CatalogCandidate chineseCandidate = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate japaneseCandidate = row(SourceId.AMLL, TimingLevel.WORD, JAPANESE, 0d, true, 0L);
        List<CatalogResolver.Points> chineseRound =
                CatalogResolver.points(Arrays.asList(chineseTrack, chineseCandidate, japaneseCandidate));
        assertEquals("the Chinese candidate carries the track's own family",
                chineseRound.get(0).family, chineseRound.get(1).family);
        assertEquals(CatalogResolver.POINTS_LANGUAGE_MATCH, chineseRound.get(1).languageMatch);
        assertNotEquals("and the Japanese one does not", chineseRound.get(0).family,
                chineseRound.get(2).family);
        assertEquals("so it scores nothing for this component", 0, chineseRound.get(2).languageMatch);
        assertEquals("while being paid every component it earns - the script gate refuses Japanese,"
                        + " which is the whole reason that gate exists",
                4 + 3 + 1,
                chineseRound.get(2).timing + chineseRound.get(2).script + chineseRound.get(2).duration
                        + chineseRound.get(2).complete);

        CatalogCandidate englishTrack = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d, true,
                0L);
        CatalogCandidate englishCandidate = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        List<CatalogResolver.Points> englishRound =
                CatalogResolver.points(Arrays.asList(englishTrack, englishCandidate, chineseCandidate));
        assertEquals(CatalogResolver.POINTS_LANGUAGE_MATCH, englishRound.get(1).languageMatch);
        assertEquals("an English track does not take a Chinese document",
                0, englishRound.get(2).languageMatch);
        assertEquals("and the mismatch never eliminates it", false,
                englishRound.get(2).eliminated);
    }

    /**
     * The binding of the {@code 歌词语言偏好} setting: with it set, a Chinese document also matches a
     * track that is not Chinese. That is the escape hatch for an owner who would rather read a
     * translation than the original words, and it is a payment like any other - it never turns a
     * mismatch into an elimination.
     */
    @Test
    public void theChineseTranslationPreferenceMakesAChineseCandidateMatchANonChineseTrack() {
        CatalogCandidate englishTrack = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d, true,
                0L);
        CatalogCandidate chineseCandidate = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        List<CatalogCandidate> round = Arrays.asList(englishTrack, chineseCandidate);

        assertEquals("the default, 优先原文: the Chinese document does not match",
                0, CatalogResolver.points(round).get(1).languageMatch);
        assertEquals("优先中文翻译: it does",
                CatalogResolver.POINTS_LANGUAGE_MATCH, CatalogResolver.points(round, true)
                        .get(1).languageMatch);
        assertEquals("and that is worth exactly the constant",
                CatalogResolver.POINTS_LANGUAGE_MATCH,
                CatalogResolver.points(round, true).get(1).total
                        - CatalogResolver.points(round).get(1).total, 0.0001d);
        assertFalse("with no elimination either way", CatalogResolver.points(round, true)
                .get(1).eliminated);
    }

    /**
     * A round with no Spotify document: the component pays nobody and nothing else changes - the
     * round still seats a winner, and the winner is the one the rest of the table elects.
     *
     * <p>This is the no-speed-reduction half of the contract. The component never asks for a document
     * that is not already in the round, never waits, and never delays first display; a Spotify
     * document that arrives later simply re-scores the round and the points appear then.
     */
    @Test
    public void aRoundWithNoSpotifyDocumentPaysTheLanguageComponentToNobody() {
        CatalogCandidate qq = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate spicy = row(SourceId.SPICY_ORG, TimingLevel.LINE, SIMPLIFIED, 0d, true, 0L);
        List<CatalogResolver.Points> scored = CatalogResolver.points(Arrays.asList(spicy, qq));

        assertEquals("no track language to compare against", null, scored.get(0).trackFamily);
        assertEquals(0, scored.get(0).languageMatch);
        assertEquals(0, scored.get(1).languageMatch);
        assertEquals("the totals are the other components exactly",
                CatalogResolver.score(spicy), scored.get(0).total, 0.0001d);
        assertEquals("and a winner is still seated", qq.candidateId,
                CatalogResolver.resolve(Arrays.asList(spicy, qq), null, null).winner.candidateId);
    }

    /**
     * The performance pin the owner insisted on: one detection per document, however many ranking
     * passes run over it. The detector is replaced with a counting one, the same round is scored
     * three times, and the count must not move - a character n-gram model run over six candidates
     * nineteen times a second is exactly the cost the cache exists to prevent.
     */
    @Test
    public void theLanguageVerdictIsComputedOncePerDocumentHoweverManyPassesRun() {
        final java.util.concurrent.atomic.AtomicInteger detections =
                new java.util.concurrent.atomic.AtomicInteger();
        com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(text -> {
            detections.incrementAndGet();
            return "en";
        });
        try {
            CatalogCandidate track = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, LATIN, 0d, true, 0L);
            CatalogCandidate candidate = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
            List<CatalogCandidate> round = Arrays.asList(track, candidate);
            for (int pass = 0; pass < 3; pass++) {
                assertEquals("pass " + pass, CatalogResolver.POINTS_LANGUAGE_MATCH,
                        CatalogResolver.points(round).get(1).languageMatch);
            }
            assertEquals("one detection for the track's document and one for the candidate's",
                    2, detections.get());
            // A row that is not the track and not the candidate - a third document - is one more.
            CatalogResolver.points(Arrays.asList(track, candidate,
                    row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L)));
            assertEquals(3, detections.get());
        } finally {
            com.spotifyplusplus.lyrics.language.DocumentLanguage.installTier1ForTests(null);
            com.spotifyplusplus.lyrics.language.DocumentLanguage.clearCache();
        }
    }

    // --- Elimination ---

    @Test
    public void aDurationErrorOfTenSecondsEliminatesACandidateThatWouldOtherwiseScoreTheMaximum() {
        // The elimination threshold, at and around it. A row that would score the maximum is still
        // eliminated once its lines run ten seconds past the recording's end, and loses to a
        // survivor that scores the minimum: no total can outrank a survivor.
        CatalogCandidate perfect = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        CatalogCandidate atLimit = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true,
                OVERRUN_MS);
        CatalogCandidate underLimit = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true,
                9999L);
        CatalogCandidate track = row(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, SIMPLIFIED, 0d, true, 0L);

        assertEquals(CatalogResolver.MAX_SCORE,
                CatalogResolver.points(Arrays.asList(track, perfect)).get(1).total, 0.0001d);
        assertEquals("a document at the limit has lost the graded duration component, the closest"
                        + " rank being the one thing elimination removes it from",
                CatalogResolver.MAX_SCORE - 3,
                CatalogResolver.points(Arrays.asList(track, atLimit)).get(1).total, 0.0001d);
        assertTrue("and it is eliminated", CatalogResolver.eliminated(atLimit));
        assertFalse("one millisecond short of the limit is not",
                CatalogResolver.eliminated(underLimit));
        assertEquals("and the component is still paid just inside the threshold",
                3, CatalogResolver.points(underLimit).duration);

        CatalogCandidate weakest = row(SourceId.QQ, TimingLevel.UNSYNCED, LATIN, 0d, false, 0L);
        assertEquals("a survivor may score less than the eliminated row", 2,
                CatalogResolver.score(weakest), 0.0001d);
        assertTrue("which it does: " + CatalogResolver.score(weakest) + " against "
                        + CatalogResolver.score(atLimit),
                CatalogResolver.score(weakest) < CatalogResolver.score(atLimit));
        assertEquals("and still wins",
                weakest.candidateId,
                CatalogResolver.resolve(Arrays.asList(atLimit, weakest), null, null).winner.candidateId);
        assertEquals("symmetrically, whatever order they arrive in",
                weakest.candidateId,
                CatalogResolver.resolve(Arrays.asList(weakest, atLimit), null, null).winner.candidateId);
    }

    @Test
    public void endingEarlyIsNotEliminatedBecauseEarlinessIsEvidenceOfNothing() {
        // The one-sided rule. Ending early is ordinary - an outro, a fade, an instrumental close -
        // so it may not eliminate. All it can lose is the duration component, and only against a row
        // whose timeline is closer, which is why the pair below is where that shows.
        CatalogCandidate early = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true,
                -60000L);
        assertFalse("ending a minute early is not a different edit",
                CatalogResolver.eliminated(early));
        assertEquals("a lone row still takes the closest rank: there is nothing else to grade",
                3, CatalogResolver.points(early).duration);

        CatalogCandidate closer = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, -900L);
        List<Points> pair = CatalogResolver.points(Arrays.asList(early, closer));
        assertEquals("beside a closer timeline it drops to two", 2, pair.get(0).duration);
        assertEquals("which the closer one takes", 3, pair.get(1).duration);
        assertEquals("so it forgoes one point and keeps every other component",
                pair.get(1).total - 1, pair.get(0).total, 0.0001d);
        assertEquals("and the closer row is seated", closer.candidateId,
                CatalogResolver.resolve(Arrays.asList(early, closer), null, null).winner.candidateId);
    }

    // --- The script component ---

    @Test
    public void theScriptPointsArePaidForSimplifiedChineseOnly() {
        // Both halves of the predicate matter. A Latin document and a Traditional one both have a
        // Simplified share below every band, so the gate is what keeps the pair apart.
        CatalogCandidate simplified = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate traditional = row(SourceId.QQ, TimingLevel.WORD, TRADITIONAL, 0d, true, 0L);
        CatalogCandidate latin = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);

        assertTrue(CatalogResolver.isSimplifiedChinese(simplified));
        assertFalse(CatalogResolver.isSimplifiedChinese(traditional));
        assertFalse("Latin is not Simplified Chinese", CatalogResolver.isSimplifiedChinese(latin));
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(simplified), 0.0001d);
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(latin), 0.0001d);
        assertTrue(CatalogResolver.lyricsTraditionalShare(traditional) > 0d);

        assertEquals("the Simplified row is the full grade ahead of the otherwise identical Latin one",
                CatalogResolver.score(latin) + CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.score(simplified), 0.0001d);
        assertEquals("and level with the Traditional one, which has no script points either",
                CatalogResolver.score(latin), CatalogResolver.score(traditional), 0.0001d);
        assertFalse("null has no script", CatalogResolver.isSimplifiedChinese(null));
        assertEquals("and no points at all", 0.0d, CatalogResolver.score(null), 0.0001d);
    }

    /**
     * The metric: of the sample's characters that actually differ between the scripts, the fraction
     * written Simplified. Characters written the same in both are in neither count, which is what
     * gives the scale fixed ends - a purely Simplified document measures 100% and a purely
     * Traditional one 0%, whatever words the song happens to use.
     */
    @Test
    public void theSimplifiedShareCountsOnlyTheCharactersTheScriptsDisagreeOn() {
        CatalogCandidate simplified = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate traditional = row(SourceId.QQ, TimingLevel.WORD, TRADITIONAL, 0d, true, 0L);
        CatalogCandidate partly = row(SourceId.QQ, TimingLevel.WORD, PARTLY_TRADITIONAL, 0d, true, 0L);

        assertEquals("离开地球表面说好的幸福呢: three Simplified-only characters, nothing Traditional",
                1.0d, CatalogResolver.points(simplified).simplifiedShare, 0.0001d);
        assertEquals("離開地球表面說好的幸福呢: the same three characters, all Traditional",
                0.0d, CatalogResolver.points(traditional).simplifiedShare, 0.0001d);
        assertEquals("離開地球表面 我们都一样: two Simplified-only against two Traditional",
                0.5d, CatalogResolver.points(partly).simplifiedShare, 0.0001d);

        assertEquals("a purely Simplified document takes the whole grade",
                CatalogResolver.POINTS_SIMPLIFIED_MAX, CatalogResolver.points(simplified).script);
        assertEquals("a purely Traditional one takes nothing", 0,
                CatalogResolver.points(traditional).script);
        assertEquals("and half and half is the middle of the scale", 2,
                CatalogResolver.points(partly).script);
    }

    /**
     * The gate, threshold by threshold.
     *
     * <p>Kana is a ratio and not a presence test, because a Chinese song that quotes a line of
     * Japanese is still a Chinese song: above ten per cent the document is Japanese, under it the
     * document is judged on the rest of the gate and graded on its own script. The two Han
     * thresholds are density tests for the same reason - "contains Han characters" is not "is
     * written in Chinese".
     */
    @Test
    public void theGateDecidesByDensitySoKanaAloneDoesNotMakeADocumentJapanese() {
        // Japanese: six of ten characters are kana.
        CatalogCandidate japanese = row(SourceId.AMLL, TimingLevel.WORD, JAPANESE, 0d, true, 0L);
        assertEquals("kana over the threshold is Japanese, whatever the kanji look like", 0,
                CatalogResolver.points(japanese).script);
        assertFalse(CatalogResolver.isSimplifiedChinese(japanese));
        assertTrue("and the trace says why: " + traceOf(japanese),
                traceOf(japanese).contains("lang=ja(kana)"));

        // Chinese with a Japanese phrase: one kana among fourteen characters, so 7%.
        CatalogCandidate mixed = row(SourceId.AMLL, TimingLevel.WORD, CHINESE_WITH_KANA, 0d, true, 0L);
        assertEquals("under the threshold it stays Chinese", LanguageToken.CHINESE,
                LanguageToken.of(traceOf(mixed)));
        assertEquals("and is graded on its own script", CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.points(mixed).script);

        // English with Chinese words: ten Han characters among sixty-two, so 16% by density.
        CatalogCandidate englishWithChinese =
                row(SourceId.QQ, TimingLevel.WORD, ENGLISH_WITH_CHINESE, 0d, true, 0L);
        assertEquals("not predominantly Chinese", 0, CatalogResolver.points(englishWithChinese).script);
        assertTrue("and the trace says so: " + traceOf(englishWithChinese),
                traceOf(englishWithChinese).contains("lang=mixed(han-density)"));

        // Too few Han characters to judge at all.
        CatalogCandidate tooFew = row(SourceId.QQ, TimingLevel.WORD, TOO_FEW_HAN, 0d, true, 0L);
        assertEquals("four Han characters is too little to judge", 0,
                CatalogResolver.points(tooFew).script);
        assertTrue("and the trace says so: " + traceOf(tooFew),
                traceOf(tooFew).contains("lang=unknown(too-few-han)"));

        CatalogCandidate latin = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertEquals("a Latin document has no Han character at all", 0,
                CatalogResolver.points(latin).script);
        assertTrue("which the trace names differently: " + traceOf(latin),
                traceOf(latin).contains("lang=unknown(no-han)"));

        // Every verdict but Chinese costs this component's points and nothing else: the Japanese row
        // still competes on timing, duration, completeness and match.
        CatalogCandidate japaneseWord = row(SourceId.AMLL, MatchMethod.EXACT_PROVIDER_MAPPING,
                TimingLevel.WORD, JAPANESE, 0d, true, 0L);
        assertEquals(4, CatalogResolver.points(japaneseWord).timing);
        assertEquals(1, CatalogResolver.points(japaneseWord).complete);
        assertEquals(1, CatalogResolver.points(japaneseWord).match);
        assertEquals("so it is scored on everything but the language", 9,
                CatalogResolver.score(japaneseWord), 0.0001d);
    }

    /** The verdict token a row's trace line carries, for the gate tests above. */
    private static String traceOf(CatalogCandidate candidate) {
        return CatalogResolver.traceBody(CatalogResolver.points(Collections.singletonList(candidate)),
                CatalogResolver.effectivePriority(null));
    }

    /** Reads the {@code lang=} token back out of a trace line. */
    private enum LanguageToken {
        CHINESE("lang=zh"), JAPANESE("lang=ja(kana)"), NO_HAN("lang=unknown(no-han)"),
        TOO_FEW_HAN("lang=unknown(too-few-han)"), MIXED("lang=mixed(han-density)");

        final String token;

        LanguageToken(String token) {
            this.token = token;
        }

        static LanguageToken of(String trace) {
            for (LanguageToken value : values()) {
                if (trace.contains(value.token)) return value;
            }
            throw new AssertionError("no language token in " + trace);
        }
    }

    /**
     * The all-neutral document: Han characters present, every one of them written the same in both
     * scripts. The share has nothing to divide - there is no variant character in the sample - and
     * the document takes the maximum, because there is nothing Traditional in it to hold it back.
     * Distinct from the no-Han case, which takes nothing.
     */
    @Test
    public void anAllScriptNeutralHanDocumentTakesTheFullSimplifiedMaximum() {
        CatalogCandidate neutral = row(SourceId.QQ, TimingLevel.WORD, NEUTRAL, 0d, true, 0L);
        assertTrue("the sample is Han", SpicyTextDetection.hasCjkIdeograph(NEUTRAL));
        assertEquals("with no Traditional-only character at all",
                0.0d, CatalogResolver.lyricsTraditionalShare(neutral), 0.0001d);
        assertEquals("and no variant character to take a share over", -1d,
                CatalogResolver.points(neutral).simplifiedShare, 0.0001d);
        assertTrue("so the trace prints no share: " + traceOf(neutral),
                traceOf(neutral).contains("simp=none"));
        assertTrue("and it is the Simplified edition by the binary question too",
                CatalogResolver.isSimplifiedChinese(neutral));
        assertEquals("which takes the full maximum", CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.points(neutral).script);

        CatalogCandidate noHan = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertEquals("while an English document measures the same zero Traditional share", 0.0d,
                CatalogResolver.lyricsTraditionalShare(noHan), 0.0001d);
        assertEquals("and takes nothing, because it has no Han character to prefer",
                0, CatalogResolver.points(noHan).script);
    }

    /**
     * A document scores no script points for one of the gate's verdicts, and the trace names which:
     * {@code lang=unknown(no-han)} for a Latin document, {@code lang=unknown(too-few-han)} for one
     * with too little Han to judge. This is the question a device round raised - nearly every
     * candidate showed {@code script=+0} with no way to tell why - and the verdict token is the
     * answer rather than a share that reads zero for several different reasons.
     */
    @Test
    public void aSampleWithoutHanCharactersPaysNothingWhateverItsShare() {
        CatalogCandidate noHan = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertEquals("a Latin document has no script to prefer", 0,
                CatalogResolver.points(noHan).script);
        assertEquals("and it measures zero for that reason, not because it is Simplified",
                0.0d, CatalogResolver.lyricsTraditionalShare(noHan), 0.0001d);
        assertFalse(CatalogResolver.isSimplifiedChinese(noHan));
        assertTrue("which the trace names: " + traceOf(noHan),
                traceOf(noHan).contains("lang=unknown(no-han)"));

        CatalogCandidate tooFew = row(SourceId.QQ, TimingLevel.WORD, TOO_FEW_HAN, 0d, true, 0L);
        assertEquals("four Han characters is too little for the gate to judge", 0,
                CatalogResolver.points(tooFew).script);
        assertTrue("and the trace says that too: " + traceOf(tooFew),
                traceOf(tooFew).contains("lang=unknown(too-few-han)"));

        CatalogCandidate korean = row(SourceId.QQ, TimingLevel.WORD, KOREAN, 0d, true, 0L);
        assertEquals("Korean with two Hanja is refused by the same threshold", 0,
                CatalogResolver.points(korean).script);
        assertTrue("which is the residual limit the gate does not fix: " + traceOf(korean),
                traceOf(korean).contains("lang=unknown(too-few-han)"));
    }

    /**
     * The metric is a proportion, so one Traditional character among a hundred Simplified ones does
     * not demote the document: it is 99% Simplified by the characters the scripts disagree about,
     * which is what a share means and what the owner asked for. The binary predicate follows it - the
     * same document counts as the Simplified edition - while a purely Traditional document, whose
     * share is zero, does not.
     */
    @Test
    public void theSimplifiedShareIsAProportionSoOneTraditionalCharacterDoesNotDemoteIt() {
        CatalogCandidate oneTraditionalCharacter = oneTraditionalCharacterInTheSample();
        double share = CatalogResolver.points(oneTraditionalCharacter).simplifiedShare;
        assertTrue("eighty Simplified-only characters against one Traditional: " + share,
                share > 0.95d);
        assertEquals("so it takes the top band", CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.points(oneTraditionalCharacter).script);
        assertTrue("and counts as the Simplified edition by the binary question",
                CatalogResolver.isSimplifiedChinese(oneTraditionalCharacter));
        assertEquals("while its Traditional share is the separate measurement, and tiny",
                true, CatalogResolver.lyricsTraditionalShare(oneTraditionalCharacter) > 0d);

        CatalogCandidate traditional = row(SourceId.QQ, TimingLevel.WORD, TRADITIONAL, 0d, true, 0L);
        assertEquals("a purely Traditional document measures the other end of the scale",
                0.0d, CatalogResolver.points(traditional).simplifiedShare, 0.0001d);
        assertEquals("and takes nothing", 0, CatalogResolver.points(traditional).script);
    }


    @Test
    public void theWorkedExampleScoresSpicyNineQqFifteenAndNeteaseSeventeen() {
        // Scored as the one round they are three candidates of, because the duration component is
        // assigned within a round: the two rows that end on the recording's end share the closest
        // rank, and the one 7.3 seconds short takes two.
        //   spicy_org 4 fill + 0 script + 0.00 trans + 2 duration + 1 complete + 2 exact  =  9.
        //   qq        4 fill + 5 script + 0.00 trans + 3 duration + 1 complete + 2 exact  = 15.
        //   netease   4 fill + 5 script + 2.00 trans + 3 duration + 1 complete + 2 exact  = 17.
        // The script grade and the duration rank separate QQ from SpicyLyrics.org, and the
        // translation separates NetEase from QQ: 9, 15, 17.
        CatalogCandidate spicy = row(SourceId.SPICY_ORG, TimingLevel.WORD, TRADITIONAL, 0d, true,
                -7309L);
        CatalogCandidate qq = row(SourceId.QQ, TimingLevel.WORD, SIMPLIFIED, 0d, true, 0L);
        CatalogCandidate netease = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        List<Points> scored = CatalogResolver.points(Arrays.asList(spicy, qq, netease));

        assertEquals(9.0d, scored.get(0).total, 0.0001d);
        assertEquals(15.0d, scored.get(1).total, 0.0001d);
        assertEquals(17.0d, scored.get(2).total, 0.0001d);

        assertEquals("QQ beats SpicyLyrics.org, which leads the fixed provider list, on the script"
                        + " grade and the closer timeline together",
                qq.candidateId,
                CatalogResolver.resolve(Arrays.asList(spicy, qq), null, null).winner.candidateId);
        assertEquals("and NetEase beats QQ on two points of translated text",
                netease.candidateId,
                CatalogResolver.resolve(Arrays.asList(qq, netease), null, null).winner.candidateId);
        assertEquals("and the three of them rank in score order",
                Arrays.asList(netease.candidateId, qq.candidateId, spicy.candidateId),
                idsOf(CatalogResolver.ranked(Arrays.asList(spicy, qq, netease), null)));
    }

    // --- The real device round: five sources, one seat ---

    /** The owner's configured source order, SpicyLyrics.org first. */
    private static final List<SourceId> OWNER_ORDER = Arrays.asList(
            SourceId.SPICY_ORG, SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
            SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE);

    /**
     * The round the owner reported, five candidates of one track, asserted component by component.
     *
     * <p>Every row is within 6.8 seconds of the recording's end, so the graded duration component
     * separates them cleanly: ranked by distance they take 3, 2, 1, 0, 0. The seat is NetEase's on
     * content - word-level timing, text with no Traditional-only character, a complete translation
     * and the closest timeline - and it wins by five points, so the tie-break is not part of the
     * outcome. What the round exists to show is the other end of the table: Spotify native's row is
     * line-timed and 29% Traditional, and its exact Spotify id match earns it nothing, because that
     * provider indexes by that id and cannot return anything else. It used to sit second on that
     * alone; it now sits second from the bottom.
     *
     * <p>Script is five for the two Simplified rows and one for the three Traditional ones, whose
     * measured 28.77-29.05% the fixture's 18.18% sample reads identically - both sit in the band above
     * a tenth and below a third. The two zero cases of the component are pinned separately in
     * {@link #aSampleWithoutHanCharactersPaysNothingWhateverItsShare()} and
     * {@link #anAllScriptNeutralHanDocumentTakesTheFullSimplifiedMaximum()}, and the language gate in
     * {@link #aJapaneseOrKoreanSampleScoresNothingHoweverSimplifiedItsKanjiLook()}.
     */
    @Test
    public void theRealDeviceRoundSeatsNeteaseAndSpotifyNativeFallsToTheBottom() {
        // The round the trace recorded, five candidates of one track:
        //
        //   netease         WORD  trad=0.00%   dt=-3      search
        //   spotify_native  LINE  trad=28.77%  dt=-3963   exact, and Spotify's own provider
        //   spicy_org       LINE  trad=29.05%  dt=-5894   exact
        //   qq              WORD  trad=0.00%   dt=-6438   search
        //   lrclib          LINE  trad=28.77%  dt=-6713   search
        //
        // The three Traditional rows here measure 18.18%, the same band the round's 28.8-29.1% falls
        // in, so the component reads them identically and the arithmetic is exact. NetEase carries the
        // two best components the round has - word-level timing and a nearly exact timeline - and QQ
        // the third; the winner is decided on content alone:
        //
        // <pre>
        // netease         4 fill + 5 script + 2.00 trans + 3 dur + 1 complete + 0 search + 3 lang = 18
        // qq              4 fill + 5 script + 0.00 trans + 0 dur + 1 complete + 0 search + 3 lang = 13
        // spicy_org       2 fill + 0 script + 0.00 trans + 1 dur + 1 complete + 2 exact  + 3 lang =  9
        // spotify_native  2 fill + 0 script + 0.00 trans + 2 dur + 1 complete + 0 exempt + 3 lang =  8
        // lrclib          2 fill + 0 script + 0.00 trans + 0 dur + 1 complete + 0 search + 3 lang =  6
        // </pre>
        //
        // Two things this round exists to pin. NetEase wins by five points, so the seat does not
        // depend on the tie-break; and Spotify native - the row whose exact match used to carry it -
        // sits second from the bottom, because its exact match earns nothing while its Traditional,
        // line-timed document earns nothing on script or fill either. The three Traditional rows take
        // no script points here because the fixture's sample is purely Traditional, which is the
        // bottom of the new scale; the round's own rows measured 28.8-29.1% Traditional, and what
        // their Simplified shares are is what the {@code simp=} field is for.
        CatalogCandidate netease = row(SourceId.NETEASE, MatchMethod.STRONG_SEARCH, TimingLevel.WORD,
                SIMPLIFIED, 1.0d, true, -3L);
        CatalogCandidate spotifyNative = row(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, TRADITIONAL, 0d, true, -3963L);
        CatalogCandidate spicy = row(SourceId.SPICY_ORG, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, TRADITIONAL, 0d, true, -5894L);
        CatalogCandidate qq = row(SourceId.QQ, MatchMethod.STRONG_SEARCH, TimingLevel.WORD,
                SIMPLIFIED, 0d, true, -6438L);
        CatalogCandidate lrclib = row(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH, TimingLevel.LINE,
                TRADITIONAL, 0d, true, -6713L);
        List<CatalogCandidate> round = Arrays.asList(netease, spotifyNative, spicy, qq, lrclib);
        List<Points> scored = CatalogResolver.points(round);

        assertEquals("netease: word-timed, Simplified, translated, closest timeline, and the track's own language: 18",
                18.0d, scored.get(0).total, 0.0001d);
        assertEquals(4, scored.get(0).timing);
        assertEquals(CatalogResolver.POINTS_SIMPLIFIED_MAX, scored.get(0).script);
        assertEquals(2.0d, scored.get(0).translation, 0.0001d);
        assertEquals(3, scored.get(0).duration);
        assertEquals(1, scored.get(0).complete);
        assertEquals("and a search earns it nothing beyond that", 0, scored.get(0).match);

        assertEquals("spotify_native: line-timed, Traditional, second closest: 8",
                8.0d, scored.get(1).total, 0.0001d);
        assertEquals(2, scored.get(1).timing);
        assertEquals(0, scored.get(1).script);
        assertEquals(0.0d, scored.get(1).translation, 0.0001d);
        assertEquals(2, scored.get(1).duration);
        assertEquals(1, scored.get(1).complete);
        assertEquals("its exact match is worth nothing, because it owns the id", 0,
                scored.get(1).match);

        assertEquals("spicy_org: layer below, but its exact match is real work, same language: 9",
                9.0d, scored.get(2).total, 0.0001d);
        assertEquals(2, scored.get(2).timing);
        assertEquals(0, scored.get(2).script);
        assertEquals(1, scored.get(2).duration);
        assertEquals(2, scored.get(2).match);

        assertEquals("qq: word-timed and Simplified, but six seconds short of a rank: 13",
                13.0d, scored.get(3).total, 0.0001d);
        assertEquals(4, scored.get(3).timing);
        assertEquals(CatalogResolver.POINTS_SIMPLIFIED_MAX, scored.get(3).script);
        assertEquals(0, scored.get(3).duration);
        assertEquals(0, scored.get(3).match);

        assertEquals("lrclib: the same document shape as the two above, furthest away: 6",
                6.0d, scored.get(4).total, 0.0001d);

        assertEquals("the winner's margin is five points, so the tie-break is not in it",
                5.0d, scored.get(0).total - scored.get(3).total, 0.0001d);
        assertTrue("and Spotify native is second from the bottom, not second",
                scored.get(1).total < scored.get(2).total
                        && scored.get(1).total < scored.get(3).total);

        assertEquals("so the seat is NetEase's, under the fixed list that prefers SpicyLyrics.org",
                netease.candidateId,
                CatalogResolver.resolve(round, null, null).winner.candidateId);
        assertEquals("in either arrival order",
                netease.candidateId,
                CatalogResolver.resolve(reversed(round), null, null).winner.candidateId);
        assertEquals("and at the head of the picker's order, which reads the same rule",
                netease.candidateId, CatalogResolver.ranked(round, null).get(0).candidateId);
        assertEquals("with the rest in score order",
                Arrays.asList(netease.candidateId, qq.candidateId, spicy.candidateId,
                        spotifyNative.candidateId, lrclib.candidateId),
                idsOf(CatalogResolver.ranked(round, null)));
        assertEquals("the configured order would not change that seat either",
                netease.candidateId,
                CatalogResolver.resolve(round, null, OWNER_ORDER).winner.candidateId);

        // The trace recorded no translation coverage for this round. The seat does not depend on it:
        // without NetEase's measured translation the round reads 13 against QQ's 10, still a clear win.
        List<Points> untranslated = CatalogResolver.points(Arrays.asList(
                row(SourceId.NETEASE, MatchMethod.STRONG_SEARCH, TimingLevel.WORD, SIMPLIFIED, 0d,
                        true, -3L),
                spotifyNative, spicy, qq, lrclib));
        assertEquals("with no translation at all NetEase still leads", 16.0d,
                untranslated.get(0).total, 0.0001d);
        assertEquals("by three points, not by a tie-break", 3.0d,
                untranslated.get(0).total - untranslated.get(3).total, 0.0001d);
    }

    @Test
    public void theDecisionTracePrintsEveryComponentAndTheRuleThatProducedTheSeat() {
        // The log line is what a bug report is diagnosed from, so its shape is asserted rather than
        // only observed on a device. It is the round above, in ranked order, scored the way
        // production scores it - as one round, not one row at a time - so every field of every
        // component can be read off: the fill, the script grade with the share it was read from, the
        // translation, the duration rank with the raw delta beside it, completeness, and the match
        // points that Spotify's own provider does not get.
        List<CatalogCandidate> round = Arrays.asList(
                row(SourceId.NETEASE, MatchMethod.STRONG_SEARCH, TimingLevel.WORD, SIMPLIFIED, 1.0d,
                        true, -3L),
                row(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID, TimingLevel.LINE,
                        TRADITIONAL, 0d, true, -3963L),
                row(SourceId.SPICY_ORG, MatchMethod.EXACT_SPOTIFY_ID, TimingLevel.LINE, TRADITIONAL,
                        0d, true, -5894L),
                row(SourceId.QQ, MatchMethod.STRONG_SEARCH, TimingLevel.WORD, SIMPLIFIED, 0d, true,
                        -6438L),
                row(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH, TimingLevel.LINE, LATIN, 0d, true,
                        -6713L));
        List<Points> rows = CatalogResolver.points(CatalogResolver.ranked(round, null));
        List<SourceId> priority = CatalogResolver.effectivePriority(null);
        String body = CatalogResolver.traceBody(rows, priority);

        assertTrue("the trace names the source and its components: " + body,
                body.startsWith("netease[STRONG_SEARCH order=6 timing=WORD(+4) script=+5"
                        + " trad=0.00% simp=100.00% lang=zh fam=fam:zh langsrc=tier1-absent+tier2 trackfam=fam:zh langmatch=+3 trans=+2.00 dur=+3 complete=+1 match=+0"
                        + " dt=-3 eliminated=0 score=18.00]"));
        assertTrue("the Simplified row six seconds short shows a lower duration rank: " + body,
                body.contains("qq[STRONG_SEARCH order=5 timing=WORD(+4) script=+5"
                        + " trad=0.00% simp=100.00% lang=zh fam=fam:zh langsrc=tier1-absent+tier2 trackfam=fam:zh langmatch=+3 trans=+0.00 dur=+0 complete=+1 match=+0"
                        + " dt=-6438 eliminated=0 score=13.00]"));
        assertTrue("an exact match on a source that resolved the id is paid two: " + body,
                body.contains("spicy_org[EXACT_SPOTIFY_ID order=0 timing=LINE(+2) script=+0"
                        + " trad=25.00% simp=0.00% lang=zh fam=fam:zh langsrc=tier1-absent+tier2 trackfam=fam:zh langmatch=+3 trans=+0.00 dur=+1 complete=+1 match=+2"
                        + " dt=-5894 eliminated=0 score=9.00]"));
        assertTrue("while Spotify's own provider is paid nothing for the same method: " + body,
                body.contains("spotify_native[EXACT_SPOTIFY_ID order=2 timing=LINE(+2) script=+0"
                        + " trad=25.00% simp=0.00% lang=zh fam=fam:zh langsrc=tier1-absent+tier2 trackfam=fam:zh langmatch=+3 trans=+0.00 dur=+2 complete=+1 match=+0"
                        + " dt=-3963 eliminated=0 score=8.00]"));
        assertTrue("and a document the gate refuses says which verdict it was: " + body,
                body.contains("lrclib[STRONG_SEARCH order=4 timing=LINE(+2) script=+0"
                        + " trad=none simp=none lang=unknown(no-han) fam=fam:latin langsrc=tier1-absent+tier2 trackfam=fam:zh langmatch=+0 trans=+0.00 dur=+0 complete=+1"
                        + " match=+0 dt=-6713 eliminated=0 score=3.00]"));
        assertTrue("the field order is unchanged for readers that parse it: " + body,
                body.indexOf(" dt=") > body.indexOf(" complete=+"));
        assertFalse("and no lyric body reaches the log", body.contains(SIMPLIFIED));
        assertEquals("an empty round says so rather than printing nothing", "none",
                CatalogResolver.traceBody(Collections.<Points>emptyList(), priority));
    }

    // --- The tie-break ---

    @Test
    public void theTieBreakFollowsTheConfiguredOrderThenTheFixedList() {
        // Two rows of equal total. The configured order decides first, in the order the owner
        // stored it; whatever they did not name falls back to the fixed list, where QQ sits ahead of
        // NetEase, so QQ wins unnamed and NetEase wins once the owner lists it first.
        CatalogCandidate qq = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate netease = row(SourceId.NETEASE, TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertEquals("equal totals, so only the tie-break is left",
                CatalogResolver.score(qq), CatalogResolver.score(netease), 0.0001d);

        assertEquals("on the fixed list QQ is the earlier source, so QQ wins",
                qq.candidateId,
                CatalogResolver.resolve(Arrays.asList(netease, qq), null, null).winner.candidateId);
        assertEquals("with no order stored the fixed list still decides",
                qq.candidateId,
                CatalogResolver.resolve(Arrays.asList(netease, qq), null,
                        Collections.<SourceId>emptyList()).winner.candidateId);
        assertEquals("and the owner's order reverses it",
                netease.candidateId,
                CatalogResolver.resolve(Arrays.asList(netease, qq), null,
                        Arrays.asList(SourceId.NETEASE, SourceId.QQ)).winner.candidateId);

        assertEquals("a source the owner did not name falls behind every source they did",
                Arrays.asList(SourceId.QQ, SourceId.SPICY_ORG, SourceId.APPLE,
                        SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB, SourceId.NETEASE),
                CatalogResolver.effectivePriority(Collections.singletonList(SourceId.QQ)));
        assertEquals("and the fixed list is upstream v2.1.4's seven-item table",
                Arrays.asList(SourceId.SPICY_ORG, SourceId.APPLE, SourceId.SPOTIFY_NATIVE,
                        SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE),
                CatalogResolver.effectivePriority(null));
        assertTrue("QQ ahead of NetEase on the fixed list",
                CatalogResolver.sourceRank(SourceId.QQ, CatalogResolver.effectivePriority(null))
                        < CatalogResolver.sourceRank(SourceId.NETEASE,
                        CatalogResolver.effectivePriority(null)));
        assertTrue("Apple Music ranks second, above Spotify native and every backup source",
                CatalogResolver.sourceRank(SourceId.APPLE, CatalogResolver.effectivePriority(null))
                        < CatalogResolver.sourceRank(SourceId.SPOTIFY_NATIVE,
                        CatalogResolver.effectivePriority(null)));
        assertTrue("and above every backup source too",
                CatalogResolver.sourceRank(SourceId.APPLE, CatalogResolver.effectivePriority(null))
                        < CatalogResolver.sourceRank(SourceId.NETEASE,
                        CatalogResolver.effectivePriority(null)));
    }

    @Test
    public void theTieBreakDecidesAFreshSeatButTheStableIdDoesNotDethroneOne() {
        // The configured order elects a fresh winner at an equal total, because it is a preference
        // the owner stated.
        CatalogCandidate qq = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate netease = row(SourceId.NETEASE, TimingLevel.WORD, LATIN, 0d, true, 0L);

        assertTrue(CatalogResolver.strictlyBetterRank(netease, qq,
                Arrays.asList(SourceId.NETEASE, SourceId.QQ)));
        assertFalse(CatalogResolver.strictlyBetterRank(qq, netease,
                Arrays.asList(SourceId.NETEASE, SourceId.QQ)));

        // An equal-scoring row from the same source never displaces a seated one, whichever order
        // the two arrive in. One of them has the smaller stable id and so is elected fresh; when it
        // is the incumbent the equal-id path returns it unchanged, and when it is the other row the
        // hold path returns the incumbent. Either way the seat does not move, which is what keeps a
        // restart or a reordered callback from flipping a persisted winner.
        CatalogCandidate seated = row(SourceId.NETEASE, TimingLevel.WORD, LATIN, 0d, true, 0L);
        CatalogCandidate sameSource = row(SourceId.NETEASE, TimingLevel.WORD, LATIN, 0d, true, 0L);
        assertEquals(CatalogResolver.score(seated), CatalogResolver.score(sameSource), 0.0001d);
        assertFalse("nothing about the document improved",
                CatalogResolver.strictlyBetterRank(sameSource, seated, null));
        assertFalse("and not the other way round either",
                CatalogResolver.strictlyBetterRank(seated, sameSource, null));
        assertNotEquals("the two rows are genuinely different candidates",
                seated.candidateId, sameSource.candidateId);

        Resolution undisturbed = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(sameSource, seated), null, seated, null);
        assertEquals("the seat does not move", seated.candidateId, undisturbed.winner.candidateId);

        Resolution held = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(seated, sameSource), null, sameSource, null);
        assertEquals("whichever row is seated", sameSource.candidateId, held.winner.candidateId);
        assertNotEquals("and the seat did not follow the stable id to the other row",
                seated.candidateId, held.winner.candidateId);

        List<SourceId> neteaseFirst = Arrays.asList(SourceId.NETEASE, SourceId.QQ);
        assertEquals("both rows are NetEase's", SourceId.NETEASE, sameSource.sourceId);
        assertEquals(SourceId.NETEASE, netease.sourceId);
        assertEquals("the incumbent keeps its seat", netease.candidateId,
                CatalogResolver.resolveWithIncumbent(
                        Arrays.asList(seated, netease), null, netease, neteaseFirst)
                        .winner.candidateId);
        assertEquals("with QQ ahead of NetEase in the owner's order",
                0, CatalogResolver.sourceRank(SourceId.QQ,
                        CatalogResolver.effectivePriority(
                                Arrays.asList(SourceId.QQ, SourceId.NETEASE))));
        Resolution upgraded = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(netease, qq), null, netease,
                Arrays.asList(SourceId.QQ, SourceId.NETEASE));
        assertEquals("a QQ challenger the owner listed first does take the seat from NetEase",
                qq.candidateId, upgraded.winner.candidateId);
    }

    // --- Decisions ---

    @Test
    public void weakMatchesAreRefusedBeforeScoring() {
        CatalogCandidate weak = row(SourceId.QQ, MatchMethod.WEAK, TimingLevel.WORD, LATIN,
                0d, true, 0L);
        assertEquals("a weak match scores its components like any other row",
                (double) CatalogResolver.POINTS_IN_LINE + CatalogResolver.POINTS_DURATION_MAX
                        + CatalogResolver.POINTS_COMPLETE,
                CatalogResolver.score(weak), 0.0001d);
        assertEquals("but it is not rankable", Collections.emptyList(),
                CatalogResolver.ranked(Collections.singletonList(weak), null));
        assertNull(CatalogResolver.resolve(Collections.singletonList(weak), null, null).winner);
        assertEquals("auto-empty", CatalogResolver.resolve(
                Collections.singletonList(weak), null, null).reason);
    }

    @Test
    public void manualPinsAndEmptyRoundsResolveAsTheyDid() {
        CatalogCandidate pinned = row(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.UNSYNCED, LATIN, 0d, true, 0L);
        CatalogCandidate better = row(SourceId.AMLL, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.SYLLABLE, SIMPLIFIED, 1.0d, true, 0L);
        CatalogSelection manual = new CatalogSelection(TRACK_ID, CatalogSource.SelectionMode.MANUAL,
                pinned.candidateId, SourceId.LRCLIB, "item", "");
        Resolution resolution = CatalogResolver.resolve(Arrays.asList(better, pinned), manual, null);
        assertEquals("a manual pin wins over a better automatic row",
                pinned.candidateId, resolution.winner.candidateId);
        assertFalse(resolution.temporary);
        assertTrue(resolution.reason.startsWith("manual:"));

        CatalogSelection missing = new CatalogSelection(TRACK_ID, CatalogSource.SelectionMode.MANUAL,
                "gone|item|x", SourceId.LRCLIB, "item", "");
        Resolution fallback = CatalogResolver.resolve(Collections.singletonList(better), missing,
                null);
        assertEquals("a missing pin renders the automatic best", better.candidateId,
                fallback.winner.candidateId);
        assertTrue("as a temporary stand-in", fallback.temporary);
        assertTrue(fallback.reason.startsWith("manual-missing-temporary:"));

        Resolution empty = CatalogResolver.resolve(Collections.<CatalogCandidate>emptyList(),
                missing, null);
        assertNull("and with nothing to stand in, no seat at all", empty.winner);
        assertTrue(empty.temporary);
        assertEquals("manual-missing-empty", empty.reason);
    }

    @Test
    public void theReasonAndTheTraceNameTheRuleThatDecided() {
        CatalogCandidate row = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        String reason = CatalogResolver.resolve(Collections.singletonList(row), null, null).reason;
        assertTrue("the reason carries the rule name so a log is unambiguous: " + reason,
                reason.startsWith("auto:points:"));
        assertTrue(reason.contains("netease"));
        assertFalse("and never lyric bodies", reason.contains(SIMPLIFIED));
        assertEquals("points", CatalogRankingAlgorithm.POINTS.ruleName());
        assertEquals("points", CatalogRanker.ruleName());
    }

    @Test
    public void theConfiguredEntryPointAppliesTheSourcePolicyFirst() {
        // A disabled source cannot win: the policy filter runs before the rule does.
        CatalogCandidate disabled = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        CatalogCandidate enabled = row(SourceId.QQ, TimingLevel.WORD, LATIN, 0d, true, 0L);
        Resolution resolution = CatalogResolver.resolveConfigured(
                Arrays.asList(disabled, enabled), null, null,
                Collections.singletonList(SourceId.QQ), false);
        assertNotNull(resolution.winner);
        assertEquals("the higher-scoring source is not enabled",
                enabled.candidateId, resolution.winner.candidateId);

        Resolution manual = CatalogResolver.resolveConfigured(
                Arrays.asList(disabled, enabled),
                new CatalogSelection(TRACK_ID, CatalogSource.SelectionMode.MANUAL,
                        disabled.candidateId, SourceId.NETEASE, "", ""), null,
                Collections.singletonList(SourceId.QQ), false);
        assertEquals("but a manual pin stays readable",
                disabled.candidateId, manual.winner.candidateId);
    }

    @Test
    public void sourceOrderModeStillTakesTheFirstEnabledSource() {
        // Source order mode is the owner's explicit order and is applied ahead of the rule, so the
        // points table only ranks the variants of the source it reached.
        CatalogCandidate spicy = row(SourceId.SPICY_ORG, TimingLevel.WORD, TRADITIONAL, 0d, true, 0L);
        CatalogCandidate netease = row(SourceId.NETEASE, TimingLevel.WORD, SIMPLIFIED, 1.0d, true, 0L);
        Resolution resolution = CatalogResolver.resolveConfigured(
                Arrays.asList(spicy, netease), null, null,
                Arrays.asList(SourceId.SPICY_ORG, SourceId.NETEASE), true);
        assertEquals("SpicyLyrics.org is first in the owner's order",
                spicy.candidateId, resolution.winner.candidateId);
    }

    @Test
    public void anEmptyRoundResolvesToNull() {
        assertNull(CatalogResolver.resolve(Collections.<CatalogCandidate>emptyList(), null, null)
                .winner);
        assertNull(CatalogResolver.resolve(null, null, null).winner);
        assertEquals(Collections.emptyList(), CatalogResolver.ranked(null, null));
    }

    private static List<String> idsOf(List<CatalogCandidate> candidates) {
        List<String> ids = new ArrayList<>();
        for (CatalogCandidate candidate : candidates) ids.add(candidate.candidateId);
        return ids;
    }

    /**
     * Forty Simplified lines and one Traditional-only character, all inside the script sample: 481
     * Han characters, one of them Traditional. The share of the variant characters is 120/121, so
     * this is a document that is Simplified in every line but one - which under a proportion is
     * almost a perfect score, and which the separate Traditional measurement still reports as
     * carrying Traditional text.
     *
     * <p>The Traditional character is the first line rather than the last so that it is inside the
     * four-hundred-character sample both measurements read; a sample that stopped before reaching it
     * would report the document as having no Traditional text at all.
     */
    private static CatalogCandidate oneTraditionalCharacterInTheSample() {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        document.type = "Word";
        LyricsLine traditional = new LyricsLine();
        traditional.text = TRADITIONAL_ONE_CHARACTER;
        traditional.startMs = 1000L;
        traditional.endMs = 1900L;
        document.lines.add(traditional);
        for (int i = 0; i < 40; i++) {
            LyricsLine line = new LyricsLine();
            line.text = SIMPLIFIED;
            line.startMs = 2000L + 1000L * i;
            line.endMs = line.startMs + 900L;
            document.lines.add(line);
        }
        return candidate(SourceId.QQ, TimingLevel.WORD, document, true, 0L);
    }

    private static List<CatalogCandidate> reversed(List<CatalogCandidate> candidates) {
        List<CatalogCandidate> copy = new ArrayList<>(candidates);
        Collections.reverse(copy);
        return copy;
    }

    /** The same lines with each line's characters in reverse order: the same characters, reordered. */
    private static String[] reversedLines(String[] lines) {
        String[] out = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            out[i] = new StringBuilder(lines[i]).reverse().toString();
        }
        return out;
    }

    /** A candidate whose document holds one content line per argument, all timed the same. */
    private static CatalogCandidate lines(SourceId source, TimingLevel timing, long deltaMs,
                                          String... text) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        document.type = timing == TimingLevel.WORD ? "Word"
                : timing == TimingLevel.LINE ? "Line" : "Static";
        for (String value : text) {
            LyricsLine line = new LyricsLine();
            line.text = value;
            line.startMs = 1000L * document.lines.size();
            line.endMs = line.startMs + 900L;
            document.lines.add(line);
        }
        return candidate(source, timing, document, true, deltaMs);
    }

    /** A copy of a duration assignment in ascending order, for comparing across arrival orders. */
    private static int[] sorted(int[] values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    private static CatalogCandidate row(SourceId source, TimingLevel timing, String text,
                                        double translationCoverage, boolean complete, long deltaMs) {
        return row(source, MatchMethod.EXACT_SPOTIFY_ID, timing, text, translationCoverage,
                complete, deltaMs);
    }

    /** Eight content lines, {@code translated} of them carrying a provider translation. */
    private static CatalogCandidate eightLines(SourceId source, int translated) {
        return candidate(source, TimingLevel.WORD, document(8, translated), true, 0L);
    }

    /** Five content lines, {@code translated} of them carrying a provider translation. */
    private static CatalogCandidate fiveLines(SourceId source, int translated) {
        return candidate(source, TimingLevel.WORD, document(5, translated), true, 0L);
    }

    private static LyricsDocument document(int lines, int translated) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        for (int i = 0; i < lines; i++) {
            LyricsLine line = new LyricsLine();
            line.text = SIMPLIFIED;
            line.startMs = 1000L * i;
            line.endMs = line.startMs + 900L;
            if (i < translated) {
                line.providerTranslatedText = "Leaving the surface of the earth";
                line.providerTranslationLanguage = "en";
            }
            document.lines.add(line);
        }
        return document;
    }

    /**
     * A candidate whose document is encoded exactly as the store encodes it, with every field the
     * points table reads stated by the caller.
     *
     * <p>{@code translationCoverage} states the fraction of the document's lines that carry a
     * provider translation, and the document is built to match: the component measures the lines,
     * so a helper that only set {@code hasProviderTranslation} would assert the opposite of what it
     * claims. These rows carry one line each, so 1.0 is a full translation and 0 is none.
     */
    private static CatalogCandidate row(SourceId source, MatchMethod method, TimingLevel timing,
                                        String text, double translationCoverage, boolean complete,
                                        long deltaMs) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        document.type = timing == TimingLevel.SYLLABLE ? "Syllable"
                : timing == TimingLevel.WORD ? "Word"
                : timing == TimingLevel.LINE ? "Line" : "Static";
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = 1000L;
        line.endMs = 2000L;
        if (translationCoverage >= 1d) {
            line.providerTranslatedText = "Leaving the surface of the earth";
            line.providerTranslationLanguage = "en";
        }
        document.lines.add(line);

        return candidate(source, method, timing, document, translationCoverage > 0d, deltaMs,
                complete);
    }

    private static CatalogCandidate candidate(SourceId source, TimingLevel timing,
                                              LyricsDocument document, boolean complete,
                                              long deltaMs) {
        return candidate(source, MatchMethod.EXACT_SPOTIFY_ID, timing, document,
                hasTranslation(document), deltaMs, complete);
    }

    private static CatalogCandidate candidate(SourceId source, MatchMethod method,
                                              TimingLevel timing, LyricsDocument document,
                                              boolean translation, long deltaMs, boolean complete) {
        String id = source.id + "|" + TRACK_ID + "|s" + (nextId++) + "#" + nextId;
        return new CatalogCandidate(id, TRACK_ID, source, TRACK_ID, method, 0.8, deltaMs, timing,
                complete, true, translation, false, false, false, false, "digest" + nextId,
                CanonicalSourceCodec.encode(document, 1, "digest", 0L), "[]", new byte[0], 1, 1, 0L);
    }

    private static boolean hasTranslation(LyricsDocument document) {
        for (LyricsLine line : document.lines) {
            if (line.providerTranslatedText != null && !line.providerTranslatedText.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
