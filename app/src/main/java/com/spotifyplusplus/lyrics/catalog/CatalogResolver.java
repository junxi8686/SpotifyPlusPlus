package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SelectionMode;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;

import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.language.DocumentLanguage;
import com.spotifyplusplus.lyrics.language.SpicyTextDetection;
import com.spotifyplusplus.lyrics.providers.ChineseScriptVariants;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import com.spotifyplusplus.xposed.XpLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The automatic ranking rule for catalog candidates: eliminate a document that is a different edit,
 * then score the survivors, highest total first.
 *
 * <p>This is the only rule. It replaced the tiered comparison, which ordered candidates by an
 * ordered list of tiers and stopped at the first one that differed; the owner removed that
 * comparison outright rather than keeping it beside this one. The consequence to know when reading
 * an old log: a seat recorded under the retired rule cannot be reproduced from these components,
 * because that rule never computed any.
 *
 * <p>The table. Elimination first, because it is not a component:
 *
 * <ul>
 *   <li>Eliminated: the document's lines run ten seconds or more past the recording's end.
 *       One-sided - a document that stops early is ordinary (an outro, a fade, an instrumental
 *       close) and is not eliminated. A fuzzy match is never eliminated for having been found by
 *       search either, and nothing else about it is priced: how a row was matched is not a
 *       component any more.</li>
 *   <li>{@code +4}: in-line timing, {@link TimingLevel#SYLLABLE} and {@link TimingLevel#WORD}
 *       alike, for every script. Both name a fill that advances span by span inside the line, and
 *       every provider here times one character per span for Chinese.</li>
 *   <li>{@code +2}: line timing.</li>
 *   <li>{@code +0}: a document with no timeline. It scores nothing for the fill and nothing for
 *       the duration either: there is no timeline to line up, whatever its length says.</li>
 *   <li>{@code +0 .. +3}: the graded duration component. The candidate whose timeline ends closest
 *       to the recording's end takes three, the next two, the next one, and every candidate behind
 *       them nothing; a document with no timeline and one eliminated as a different edit pay nothing
 *       and take no rank. Graded across the round rather than against fixed second counts - see
 *       {@link #durationPoints} for the measured round that decided that.</li>
 *   <li>{@code +0 .. +5}: the graded Simplified-Chinese component. A gate first decides whether the
 *       document is Chinese at all - over ten per cent kana is Japanese, under ten Han characters is
 *       too little to judge, under a quarter Han by density is not Chinese text - and any of those
 *       verdicts pays nothing. A document that passes is graded on the share of its variant
 *       characters that are written Simplified: a hundred per cent takes the whole five and each band
 *       below it a step less. See {@link #languageOf} and {@link #scriptPoints}.</li>
 *   <li>{@code +0 .. +2}: the share of the document's lines that carry a provider translation,
 *       paid proportionally - a complete translation scores the whole two, a half translation
 *       about one, none nothing.</li>
 *   <li>{@code +1}: complete rather than a fragment.</li>
 *   <li>{@code +2}: an exact Spotify id match, and {@code +1}: a provider's own identity mapping,
 *       such as a QQ songmid. Searching earns nothing. The one source this does not pay is Spotify's
 *       own provider, which indexes by that very id and finds every track by it by construction -
 *       see {@link #exactMatchIsEvidence}.</li>
 * </ul>
 *
 * <p>{@link #MAX_SCORE} is the sum of the components rather than a literal, so the table and the
 * code cannot drift apart: the ceiling is whatever those constants add up to, and it is the total of
 * one document that holds the best of every component at once - the fill for an in-line document that
 * is entirely Simplified Chinese, fully translated, closest on duration, complete, matched by an
 * exact id, and written in the track's own language. It is a ceiling because a document holds one
 * timing tier, one script, one duration rank, one translation coverage, one match method and one
 * language verdict, so no component can be paid twice.
 *
 * <p>An exact match is a payment for the search, not for the lyrics. It means the module resolved
 * an identity - "this row from another service is that Spotify track" - which is real work and is
 * how two exact matches on a previous round finished ahead of the better fuzzy one. It does not
 * mean the document is right: an id says which track was asked about, never whether the served
 * lyrics are timed, complete or in the right script, and the other components are what judge that.
 * Spotify's own provider is exempt for the mirror reason: it is handed the Spotify id and looks the
 * track up by it, so finding it by that id is the expected outcome rather than evidence of
 * anything.
 *
 * <p>Every component but one is read from the candidate alone, so all seven sources compete on the
 * same terms. The duration component is graded across the round instead: it is a property of where a
 * row sits among the rows actually being scored, which is what lets it separate them on a track
 * where every source is tens of seconds out. A score is therefore a function of the candidate
 * <em>and the round it is scored in</em>, and the round is whatever survived the policy filter -
 * the rows the owner's enabled sources returned for this track. Nothing here asks which source a row
 * came from; the source only enters as the tie-break below, after every component has been paid.
 *
 * <p>What that produces on a measured round: the word-timed Simplified source with a nearly exact
 * timeline leads, the word-timed Simplified one six seconds out follows, and the line-timed
 * Traditional editions trail - with Spotify's own exempt row no longer carried by its exact match.
 * The script component is what separates a Simplified edition from a Traditional one of the same
 * song, which is what the owner wanted from the start.
 *
 * <p>Ties fall through to the owner's configured source order, and only then to the fixed list -
 * upstream v2.1.4's seven-item table: SpicyLyrics.org, Apple Music, Spotify native, AMLL, LRCLIB,
 * QQ, NetEase - and last to the stable candidate id, which keeps the comparison a total order and
 * the outcome independent of callback order. Sources the owner did not list fall back to that same
 * fixed list, so a null or empty order behaves as it did before.
 *
 * <p>What this rule does not read, because the table does not price it: match confidence, match
 * method, background vocals, duet marking, credits, timing health. None of those fields is gone from
 * the candidate - the picker still shows them - they simply do not decide the seat here.
 *
 * <p>Upgrade stability: the incumbent keeps its seat on equal scores and on equal digests, so
 * callback order and restarts cannot flip a persisted winner. Manual mode pins the selected
 * candidate; a missing pin renders the automatic best as a clearly temporary fallback without
 * clearing the pin.
 *
 * <p>Reason strings carry candidate IDs, ranks, and status only, never lyric bodies. Every reason
 * and every decision trace names this rule, so a log says which algorithm produced the seat.
 */
public final class CatalogResolver {
    private CatalogResolver() {
    }

    /**
     * How far a document may run PAST the recording's end before it is read as a different edit.
     *
     * <p>Elimination is one-sided because earliness is evidence of nothing: comparing the absolute
     * difference against a threshold briefly promoted an unsynced document over two synced ones
     * that each ended about nineteen seconds early, and one of that pair was the correct document.
     * A recording that has stopped while the file still has lines is different in kind - that only
     * happens with a longer edit.
     *
     * <p>Kept beside the graded duration component although the two look similar: this one asks
     * whether the document is a different edit at all, and answers by removing the candidate from
     * the round. Grading answers how well the rows that remain fit, and never removes one.
     */
    private static final long DURATION_LIMIT_MS = 10000L;

    /** Points paid for spans inside the line, word and syllable alike. */
    static final int POINTS_IN_LINE = 4;

    /** Points paid for line timing. */
    static final int POINTS_LINE = 2;

    /**
     * The most the graded duration component can pay, to the candidate whose timeline ends closest
     * to the recording's end. The next two rows pay one less each; see
     * {@link #durationPoints(java.util.List)}.
     */
    static final int POINTS_DURATION_MAX = 3;

    /**
     * The most the graded Simplified-Chinese component can pay, to a Chinese document whose sampled
     * text carries no Traditional-only character. The scale below it is stepped; see
     * {@link #scriptPoints}.
     */
    static final int POINTS_SIMPLIFIED_MAX = 5;

    /**
     * The most the translation component can pay, for a document whose every line carries a
     * provider translation. Paid proportionally; see {@link #translationPoints(double)}.
     */
    static final int POINTS_TRANSLATION_MAX = 2;

    /** Points paid for a complete document. */
    static final int POINTS_COMPLETE = 1;

    /**
     * Points paid for a match on the track's own Spotify id, to a source that had to work out which
     * of its own items that id names. See {@link #exactMatchIsEvidence(SourceId)} for the source
     * this is withheld from.
     */
    static final int POINTS_MATCH_EXACT = 2;

    /**
     * Points paid for a provider's own identity mapping - a QQ songmid, a NetEase song id - where
     * the provider's catalogue says this is the track, found by search rather than by Spotify's id.
     */
    static final int POINTS_MATCH_PROVIDER = 1;

    /**
     * Points paid when a candidate's script family is the track's own, and nothing when it is not.
     *
     * <p>Paid in full or not at all, and never a penalty: a family that cannot be determined, or a
     * candidate whose family differs, scores zero here and is otherwise untouched - not eliminated,
     * not reordered against anything but the totals it already had. That is what makes a wrong
     * verdict safe: it can withhold these three points, and it can never promote a source whose
     * document is in the wrong language.
     */
    static final int POINTS_LANGUAGE_MATCH = 3;

    /**
     * The highest total attainable: the sum of every component, the larger timing tier standing in
     * for the mutually exclusive pair, and each graded component at its maximum. Twenty.
     */
    static final int MAX_SCORE = Math.max(POINTS_IN_LINE, POINTS_LINE) + POINTS_SIMPLIFIED_MAX
            + POINTS_TRANSLATION_MAX + POINTS_DURATION_MAX + POINTS_COMPLETE + POINTS_MATCH_EXACT
            + POINTS_LANGUAGE_MATCH;

    /** Characters of a document examined when judging its script. */
    private static final int SCRIPT_SAMPLE_CHARS = 400;

    /**
     * Kana as a fraction of the sample's non-space characters, above which the document is Japanese.
     *
     * <p>Ten per cent, and a ratio rather than a presence test, because a Chinese song that quotes a
     * line of Japanese is still a Chinese song: its kana trace sits far under this, while a Japanese
     * lyric is close to half kana.
     */
    private static final double KANA_JAPANESE_RATIO = 0.10d;

    /** Han characters a sample needs before its script is judged at all. */
    private static final int MIN_HAN_FOR_CHINESE = 10;

    /** Han as a fraction of the sample's non-space characters, below which it is not Chinese text. */
    private static final double MIN_HAN_DENSITY = 0.25d;

    /**
     * What the language gate decided about a sample, and the token the trace prints for it.
     *
     * <p>One value per verdict rather than a set of flags, so the gate's decision and the token a
     * reader sees can never disagree.
     */
    private enum Language {
        CHINESE("zh"),
        JAPANESE("ja(kana)"),
        NO_HAN("unknown(no-han)"),
        TOO_FEW_HAN("unknown(too-few-han)"),
        MIXED("mixed(han-density)");

        final String token;

        Language(String token) {
            this.token = token;
        }
    }

    /**
     * The automatic provider tie-break, in order, first preferred: the fallback behind the owner's
     * configured source order.
     *
     * <p>Upstream v2.1.4's seven-item table, Apple Music included in the second slot. Written out
     * rather than read from {@link SourceId#ordinal()} so the order is a stated policy and not an
     * accident of enum declaration order.
     */
    private static final SourceId[] PROVIDER_PRIORITY = {
            SourceId.SPICY_ORG,
            SourceId.APPLE,
            SourceId.SPOTIFY_NATIVE,
            SourceId.AMLL,
            SourceId.LRCLIB,
            SourceId.QQ,
            SourceId.NETEASE,
    };

    /** The uniform decision surface, so callers hold one type whichever rule ran. */
    public static CatalogRanker.Resolution resolve(List<CatalogCandidate> candidates,
                                                   CatalogSelection selection,
                                                   List<SourceId> configuredOrder) {
        return resolve(candidates, selection, configuredOrder, false);
    }

    /** As above, with the owner's language preference applied; see {@link #languagePoints}. */
    public static CatalogRanker.Resolution resolve(List<CatalogCandidate> candidates,
                                                   CatalogSelection selection,
                                                   List<SourceId> configuredOrder,
                                                   boolean preferChineseTranslation) {
        List<CatalogCandidate> ranked = ranked(candidates, configuredOrder,
                preferChineseTranslation);
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            CatalogCandidate pinned = byId(ranked, selection.candidateId);
            if (pinned != null) {
                return new CatalogRanker.Resolution(pinned, describe("manual", pinned), false);
            }
            if (!ranked.isEmpty()) {
                return new CatalogRanker.Resolution(ranked.get(0),
                        "manual-missing-temporary:" + describe("auto", ranked.get(0)), true);
            }
            return new CatalogRanker.Resolution(null, "manual-missing-empty", true);
        }
        if (ranked.isEmpty()) return new CatalogRanker.Resolution(null, "auto-empty", false);
        return new CatalogRanker.Resolution(ranked.get(0), describe("auto", ranked.get(0)), false);
    }

    /**
     * Ranked best-first with the owner's configured source order as the provider tie-break; weak
     * matches excluded, never null.
     *
     * <p>Every row is scored once, before the sort, and the whole round is scored together: the
     * graded duration component is a rank within the round, so it is assigned here where the round
     * is in hand. Scoring decodes the stored document, which is a JSON parse, and a comparator would
     * repeat that parse for every pair it compares instead of once per candidate.
     */
    static List<CatalogCandidate> ranked(List<CatalogCandidate> candidates,
                                         List<SourceId> configuredOrder) {
        return ranked(candidates, configuredOrder, false);
    }

    /** As {@link #ranked(List, List)}, with the owner's language preference applied. */
    static List<CatalogCandidate> ranked(List<CatalogCandidate> candidates,
                                         List<SourceId> configuredOrder,
                                         boolean preferChineseTranslation) {
        List<CatalogCandidate> admitted = new ArrayList<>();
        if (candidates != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && candidate.matchMethod != MatchMethod.WEAK) admitted.add(candidate);
            }
        }
        final List<SourceId> priority = effectivePriority(configuredOrder);
        List<Points> rows = points(admitted, preferChineseTranslation);
        Collections.sort(rows, new Comparator<Points>() {
            @Override public int compare(Points a, Points b) {
                return rankOrder(a, b, priority);
            }
        });
        List<CatalogCandidate> ranked = new ArrayList<>(rows.size());
        for (Points row : rows) ranked.add(row.candidate);
        traceDecision(rows, priority);
        return ranked;
    }

    /**
     * A round scored and ordered best-first, without the decision trace {@link #ranked} prints.
     *
     * <p>Split out for callers that need the ordering as a measurement rather than as a decision: the
     * arrival trace reports where a newly stored candidate placed and what it scored. Calling
     * {@link #ranked} there would print a second full decision trace for the same round on every
     * arrival.
     */
    static List<Points> sortedPoints(List<CatalogCandidate> candidates,
                                     List<SourceId> configuredOrder) {
        final List<SourceId> priority = effectivePriority(configuredOrder);
        List<Points> rows = points(candidates);
        Collections.sort(rows, new Comparator<Points>() {
            @Override public int compare(Points a, Points b) {
                return rankOrder(a, b, priority);
            }
        });
        return rows;
    }

    /**
     * Resolves with hysteresis: the incumbent keeps its seat against equal-scoring challengers and
     * equal digests, and a challenger takes over only with a strictly better rank.
     *
     * <p>What makes a seated winner survive a restart or a reordered callback. Without it the
     * elected row would follow whatever order the candidates happened to arrive in.
     */
    public static CatalogRanker.Resolution resolveWithIncumbent(
            List<CatalogCandidate> candidates, CatalogSelection selection, CatalogCandidate incumbent,
            List<SourceId> configuredOrder) {
        return resolveWithIncumbent(candidates, selection, incumbent, configuredOrder, false);
    }

    /** As above, with the owner's language preference applied to the ranking and the hysteresis. */
    public static CatalogRanker.Resolution resolveWithIncumbent(
            List<CatalogCandidate> candidates, CatalogSelection selection, CatalogCandidate incumbent,
            List<SourceId> configuredOrder, boolean preferChineseTranslation) {
        CatalogRanker.Resolution fresh =
                resolve(candidates, selection, configuredOrder, preferChineseTranslation);
        if (fresh.winner == null || incumbent == null) return fresh;
        if (selection != null && selection.mode == SelectionMode.MANUAL && !fresh.temporary) {
            return fresh;
        }
        if (fresh.temporary) return fresh;
        if (fresh.winner.candidateId.equals(incumbent.candidateId)) return fresh;
        if (fresh.winner.canonicalDigest.equals(incumbent.canonicalDigest)) {
            return new CatalogRanker.Resolution(incumbent,
                    describe("auto-incumbent-same-digest", incumbent), false);
        }
        if (!strictlyBetterRank(fresh.winner, incumbent, configuredOrder, preferChineseTranslation)) {
            return new CatalogRanker.Resolution(incumbent,
                    describe("auto-incumbent-hold", incumbent), false);
        }
        return fresh;
    }

    /**
     * Applies the runtime source policy before resolving Auto: manual picks stay readable even when
     * their provider is disabled, Source order mode takes the first enabled source with a valid
     * candidate, and the points table ranks only the variants of that source.
     */
    public static CatalogRanker.Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                                             CatalogSelection selection,
                                                             CatalogCandidate incumbent,
                                                             List<SourceId> enabledOrder,
                                                             boolean sourceOrderMode) {
        return resolveConfigured(candidates, selection, incumbent, enabledOrder, sourceOrderMode,
                false);
    }

    /** As above, with the owner's language preference; see {@link #languagePoints}. */
    public static CatalogRanker.Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                                             CatalogSelection selection,
                                                             CatalogCandidate incumbent,
                                                             List<SourceId> enabledOrder,
                                                             boolean sourceOrderMode,
                                                             boolean preferChineseTranslation) {
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            return resolve(candidates, selection, enabledOrder);
        }
        List<CatalogCandidate> enabled = new ArrayList<>();
        if (candidates != null && enabledOrder != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && enabledOrder.contains(candidate.sourceId)) {
                    enabled.add(candidate);
                }
            }
        }
        if (sourceOrderMode) {
            for (SourceId source : enabledOrder == null
                    ? Collections.<SourceId>emptyList() : enabledOrder) {
                List<CatalogCandidate> fromSource = new ArrayList<>();
                for (CatalogCandidate candidate : enabled) {
                    if (candidate.sourceId == source) fromSource.add(candidate);
                }
                List<CatalogCandidate> ranked =
                        ranked(fromSource, enabledOrder, preferChineseTranslation);
                if (!ranked.isEmpty()) {
                    CatalogCandidate winner = ranked.get(0);
                    if (incumbent != null && incumbent.sourceId == source
                            && !strictlyBetterRank(winner, incumbent, enabledOrder,
                            preferChineseTranslation)) {
                        winner = incumbent;
                    }
                    return new CatalogRanker.Resolution(winner, describe("source-order", winner),
                            false);
                }
            }
            return new CatalogRanker.Resolution(null, "source-order-empty", false);
        }
        CatalogCandidate enabledIncumbent = null;
        if (incumbent != null) {
            for (CatalogCandidate candidate : enabled) {
                if (candidate.candidateId.equals(incumbent.candidateId)) {
                    enabledIncumbent = incumbent;
                    break;
                }
            }
        }
        return resolveWithIncumbent(enabled, CatalogSelection.auto(
                selection == null ? "" : selection.trackId), enabledIncumbent, enabledOrder,
                preferChineseTranslation);
    }

    /**
     * One line per decision: every candidate with its points, the components that paid, and the
     * winner, under the name of the rule that produced it.
     *
     * <p>This line is the only place the arithmetic is visible from outside, so it prints every
     * component separately rather than the total alone. {@code dt=} is the raw duration delta the
     * timeline-alignment point is computed from, beside that point so a source which lost it can be
     * seen losing it to a number rather than to a verdict.
     *
     * <p>The components replaced the retired tiered rule's columns. This rule does not consult most
     * of those - match confidence, capabilities, timing health - and a line that still printed them
     * would invite the reader to attribute a loss to a value that was never compared.
     *
     * <p>Printed only when the line changes. Resolving runs on every render, and renders are driven
     * by the session's poll, each surface's demand, and the picker's row ticker - measured on the
     * owner's device at about nineteen a second - while the candidate set is unchanged between almost
     * all of them. Re-printing an identical round costs CPU, rotates the logcat buffer so the last
     * thirty seconds are gone, and makes a real event uncountable: the owner could not tell how often
     * QQ Music was contacted because one arrival produced a dozen identical lines. A repeated line
     * over an unchanged round is not information, so the same rule the other traces follow applies
     * here. What a *change* is - an arrival, a seat move, a source's state - is logged by
     * {@link CatalogDecisions#traceArrival} and the planner's round lines.
     */
    private static void traceDecision(List<Points> ranked, List<SourceId> priority) {
        try {
            String line = "SpotifyPlusSpicy rule=" + CatalogRankingAlgorithm.POINTS.ruleName()
                    + " candidates " + ranked.size() + ": " + traceBody(ranked, priority)
                    + "  -> " + (ranked.isEmpty() ? "none" : ranked.get(0).candidate.sourceId);
            if (line.equals(lastDecisionLine)) return;
            lastDecisionLine = line;
            XpLog.log(line);
        } catch (Throwable ignored) {
            // A trace must never affect the decision it describes.
        }
    }

    /** Last line printed by {@link #traceDecision}; an unchanged round is not re-printed. */
    private static volatile String lastDecisionLine = "";

    /**
     * The candidate half of the decision trace, split out so its shape is asserted by a unit test
     * rather than only observed on a device.
     *
     * <p>Kept package-private for that reason: the log line is what a bug report is diagnosed from,
     * and a field renamed here without the parser being updated is a diagnosis that quietly stops
     * working.
     */
    static String traceBody(List<Points> ranked, List<SourceId> priority) {
        StringBuilder line = new StringBuilder();
        for (Points row : ranked) {
            CatalogCandidate candidate = row.candidate;
            if (line.length() > 0) line.append("  ");
            line.append(candidate.sourceId)
                    .append("[").append(candidate.matchMethod)
                    .append(" order=").append(sourceRank(candidate.sourceId, priority))
                    .append(" timing=").append(candidate.timingLevel)
                    .append("(+").append(row.timing).append(")")
                    .append(" script=+").append(row.script)
                    .append(" trad=").append(formatTraditionalShare(row))
                    .append(" simp=").append(formatSimplifiedShare(row))
                    .append(" lang=").append(row.language.token)
                    .append(" fam=").append(row.family)
                    .append(" langsrc=").append(row.languageSource)
                    .append(" trackfam=").append(row.trackFamily == null ? "none" : row.trackFamily)
                    .append(" langmatch=+").append(row.languageMatch)
                    .append(" trans=").append(formatTranslation(row.translation))
                    .append(" dur=+").append(row.duration)
                    .append(" complete=+").append(row.complete)
                    .append(" match=+").append(row.match)
                    .append(" dt=").append(candidate.durationDeltaMs)
                    .append(" eliminated=").append(row.eliminated ? 1 : 0)
                    .append(" score=").append(formatScore(row.total))
                    .append("]");
        }
        return line.length() == 0 ? "none" : line.toString();
    }

    /**
     * The Traditional measurement, printed beside the script points: the share of the sample's Han
     * characters that are Traditional-only, or {@code none} when the sample has no Han character to
     * measure.
     *
     * <p>Printed with two decimals because {@code script=+0} alone cannot say what the row lost on:
     * a document with one Traditional-only character in four hundred measures a fraction of a percent
     * and a purely Traditional one measures a third, and the whole-percent column of the retired rule
     * could not tell either from an exact zero. The reason the component scored what it did is the
     * {@code lang=} token beside it; this field is the separate measurement the older component used,
     * kept because it is still the plainest read of how Traditional a sample's Han text is.
     */
    private static String formatTraditionalShare(Points row) {
        if (row.traditionalShare < 0d) return "none";
        return String.format(Locale.ROOT, "%.2f%%", row.traditionalShare * 100d);
    }

    /**
     * The Simplified share, printed beside the Traditional one: of the characters that actually
     * differ between the scripts, how many are written Simplified.
     *
     * <p>Two decimals, because the bands are cut at whole tens and a reader checking a seat wants to
     * see which side of one a row fell on. {@code none} means the sample carries no variant character
     * at all - nothing Traditional to weigh against anything Simplified, which is the case that takes
     * the maximum rather than a share.
     */
    private static String formatSimplifiedShare(Points row) {
        if (row.simplifiedShare < 0d) return "none";
        return String.format(Locale.ROOT, "%.2f%%", row.simplifiedShare * 100d);
    }

    /**
     * A translation share with two decimals, so a partial one is legible in the trace.
     *
     * <p>A whole number would print a half translation as {@code +1} and a third of one as
     * {@code +0}, which reads as "this source has no translation" - the exact misreading the trace
     * exists to prevent.
     */
    private static String formatTranslation(double points) {
        return String.format(Locale.ROOT, "+%.2f", points);
    }

    /** A total with two decimals, because a proportional component can put it between integers. */
    private static String formatScore(double total) {
        return String.format(Locale.ROOT, "%.2f", total);
    }

    /**
     * One candidate's points, component by component, so the trace can name what paid and the sort
     * can score each row once.
     */
    static final class Points {
        final CatalogCandidate candidate;
        /** Points for the fill: in-line, line, or nothing. */
        final int timing;
        /** The graded Simplified-Chinese points, 0..{@link #POINTS_SIMPLIFIED_MAX}. */
        final int script;
        /**
         * The measurement the script points are graded on, kept for the trace: the share of the
         * sample's Han characters that are Traditional-only, or -1 when the sample has no Han
         * character, which is the case that pays nothing without there being any Traditional text
         * at all.
         */
        final double traditionalShare;
        /**
         * The Simplified share over the sample's variant characters, kept for the trace: Simplified-only
         * characters divided by Simplified-only plus Traditional-only, or -1 when the sample carries no
         * variant character at all. See {@link DocumentMeasure#simplifiedShare}.
         */
        final double simplifiedShare;
        /** What the gate decided the sample is written in; the trace prints its token. */
        final Language language;
        /** The candidate's script family token, for the language-match component and the trace. */
        final String family;
        /** Which tier produced {@link #family}, for the trace's {@code langsrc=} field. */
        final String languageSource;
        /** The track's script family token, or null when the round carried no Spotify document. */
        final String trackFamily;
        /** {@link #POINTS_LANGUAGE_MATCH} when the family matches the track's, else 0. */
        final int languageMatch;
        /** Points for provider translation coverage, 0..{@link #POINTS_TRANSLATION_MAX}. */
        final double translation;
        /** The graded duration points, 0..{@link #POINTS_DURATION_MAX}; see {@link #durationPoints}. */
        final int duration;
        final int complete;
        /** Points for how the row was matched, 0..{@link #POINTS_MATCH_EXACT}; see {@link #matchPoints}. */
        final int match;
        /** True when the document runs ten seconds or more past the recording's end. */
        final boolean eliminated;
        /** The sum of the components, 0..{@link #MAX_SCORE}. */
        final double total;

        private Points(CatalogCandidate candidate, DocumentMeasure measure, int duration,
                       String trackFamily, boolean preferChineseTranslation) {
            this.candidate = candidate;
            this.timing = timingPoints(candidate.timingLevel);
            this.script = scriptPoints(measure);
            this.traditionalShare = measure.traditionalShare;
            this.simplifiedShare = measure.simplifiedShare;
            this.language = measure.language;
            this.family = measure.family;
            this.languageSource = measure.languageSource;
            this.trackFamily = trackFamily;
            this.languageMatch = languagePoints(measure, trackFamily, preferChineseTranslation);
            this.translation = translationPoints(measure.translationShare);
            this.duration = duration;
            this.complete = candidate.complete ? POINTS_COMPLETE : 0;
            this.match = matchPoints(candidate);
            this.eliminated = eliminated(candidate);
            this.total = timing + script + translation + duration + complete + match + languageMatch;
        }
    }

    /**
     * Scores a whole round: the one place a document is decoded, and the place the graded duration
     * component is assigned across the round.
     *
     * <p>The returned rows are in the order the candidates were given, so a caller can pair them
     * back up - see {@link #compare} and {@link #strictlyBetterRank}, which score the two rows of a
     * verdict as the round they are the whole of.
     */
    static List<Points> points(List<CatalogCandidate> round) {
        return points(round, false);
    }

    /**
     * As {@link #points(List)}, with the owner's language preference: a Chinese candidate also
     * matches a non-Chinese track when it is set.
     *
     * <p>The track's own family is read from the Spotify document in this same round, so the
     * component costs no request and no wait. A round without one leaves it undetermined and pays
     * nobody, and a later arrival simply re-scores the round with it in hand.
     */
    static List<Points> points(List<CatalogCandidate> round, boolean preferChineseTranslation) {
        String trackFamily = trackFamilyOf(round);
        int[] duration = durationPoints(round);
        List<Points> rows = new ArrayList<>(round.size());
        for (int i = 0; i < round.size(); i++) {
            rows.add(new Points(round.get(i), measure(round.get(i)), duration[i], trackFamily,
                    preferChineseTranslation));
        }
        return rows;
    }

    /**
     * The script family of the round's Spotify document, or null when the round has none.
     *
     * <p>Spotify's own document is the one source whose language is known to be the track's: it is
     * served by the player for this recording. It is already a candidate in every round that has it,
     * so this reads what is in hand rather than fetching anything - the whole reason the component
     * cannot slow a decision down.
     */
    private static String trackFamilyOf(List<CatalogCandidate> round) {
        if (round == null) return null;
        for (CatalogCandidate candidate : round) {
            if (candidate != null && candidate.sourceId == SourceId.SPOTIFY_NATIVE) {
                return measure(candidate).family;
            }
        }
        return null;
    }

    /**
     * Scores one candidate on its own, as a round of one: it is the closest timeline there, so it
     * takes the full {@link #POINTS_DURATION_MAX} - unless it has no timeline or is eliminated, the
     * two cases that never earn the component. Used where a row is described rather than elected -
     * {@link #score(CatalogCandidate)} and the tests that state one row's arithmetic - and never
     * where two rows are compared, because a pair graded separately would pay both of them for being
     * closest and the component would cancel itself out.
     *
     * <p>The language component pays nothing here unless the row is Spotify's own document, which is
     * the only row a round of one can know the track's family from; that is the honest reading of "a
     * round with no Spotify document pays nobody".
     */
    static Points points(CatalogCandidate candidate) {
        int duration = durationEligible(candidate) ? POINTS_DURATION_MAX : 0;
        DocumentMeasure measure = measure(candidate);
        String trackFamily = candidate != null && candidate.sourceId == SourceId.SPOTIFY_NATIVE
                ? measure.family : null;
        return new Points(candidate, measure, duration, trackFamily, false);
    }

    /** The points total for one candidate; 0 for null. Fractional when coverage is partial. */
    static double score(CatalogCandidate candidate) {
        return candidate == null ? 0d : points(candidate).total;
    }

    /**
     * The graded duration component, one value per candidate in the order given: 3 for the row whose
     * timeline ends closest to the recording's end, 2 for the next, 1 for the one after that, and
     * nothing for every row behind them.
     *
     * <p>Graded by rank within the round rather than against fixed second counts, because the fixed
     * counts did no work on real rounds. Measured on device: one round came back with every source
     * between 24 and 39 seconds short of the recording's end (netease -24105, lrclib -32615,
     * spotify_native -36165, qq -38667, spicy_org -38925), and another with five of six outside five
     * seconds. A banded gradient - within two seconds three points, within five two, within ten one,
     * beyond that nothing - pays every row in the first round zero and says nothing about which of
     * them is nearly fourteen seconds better than the worst. Ranked, that round reads 3, 2, 1, 0, 0:
     * the rows are separated by the only evidence the round contains, which is their order.
     *
     * <p>Rows whose absolute delta ties share the better value, so the assignment depends only on the
     * deltas and not on arrival order or on the stable candidate id.
     *
     * <p>Two kinds of row are outside the grading and pay nothing. An eliminated row is already
     * established as a different edit, and letting it hold the closest rank would take a point away
     * from a survivor that is genuinely the same edit, for a row that cannot be seated whatever it
     * scores. A row with no timeline never measured its delta at all: the adapter reports zero
     * overrun for a document whose lines carry no end time ({@code CatalogAdapters.lyricsOverrunMs}),
     * so an untimed file looks like a perfect fit and would take the closest rank from every timed
     * document in the round. Ranked on a delta that was never measured is worse than paying nothing
     * for a timeline that does not exist.
     */
    static int[] durationPoints(List<CatalogCandidate> round) {
        List<Long> distances = new ArrayList<>();
        for (CatalogCandidate candidate : round) {
            if (!durationEligible(candidate)) continue;
            long distance = Math.abs(candidate.durationDeltaMs);
            if (!distances.contains(distance)) distances.add(distance);
        }
        Collections.sort(distances);
        int[] out = new int[round.size()];
        for (int i = 0; i < round.size(); i++) {
            CatalogCandidate candidate = round.get(i);
            if (!durationEligible(candidate)) continue;
            out[i] = durationPointsForRank(
                    distances.indexOf(Math.abs(candidate.durationDeltaMs)));
        }
        return out;
    }

    /** True for a row that has a timeline to measure and is not eliminated as a different edit. */
    private static boolean durationEligible(CatalogCandidate candidate) {
        return candidate != null
                && candidate.timingLevel != TimingLevel.UNSYNCED
                && !eliminated(candidate);
    }

    /** Three for the closest rank, one less for each rank behind it, nothing from the fourth on. */
    private static int durationPointsForRank(int rank) {
        int points = POINTS_DURATION_MAX - rank;
        return points > 0 ? points : 0;
    }

    /**
     * Points for the fill a row draws: both in-line tiers pay four, line timing two, a document
     * with no timeline nothing.
     *
     * <p>{@link TimingLevel#SYLLABLE} and {@link TimingLevel#WORD} pay the same for every script.
     * The retired tiered rule kept them apart for Latin text - one span per syllable is not one span
     * per word - and the owner's table scores them equally everywhere, so a Latin word-timed and a
     * Latin syllable-timed document tie here and the tie-break decides.
     */
    static int timingPoints(TimingLevel level) {
        if (level == null) return 0;
        if (level.isInLine()) return POINTS_IN_LINE;
        return level == TimingLevel.LINE ? POINTS_LINE : 0;
    }

    /**
     * True when an exact match on this source is evidence of something.
     *
     * <p>False for Spotify's own provider, and only for it. That source is handed the Spotify track
     * id and looks the track up by it, so an "exact Spotify id match" from it is what it does by
     * default - it cannot return a row that is not an exact match on the id it was given. Paying it
     * priced the lookup the module performed rather than any work the provider did, and on a
     * measured round it put Spotify native second on that alone while its document was line-timed
     * and 28.77% Traditional.
     *
     * <p>Every other source is asked in its own terms - a title search, a songmid, a provider item -
     * and has to be resolved back to a Spotify id. An exact match there is that resolution
     * succeeding, which is real work and real evidence, and it is paid.
     */
    static boolean exactMatchIsEvidence(SourceId source) {
        return source != SourceId.SPOTIFY_NATIVE;
    }

    /**
     * Points for how the row was matched to the track: an exact Spotify id match pays the most, a
     * provider's own identity mapping one, and everything weaker - a fuzzy search, a strong search,
     * a karaoke substitution, a rejected weak match - nothing. Withheld entirely from the source
     * that owns the id; see {@link #exactMatchIsEvidence(SourceId)}.
     *
     * <p>The one component that is not about the document. {@link MatchMethod#EXACT_SPOTIFY_ID}
     * means the provider was asked about this track and answered about this track;
     * {@link MatchMethod#EXACT_PROVIDER_MAPPING} means the provider's own catalogue confirms the
     * pairing, which is confirmation by a different route and worth less; a search returns a
     * document that merely looks like the right one, and the remaining components are what judge
     * it. It is a payment, never a gate: an exact match outranks a search by two points and takes no
     * seat by itself.
     */
    static int matchPoints(CatalogCandidate candidate) {
        if (candidate == null || !exactMatchIsEvidence(candidate.sourceId)) return 0;
        if (candidate.matchMethod == MatchMethod.EXACT_SPOTIFY_ID) return POINTS_MATCH_EXACT;
        if (candidate.matchMethod == MatchMethod.EXACT_PROVIDER_MAPPING) return POINTS_MATCH_PROVIDER;
        return 0;
    }

    /**
     * True when the document has lines ten seconds or more after the recording has ended, which is
     * what makes it a different edit. One-sided; see {@link #DURATION_LIMIT_MS}.
     */
    static boolean eliminated(CatalogCandidate candidate) {
        return candidate != null && candidate.durationDeltaMs >= DURATION_LIMIT_MS;
    }

    /**
     * The Simplified-Chinese predicate: the document passes the Chinese gate and more of its variant
     * characters are written Simplified than Traditional.
     *
     * <p>The binary form of {@link #scriptPoints}. A document with no variant character at all is
     * read as Simplified - there is nothing Traditional about it, and that is what the owner asked
     * for. The gate is the other half, and Japanese is the case that makes it a real bug rather than
     * a theoretical one: its kanji are Han code points and modern Japanese writes many of them in
     * forms that never differ from their Simplified counterpart, so a Japanese document can carry no
     * Traditional-only character at all and be read as Simplified Chinese.
     */
    static boolean isSimplifiedChinese(CatalogCandidate candidate) {
        DocumentMeasure measure = measure(candidate);
        return measure.language == Language.CHINESE
                && (measure.simplifiedShare < 0d || measure.simplifiedShare >= 0.5d);
    }

    /**
     * The gate: what language the sample is written in, decided from densities rather than from the
     * presence of any single character.
     *
     * <p>Kana first, and as a ratio: above {@link #KANA_JAPANESE_RATIO} the document is Japanese. The
     * ratio matters because a Chinese song that quotes a line of Japanese is Chinese with a kana
     * trace in it, and a plain "any kana means Japanese" test would throw it away.
     *
     * <p>Then the Han evidence. Fewer than {@link #MIN_HAN_FOR_CHINESE} Han characters is too little
     * to judge and scores nothing; so does a document whose Han characters are under
     * {@link #MIN_HAN_DENSITY} of what it contains, which is the English track with a few Chinese
     * words in it. Density rather than presence is the whole point of the gate: it is what separates
     * "written in Chinese" from "contains Chinese".
     *
     * <p>Every verdict except {@link Language#CHINESE} sends the component to zero, so a
     * misclassification costs this component's points and nothing else: a wrong verdict neither
     * rewards nor punishes, and no document can be pushed to the top by it. That property is what
     * makes a heuristic acceptable here without a language model - the residual limits of the
     * heuristic (a Japanese song with almost no kana reads as Chinese; a Chinese song sung mostly in
     * English can fall under the density gate; Hanja in Korean is not told apart from Chinese) can
     * withhold points but cannot promote the wrong source.
     */
    private static Language languageOf(DocumentMeasure measure) {
        if (measure.countedChars == 0) return Language.NO_HAN;
        if ((double) measure.kanaCount / measure.countedChars > KANA_JAPANESE_RATIO) {
            return Language.JAPANESE;
        }
        if (measure.hanCount == 0) return Language.NO_HAN;
        if (measure.hanCount < MIN_HAN_FOR_CHINESE) return Language.TOO_FEW_HAN;
        if ((double) measure.hanCount / measure.countedChars < MIN_HAN_DENSITY) {
            return Language.MIXED;
        }
        return Language.CHINESE;
    }

    /**
     * The graded Simplified-Chinese component, over an already-measured sample: of the characters
     * that actually differ between the scripts, how many are written Simplified.
     *
     * <p>The share is taken over variant characters alone - Simplified-only plus Traditional-only -
     * and never over the whole text. Characters written identically in both scripts are in neither
     * count, which is what gives the scale a defined maximum: a purely Simplified lyric measures
     * 100% however many neutral characters it happens to use, and a purely Traditional one measures
     * 0%, on a twenty-character sample and a two-hundred-character one alike. Counting against all
     * characters instead would leave a fully Simplified song around 25-40%, with no natural
     * full-marks value, which is the version the owner first proposed and then dropped.
     *
     * <p>A sample with no variant character at all has no share to take; it is neither Traditional
     * nor Simplified, and it takes the maximum. The owner's earlier requirement is the reason: a
     * document like 我今天很好 is read in the Simplified script, and there is nothing Traditional in
     * it to hold it back.
     *
     * <p>The bands are cut at 95%, 80%, 60%, 40% and 20%: the top of the scale is where a document is
     * Simplified throughout, and each band below it is a further step back towards a Traditional
     * edition. Unlike the sampled share the owner first proposed, these need no calibration from
     * device data, because the metric itself is bounded and interpretable - and the bands are stated
     * here rather than tuned by ear.
     */
    /**
     * The language-match points for one measured candidate against the track's own verdict.
     *
     * <p>The track's verdict is the one its Spotify document carries, so nothing here needs a
     * network request or a wait: the document is already a candidate in the round. A round with no
     * Spotify document leaves the verdict undetermined and pays nobody, which is the "no speed
     * reduction" half of the contract - this component never asks for a document that is not already
     * there, and if one arrives later the round is simply re-scored and the points appear then.
     *
     * <p>Both sides are compared as strings, which is what makes the two tiers interchangeable:
     * {@code en} matches {@code en} and not {@code fr}, and with no model pack installed
     * {@code fam:latin} matches {@code fam:latin} and not {@code fam:zh}. One verdict token per
     * document, computed once and cached by {@link DocumentLanguage}, so no ranking pass - and no
     * comparison inside one - ever runs the detector.
     *
     * <p>{@code preferChineseTranslation} is the owner's escape hatch: it also accepts a Chinese
     * document for a track whose verdict is not Chinese, which is what someone who would rather read
     * a translation than the original words wants.
     */
    private static int languagePoints(DocumentMeasure candidate, String trackFamily,
                                      boolean preferChineseTranslation) {
        if (trackFamily == null || trackFamily.isEmpty()) return 0;
        if (candidate.family == null || candidate.family.isEmpty()) return 0;
        if (candidate.family.equals(trackFamily)) return POINTS_LANGUAGE_MATCH;
        if (preferChineseTranslation && DocumentLanguage.isChinese(candidate.family)) {
            return POINTS_LANGUAGE_MATCH;
        }
        return 0;
    }

    /**
     * The graded Simplified-Chinese component, over an already-measured sample: of the characters
     * that actually differ between the scripts, how many are written Simplified.
     */
    private static int scriptPoints(DocumentMeasure measure) {
        if (measure.language != Language.CHINESE) return 0;
        double share = measure.simplifiedShare;
        if (share < 0d) return POINTS_SIMPLIFIED_MAX;
        if (share >= 0.95d) return POINTS_SIMPLIFIED_MAX;
        if (share >= 0.80d) return 4;
        if (share >= 0.60d) return 3;
        if (share >= 0.40d) return 2;
        if (share >= 0.20d) return 1;
        return 0;
    }

    /**
     * The translation component, proportional: the share of the document's lines that carry a
     * provider translation, scaled to {@link #POINTS_TRANSLATION_MAX}.
     *
     * <p>A share rather than a yes/no because a provider may translate part of a document, and the
     * owner's intent is that more translated text always beats less. That is a monotonicity
     * requirement: with a rounded whole-point score, a document at 26% coverage and one at 74%
     * would tie, and the second one - three times as much of what the owner is reading - could win
     * or lose on the provider tie-break instead. Paid as a fraction, every additional translated
     * line moves the total, so nothing has to be rounded and no coverage is free.
     *
     * <p>Measured on canonical source state, not on derived text: {@code generatedText} is produced
     * after the seat is decided and is never stored on a candidate, so this cannot reward a row for
     * a machine translation it will be given later either way.
     */
    static double translationPoints(double share) {
        if (share <= 0d) return 0d;
        if (share >= 1d) return POINTS_TRANSLATION_MAX;
        return share * POINTS_TRANSLATION_MAX;
    }

    /**
     * Negative when {@code a} outranks {@code b}: survivors before eliminated candidates, then the
     * higher total, then the owner's configured source order, then the stable candidate id.
     */
    static int compare(CatalogCandidate a, CatalogCandidate b, List<SourceId> configuredOrder) {
        // The two rows are the round: graded separately, each would be the closest timeline in its
        // own round of one and the duration component would cancel out of the verdict.
        List<Points> pair = points(Arrays.asList(a, b));
        return rankOrder(pair.get(0), pair.get(1), effectivePriority(configuredOrder));
    }

    /**
     * The comparison itself, over rows that are already scored: elimination first, so no total can
     * outrank a survivor, then the total, then the provider tie-break, then the stable id.
     */
    private static int rankOrder(Points a, Points b, List<SourceId> priority) {
        if (a.eliminated != b.eliminated) return a.eliminated ? 1 : -1;
        int diff = Double.compare(b.total, a.total);
        if (diff != 0) return diff;
        diff = sourceRank(a.candidate.sourceId, priority)
                - sourceRank(b.candidate.sourceId, priority);
        if (diff != 0) return diff;
        return a.candidate.candidateId.compareTo(b.candidate.candidateId);
    }

    /**
     * True only when {@code challenger} beats the incumbent on the rank a seated winner is defended
     * with: elimination, then the points total, then the provider tie-break. The stable candidate id
     * is deliberately outside that tuple - it elects fresh winners but never dethrones a seated one,
     * which is what keeps a restart or a reordered callback from flipping a persisted seat.
     *
     * <p>A challenger from a source the owner listed earlier does take the seat at an equal total,
     * because that is a preference the owner stated; one from the same source never does, because
     * nothing about the document improved.
     *
     * <p>The two rows are scored as the round they are the whole of, so the graded duration
     * component is assigned between them and the verdict agrees with the ranked list: if the
     * challenger's timeline is closer, it is closer here too.
     */
    static boolean strictlyBetterRank(CatalogCandidate challenger, CatalogCandidate incumbent,
                                      List<SourceId> configuredOrder) {
        return strictlyBetterRank(challenger, incumbent, configuredOrder, false);
    }

    /** As above, with the owner's language preference; the render and the hysteresis must agree. */
    static boolean strictlyBetterRank(CatalogCandidate challenger, CatalogCandidate incumbent,
                                      List<SourceId> configuredOrder,
                                      boolean preferChineseTranslation) {
        if (challenger == null || incumbent == null) return false;
        List<Points> pair = points(Arrays.asList(challenger, incumbent), preferChineseTranslation);
        Points better = pair.get(0);
        Points seated = pair.get(1);
        if (better.eliminated != seated.eliminated) return !better.eliminated;
        if (better.total != seated.total) return better.total > seated.total;
        List<SourceId> priority = effectivePriority(configuredOrder);
        return sourceRank(challenger.sourceId, priority)
                < sourceRank(incumbent.sourceId, priority);
    }

    /**
     * How much of this candidate's own lyrics are written in Traditional, 0..1.
     *
     * <p>Measured on the document rather than the title, because the two can disagree: a catalogue
     * will serve a Simplified track name and Traditional lines, and it is the lines that get read.
     *
     * <p>Returns 0 for a document with no Han characters, which is what an English or Japanese
     * release has: there is no script to prefer, and it must not read as fully Traditional. Callers
     * that need to tell that case from a Simplified document ask {@link #isSimplifiedChinese}.
     */
    static double lyricsTraditionalShare(CatalogCandidate candidate) {
        double share = measure(candidate).traditionalShare;
        return share < 0d ? 0d : share;
    }

    /**
     * The measurement behind {@link #lyricsTraditionalShare}, over an already-decoded sample.
     *
     * <p>Split out so scoring decodes a document once: the Han test and the share both read the same
     * sample, and calling both public entry points would decode the record twice per candidate.
     */
    private static double traditionalShare(String sample) {
        if (sample.isEmpty()) return 0d;
        return ChineseScriptVariants.traditionalConfidence(sample);
    }

    /**
     * What one candidate's stored document says about the components that need its lines.
     *
     * <p>{@code translationShare} counts the lines that carry canonical {@code providerTranslatedText},
     * over the lines that could carry it. Interludes and empty lines are excluded from the
     * denominator: they are markers ("♪", a gap), no provider translates them, and counting them
     * would silently cap every document below full coverage - which for a proportional component
     * means every document losing points for a line nobody could have translated.
     *
     * <p>Lines rather than characters, because a provider translates line by line: the resolution
     * path is per line, a line is the unit the reader sees a translation under, and a character
     * count would have to decide how long a translation has to be before it counts, which is a
     * threshold nothing in the pipeline has. Counting lines also makes the component independent of
     * how long each line is, so a document of long lines and one of short lines are comparable.
     *
     * <p>{@code traditionalShare} is the Traditional measurement over the bounded sample, with the
     * no-Han case kept apart from a zero share: {@code -1} means the sample has no Han character to
     * measure, so nothing is preferred and nothing is paid. Everything else here - the gate's counts
     * and the Simplified share - reads the same bounded sample, so {@code trad=}, {@code simp=} and
     * {@code lang=} describe one text.
     */
    private static final class DocumentMeasure {
        /** The script sample, from the head of the document. */
        final String sample;
        /** Share of the document's content lines that carry a provider translation, 0..1. */
        final double translationShare;
        /** The Traditional share of the sample, 0..1, or -1 when the sample has no Han character. */
        final double traditionalShare;
        /**
         * The share of the sample's variant characters that are written Simplified, or -1 when it
         * carries none.
         *
         * <p>The numerator is {@link ChineseScriptVariants#simplifiedOnlyCount} and the denominator
         * its sum with {@link ChineseScriptVariants#traditionalOnlyCount}, both reading the same
         * OpenCC-derived tables {@link ChineseScriptVariants#hasSimplified} and
         * {@link ChineseScriptVariants#hasTraditional} read. Characters written identically in both
         * scripts are in neither count, which is what makes 0% purely Traditional and 100% purely
         * Simplified whatever words the song uses. Whitespace is not counted anywhere; punctuation,
         * Latin and digits count towards the gate's denominators but never as variant characters.
         */
        final double simplifiedShare;
        /** Non-whitespace code points of the sample, the gate's denominator. */
        final int countedChars;
        /** Kana code points of the sample. */
        final int kanaCount;
        /** Han code points of the sample. */
        final int hanCount;
        /** What the gate decided the sample is written in. */
        final Language language;
        /** The sample's language verdict token, comparable across documents; see DocumentLanguage. */
        final String family;
        /** Which tier produced the verdict, for the trace's {@code langsrc=} field. */
        final String languageSource;
        /** True when the sample carries kana, kept for the trace's {@code trad=} evidence. */
        final boolean kanaSample;

        DocumentMeasure(String documentKey, String sample, double translationShare,
                        int countedChars, int kanaCount, int hanCount, int simplifiedOnly,
                        int traditionalOnly) {
            this.sample = sample;
            this.translationShare = translationShare;
            this.countedChars = countedChars;
            this.kanaCount = kanaCount;
            this.hanCount = hanCount;
            this.kanaSample = kanaCount > 0;
            int variants = simplifiedOnly + traditionalOnly;
            this.simplifiedShare = variants == 0 ? -1d : (double) simplifiedOnly / variants;
            this.traditionalShare = hanCount > 0 ? traditionalShare(sample) : -1d;
            this.language = languageOf(this);
            DocumentLanguage.Verdict verdict = DocumentLanguage.verdictOf(documentKey, sample);
            this.family = verdict.token;
            this.languageSource = verdict.source;
        }

        /** An empty measurement: no lines, no characters, nothing to grade. */
        static DocumentMeasure empty() {
            return new DocumentMeasure("", "", 0d, 0, 0, 0, 0, 0);
        }
    }

    /**
     * The line-derived measurements, over one decode of the stored document.
     *
     * <p>The sample is decoded, not read raw, and that distinction is worth a measurement of its own
     * history: reading {@code normalizedDocument} itself returns "no Han character" for every
     * candidate, because an encoded record contains no lyrics. Every source in a device round then
     * measures a share of zero - a measurement that never happened, which reads exactly like a
     * document with no script to prefer. The decode is why that cannot happen here: raw line text
     * goes into every counter, and never a translation or a romanisation.
     *
     * <p>The sample is the head of the document, bounded at {@link #SCRIPT_SAMPLE_CHARS}, and every
     * script measurement reads it: the gate's densities, both shares, and the trace fields beside the
     * points. Bounded because this runs once per candidate per ranking and script is settled by the
     * opening lines; the translation share is the one measurement that reads the whole document,
     * because coverage is a property of all of it.
     */
    private static DocumentMeasure measure(CatalogCandidate candidate) {
        if (candidate == null) return DocumentMeasure.empty();
        try {
            CanonicalSourceCodec.Record record =
                    CanonicalSourceCodec.decode(candidate.normalizedDocument);
            if (record == null || record.document == null || record.document.lines == null) {
                return DocumentMeasure.empty();
            }
            StringBuilder sample = new StringBuilder();
            int contentLines = 0;
            int translatedLines = 0;
            for (LyricsLine line : record.document.lines) {
                if (line == null) continue;
                String text = line.text == null ? "" : line.text;
                if (sample.length() < SCRIPT_SAMPLE_CHARS) sample.append(text).append(' ');
                if (line.interlude || text.trim().isEmpty()) continue;
                contentLines++;
                if (line.providerTranslatedText != null
                        && !line.providerTranslatedText.trim().isEmpty()) {
                    translatedLines++;
                }
            }
            String script = sample.toString();
            double share = contentLines == 0 ? 0d : (double) translatedLines / contentLines;
            return new DocumentMeasure(documentKey(candidate), script, share, countCounted(script),
                    SpicyTextDetection.kanaCount(script), countHan(script),
                    ChineseScriptVariants.simplifiedOnlyCount(script),
                    ChineseScriptVariants.traditionalOnlyCount(script));
        } catch (Throwable ignored) {
            // A document that cannot be decoded has no measurable script and no measurable
            // translation coverage, which reads as no preference - never as a reason to refuse the
            // candidate.
            return DocumentMeasure.empty();
        }
    }

    /**
     * The identity a document's language verdict is cached under: its candidate id and its digest.
     *
     * <p>The same pair the catalog treats as the row's identity, so a re-fetched document with new
     * content gets a new verdict and an unchanged one reuses the old - and a document is inspected by
     * the detector at most once however many ranking passes run over it.
     */
    private static String documentKey(CatalogCandidate candidate) {
        if (candidate == null) return "";
        return candidate.candidateId + "|" + candidate.canonicalDigest;
    }

    /**
     * Every non-whitespace code point of the text: the denominator of the gate's two ratios.
     *
     * <p>Whitespace is the one exclusion, and it is a formatting artefact rather than a character of
     * the song: providers disagree about line breaks and spacing while agreeing about the words.
     * Punctuation, Latin letters, digits, kana and Han all count, which is what makes the density
     * ratio mean "how much of this document is Chinese".
     */
    private static int countCounted(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp)) count++;
        }
        return count;
    }

    /**
     * Han code points of the text. Counted here rather than asked of {@link SpicyTextDetection},
     * which answers the same question yes or no and has no count to give.
     */
    private static int countHan(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) count++;
        }
        return count;
    }

    /**
     * The tie-break order this rule applies: the owner's configured order first, in the order they
     * stored it, then every source of the fixed list they did not name, in fixed-list order.
     *
     * <p>A null or empty configured order therefore produces exactly the fixed list, which is what
     * makes an absent setting behave as it did before this list was consulted. Built once per
     * ranking rather than per comparison, because {@link #ranked} hands the same instance to every
     * pair it sorts.
     */
    static List<SourceId> effectivePriority(List<SourceId> configuredOrder) {
        List<SourceId> order = new ArrayList<>();
        if (configuredOrder != null) {
            for (SourceId source : configuredOrder) {
                if (source != null && !order.contains(source)) order.add(source);
            }
        }
        for (SourceId source : PROVIDER_PRIORITY) {
            if (!order.contains(source)) order.add(source);
        }
        return order;
    }

    /**
     * Position on the applied tie-break list; lower wins. A source on neither the owner's order nor
     * the fixed list keeps its place behind every listed source (by enum ordinal), which also keeps
     * the comparison a total order.
     */
    static int sourceRank(SourceId source, List<SourceId> priority) {
        if (source == null) return Integer.MAX_VALUE;
        int index = priority.indexOf(source);
        if (index >= 0) return index;
        return priority.size() + source.ordinal();
    }

    private static CatalogCandidate byId(List<CatalogCandidate> ranked, String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        for (CatalogCandidate candidate : ranked) {
            if (candidateId.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    /**
     * The reason string, carrying the rule name so a log read after the retired rule was removed
     * says which algorithm seated the winner. The shape after the rule prefix is unchanged, so
     * existing readers of {@code auto:<source>|<timing>|...} still parse it.
     */
    private static String describe(String prefix, CatalogCandidate winner) {
        String source = winner.sourceId == null ? "unknown" : winner.sourceId.id;
        return prefix + ":" + CatalogRankingAlgorithm.POINTS.ruleName()
                + ":" + source
                + "|" + winner.timingLevel.name().toLowerCase(Locale.ROOT)
                + "|" + winner.matchMethod.name().toLowerCase(Locale.ROOT)
                + "|" + (winner.complete ? "complete" : "incomplete")
                + "|" + winner.candidateId;
    }
}
