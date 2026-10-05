package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SelectionMode;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;

import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;
import com.spotifyplusplus.lyrics.providers.ChineseScriptVariants;

import com.spotifyplusplus.xposed.XpLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One pure automatic ranking rule for catalog candidates, replacing the competing heuristics in
 * {@code LyricQualityRanker}, {@code LyricsProviderChain}, and canonical-base adoption.
 *
 * <p>Configured Auto first chooses the primary source: Spicy.org, direct Apple, then native
 * Spotify. Enabled fallbacks follow their configured order. Timing quality chooses variants
 * only within that source, so a richer fallback cannot replace the primary text or credit.
 * Source order mode honors the user's full source order. The unconfigured ranking helpers
 * compare timing, completeness, identity, capabilities, and health for same-source variants.
 *
 * <p>Upgrade stability: the incumbent keeps its seat on ties and on equal digests, so callback
 * order and restarts cannot flip a persisted winner. A challenger needs a strictly better rank.
 * Manual mode pins the selected candidate; a missing pin renders the automatic best as a
 * clearly temporary fallback without clearing the pin.
 *
 * <p>Reason strings carry candidate IDs, ranks, and status only, never lyric bodies.
 */
public final class CatalogResolver {
    private CatalogResolver() {
    }

    /** Resolution outcome: the candidate to render plus the machine-readable reason. */
    public static final class Resolution {
        public final CatalogCandidate winner;
        /** e.g. {@code auto:apple|line|exact-provider|complete}, never lyric text. */
        public final String reason;
        /** True when the winner is standing in for a missing manual pin. */
        public final boolean temporary;

        Resolution(CatalogCandidate winner, String reason, boolean temporary) {
            this.winner = winner;
            this.reason = reason == null ? "" : reason;
            this.temporary = temporary;
        }
    }

    /**
     * Resolves which candidate renders. Pure and deterministic: equal inputs always elect the
     * same winner regardless of callback order.
     */
    public static Resolution resolve(List<CatalogCandidate> candidates, CatalogSelection selection) {
        List<CatalogCandidate> ranked = ranked(candidates);
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            CatalogCandidate pinned = byId(ranked, selection.candidateId);
            if (pinned != null) return new Resolution(pinned, describe("manual", pinned), false);
            if (!ranked.isEmpty()) {
                return new Resolution(ranked.get(0),
                        "manual-missing-temporary:" + describe("auto", ranked.get(0)), true);
            }
            return new Resolution(null, "manual-missing-empty", true);
        }
        if (ranked.isEmpty()) return new Resolution(null, "auto-empty", false);
        return new Resolution(ranked.get(0), describe("auto", ranked.get(0)), false);
    }

    /**
     * Resolves with hysteresis: the incumbent (already-rendered automatic winner) keeps its seat
     * against equal-ranked challengers and equal digests. A challenger takes over only with a
     * strictly better rank tuple — never an automatic downgrade.
     */
    public static Resolution resolveWithIncumbent(List<CatalogCandidate> candidates,
                                                  CatalogSelection selection,
                                                  CatalogCandidate incumbent) {
        Resolution fresh = resolve(candidates, selection);
        if (fresh.winner == null || incumbent == null || !incumbent.hasValidProviderTiming()) return fresh;
        if (selection != null && selection.mode == SelectionMode.MANUAL && !fresh.temporary) {
            return fresh;
        }
        if (fresh.temporary) return fresh;
        if (fresh.winner.candidateId.equals(incumbent.candidateId)) return fresh;
        if (fresh.winner.canonicalDigest.equals(incumbent.canonicalDigest)) {
            return new Resolution(incumbent, describe("auto-incumbent-same-digest", incumbent),
                    false);
        }
        if (!strictlyBetterRank(fresh.winner, incumbent)) {
            return new Resolution(incumbent, describe("auto-incumbent-hold", incumbent), false);
        }
        return fresh;
    }

    /**
     * Applies the runtime source policy before resolving Auto. Manual picks remain readable even
     * when their provider is disabled. Auto uses the primary stack before configured fallbacks;
     * Source order honors the full user order. Quality ranks variants within the selected source.
     */
    public static Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                               CatalogSelection selection,
                                               CatalogCandidate incumbent,
                                               List<SourceId> enabledOrder,
                                               boolean sourceOrderMode) {
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            CatalogCandidate pinned = byId(ranked(candidates), selection.candidateId);
            if (pinned != null) return new Resolution(pinned, describe("manual", pinned), false);
            Resolution fallback = resolveConfigured(candidates, CatalogSelection.auto(selection.trackId),
                    incumbent, enabledOrder, sourceOrderMode);
            return new Resolution(fallback.winner, "manual-missing-temporary:" + fallback.reason, true);
        }
        List<CatalogCandidate> enabled = new ArrayList<>();
        if (candidates != null && enabledOrder != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && enabledOrder.contains(candidate.sourceId)) {
                    enabled.add(candidate);
                }
            }
        }
        // A primary response owns the text and credit even when a fallback has richer timing.
        // Compare quality only between variants of the same acquisition source.
        List<SourceId> order = new CatalogPolicy(enabledOrder, sourceOrderMode).automaticOrder();
        for (SourceId source : order) {
            List<CatalogCandidate> fromSource = new ArrayList<>();
            boolean incumbentPresent = false;
            for (CatalogCandidate candidate : enabled) {
                if (candidate.sourceId != source) continue;
                fromSource.add(candidate);
                if (incumbent != null && candidate.candidateId.equals(incumbent.candidateId)) {
                    incumbentPresent = true;
                }
            }
            List<CatalogCandidate> ranked = ranked(fromSource);
            if (ranked.isEmpty()) continue;
            CatalogCandidate winner = ranked.get(0);
            if (incumbentPresent && incumbent.hasValidProviderTiming()
                    && !strictlyBetterRank(winner, incumbent)) winner = incumbent;
            return new Resolution(winner, describe(sourceOrderMode ? "source-order" : "auto-anchor",
                    winner), false);
        }
        return new Resolution(null, sourceOrderMode ? "source-order-empty" : "auto-empty", false);
    }

    /** Ranked best-first; weak matches excluded, never null. */
    static List<CatalogCandidate> ranked(List<CatalogCandidate> candidates) {
        List<CatalogCandidate> out = new ArrayList<>();
        if (candidates != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && candidate.hasValidProviderTiming()
                        && candidate.matchMethod != MatchMethod.WEAK) out.add(candidate);
            }
        }
        Collections.sort(out, new Comparator<CatalogCandidate>() {
            @Override public int compare(CatalogCandidate a, CatalogCandidate b) {
                return CatalogResolver.compare(a, b);
            }
        });
        traceDecision(out);
        return out;
    }

    /**
     * One line per decision: every candidate with the values the comparison orders on, and the
     * winner.
     *
     * <p>Added because "the panel says this source is available" and "this source returned a
     * document for this track" look identical on screen, and telling them apart from outside took
     * several rounds. Reading this line says which it is: a source missing from the list returned
     * nothing for the track, and a source present but not first lost the comparison, with the
     * four values that decided it shown beside its name.
     */
    private static void traceDecision(List<CatalogCandidate> ranked) {
        try {
            StringBuilder line = new StringBuilder();
            for (CatalogCandidate candidate : ranked) {
                if (line.length() > 0) line.append("  ");
                line.append(candidate.sourceId)
                        .append("[").append(candidate.matchMethod)
                        .append(" trans=").append(providedTextCount(candidate))
                        .append(" trad=").append(Math.round(lyricsTraditionalShare(candidate) * 100))
                        .append("% caps=").append(capabilityCount(candidate))
                        .append(" conf=").append(Math.round(candidate.matchConfidence * 100))
                        .append(" dt=").append(candidate.durationDeltaMs)
                        .append("]");
            }
            XpLog.log("SpotifyPlusSpicy candidates " + ranked.size() + ": "
                    + (line.length() == 0 ? "none" : line.toString())
                    + "  -> " + (ranked.isEmpty() ? "none" : ranked.get(0).sourceId));
        } catch (Throwable ignored) {
            // A trace must never affect the decision it describes.
        }
    }

    /** Negative when {@code a} outranks {@code b}. */
    /**
     * How far a document may run PAST the recording's end before it is read as a different edit.
     *
     * <p>One-sided. A document that stops early is ordinary and says nothing - an outro, a fade, an
     * instrumental close - while a document with lines after the recording has ended is a longer
     * edit. Comparing the absolute difference instead, briefly, was what let an unsynced file beat
     * two synced ones.
     */
    private static final long DISQUALIFYING_OVERRUN_MS = 10000L;

    /** Characters of a document examined when judging its script. */
    private static final int SCRIPT_SAMPLE_CHARS = 400;

    static int compare(CatalogCandidate a, CatalogCandidate b) {
        // A document that outruns the recording is a different edit; one that stops early is not.
        //
        // The check is one-sided on purpose. This was briefly |dt| against a threshold, placed ahead
        // of the sync level, and that promoted an unsynced document over two synced ones: both of the
        // synced ones ended about nineteen seconds early, tripped the threshold, and lost to a Static
        // file with no timeline at all. Ending early is ordinary - an outro, a fade, an instrumental
        // close - and one of that pair was the correct document, so earliness is evidence of
        // nothing. Running past the end is different in kind: the recording has stopped and the file
        // still has lines, which only happens with a longer edit.
        boolean aOutruns = a.durationDeltaMs >= DISQUALIFYING_OVERRUN_MS;
        boolean bOutruns = b.durationDeltaMs >= DISQUALIFYING_OVERRUN_MS;
        if (aOutruns != bOutruns) return aOutruns ? 1 : -1;
        int diff = a.timingLevel.ordinal() - b.timingLevel.ordinal();
        if (diff != 0) return diff;
        if (a.complete != b.complete) return a.complete ? -1 : 1;
        // What the reader actually gets, ahead of how the candidate was found.
        //
        // Both of these used to sit below the match method, and the match method decides that a
        // candidate found by exact track id is not equivalent to one found by search - so Spotify,
        // which arrives exactly, won before either preference was consulted. The panel then showed
        // Spotify's or LRCLIB's Traditional text while NetEase held a Simplified one with a
        // translation, which is what the owner kept reporting.
        //
        // That order exists to break ties between otherwise equivalent candidates, and "carries the
        // translation the owner asked for", and "is written in the script the owner reads", are not
        // ties. Ordering the translation after the source rank is what made the panel seat QQ Music
        // - word timing, no translation - over NetEase's word timing *with* a translation, purely
        // because QQ sits earlier in the enum; the owner then saw a lyrics screen with no
        // translation at all and no way to tell why.
        diff = providedTextCount(b) - providedTextCount(a);
        if (diff != 0) return diff;
        // The script the lyrics are actually written in. A provider can serve a Simplified title and
        // Traditional lines, so the title's script - which the per-provider ranking already uses -
        // cannot answer this.
        diff = Double.compare(lyricsTraditionalShare(a), lyricsTraditionalShare(b));
        if (diff != 0) return diff;
        diff = a.matchMethod.ordinal() - b.matchMethod.ordinal();
        if (diff != 0) return diff;
        // Evidence, then the enum.
        //
        // These three used to sit below sourceRank, so two candidates that agreed on everything
        // visible were separated by which enum constant came first - AMLL over QQ, for instance,
        // while QQ held the richer document. Position in SourceId is arbitrary; how many extra
        // features the document carries, how confidently it matched and how close its runtime is
        // are not. The enum stays last as the final tie-break it was always meant to be.
        diff = capabilityCount(b) - capabilityCount(a);
        if (diff != 0) return diff;
        if (a.timingHealthy != b.timingHealthy) return a.timingHealthy ? -1 : 1;
        diff = Double.compare(b.matchConfidence, a.matchConfidence);
        if (diff != 0) return diff;
        diff = Long.compare(Math.abs(a.durationDeltaMs), Math.abs(b.durationDeltaMs));
        if (diff != 0) return diff;
        diff = sourceRank(a.sourceId) - sourceRank(b.sourceId);
        if (diff != 0) return diff;
        return a.candidateId.compareTo(b.candidateId);
    }

    /**
     * True only when {@code challenger} beats the incumbent on the coarse rank tuple (timing,
     * completeness, identity, provided text, provider, capabilities, health). Confidence, duration
     * fit, and row order elect fresh winners but never dethrone a seated one: an equal-ranked
     * result is not a material quality improvement.
     */
    static boolean strictlyBetterRank(CatalogCandidate challenger, CatalogCandidate incumbent) {
        // Same one-sided rule as compare(): outrunning the recording is disqualifying, stopping
        // early is not.
        boolean challengerOutruns =
                challenger.durationDeltaMs >= DISQUALIFYING_OVERRUN_MS;
        boolean incumbentOutruns =
                incumbent.durationDeltaMs >= DISQUALIFYING_OVERRUN_MS;
        if (challengerOutruns != incumbentOutruns) return !challengerOutruns;
        if (challenger.timingLevel.ordinal() != incumbent.timingLevel.ordinal()) {
            return challenger.timingLevel.ordinal() < incumbent.timingLevel.ordinal();
        }
        if (challenger.complete != incumbent.complete) return challenger.complete;
        // Same order as compare(): the text the owner reads first, the provenance after.
        //
        // Without this reorder the ranking above would have been pointless - a candidate carrying
        // the translation, or the Simplified script, could never take the seat, because the
        // incumbent's match method was consulted before either and an exact-id incumbent wins that.
        if (providedTextCount(challenger) != providedTextCount(incumbent)) {
            return providedTextCount(challenger) > providedTextCount(incumbent);
        }
        // A more Simplified edition is a material improvement for the same reason a translation
        // is: it is what gets read, not a tie-break between equals.
        double challengerTraditional = lyricsTraditionalShare(challenger);
        double incumbentTraditional = lyricsTraditionalShare(incumbent);
        if (challengerTraditional != incumbentTraditional) {
            return challengerTraditional < incumbentTraditional;
        }
        if (challenger.matchMethod.ordinal() != incumbent.matchMethod.ordinal()) {
            return challenger.matchMethod.ordinal() < incumbent.matchMethod.ordinal();
        }
        if (capabilityCount(challenger) != capabilityCount(incumbent)) {
            return capabilityCount(challenger) > capabilityCount(incumbent);
        }
        if (challenger.timingHealthy != incumbent.timingHealthy) return challenger.timingHealthy;
        if (sourceRank(challenger.sourceId) != sourceRank(incumbent.sourceId)) {
            return sourceRank(challenger.sourceId) < sourceRank(incumbent.sourceId);
        }
        return false;
    }

    /**
     * How much of the two texts the owner may actually want the provider supplies itself.
     *
     * <p>Deliberately narrower than {@link #capabilityCount}, which also counts background vocals,
     * duet marking and credits: those are extras a reader notices, while a translation and a
     * transliteration are the two things the panel has switches for, and the two whose absence is
     * read as the feature being broken rather than the source being different.
     */
    /**
     * How much of this candidate's own lyrics are written in Traditional, 0..1.
     *
     * <p>Measured on the document rather than the title, because the two can disagree: a catalogue
     * will serve a Simplified track name and Traditional lines, and it is the lines that get read.
     * The share is sample-bounded - script is a property of the whole document and a few hundred
     * characters settle it, while this runs once per comparison inside every sort.
     *
     * <p>Returns 0 for a document with no Han characters, which is what an English or Japanese
     * release has: there is no script to prefer, and it must not read as fully Traditional.
     */
    static double lyricsTraditionalShare(CatalogCandidate candidate) {
        String sample = lyricsText(candidate);
        if (sample.isEmpty()) return 0d;
        return ChineseScriptVariants.traditionalConfidence(sample);
    }

    /**
     * The first few hundred characters of a candidate's own lyrics text.
     *
     * <p>Decoded, not read raw. {@code normalizedDocument} holds a {@link CanonicalSourceCodec}
     * record - the lyrics are inside it - and measuring the record itself returns zero for every
     * candidate, because an encoded record contains no Han characters. That is exactly what the
     * decision trace showed: every source at nought percent, including one whose text is plainly
     * Traditional, which is a measurement that never happened rather than a preference that lost.
     *
     * <p>Bounded because this runs inside the comparator: script is settled by a few hundred
     * characters, and the sort only ever holds a handful of candidates.
     */
    private static String lyricsText(CatalogCandidate candidate) {
        if (candidate == null) return "";
        try {
            CanonicalSourceCodec.Record record =
                    CanonicalSourceCodec.decode(candidate.normalizedDocument);
            if (record == null || record.document == null || record.document.lines == null) return "";
            StringBuilder sample = new StringBuilder();
            for (com.spotifyplusplus.lyrics.LyricsLine line : record.document.lines) {
                if (line == null || line.text == null) continue;
                sample.append(line.text).append(' ');
                if (sample.length() >= SCRIPT_SAMPLE_CHARS) break;
            }
            return sample.toString();
        } catch (Throwable ignored) {
            // A document that cannot be decoded has no measurable script, which reads as no
            // preference - never as a reason to refuse the candidate.
            return "";
        }
    }

    private static int providedTextCount(CatalogCandidate candidate) {
        int count = 0;
        if (candidate.hasProviderTranslation) count++;
        if (candidate.hasProviderTransliteration) count++;
        return count;
    }

    private static int sourceRank(SourceId source) {
        return source == null ? Integer.MAX_VALUE : source.ordinal();
    }

    private static int capabilityCount(CatalogCandidate candidate) {
        int count = 0;
        if (candidate.hasProviderTranslation) count++;
        if (candidate.hasProviderTransliteration) count++;
        if (candidate.hasBackgroundVocals) count++;
        if (candidate.hasDuet) count++;
        if (candidate.hasCredits) count++;
        return count;
    }

    private static CatalogCandidate byId(List<CatalogCandidate> ranked, String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        for (CatalogCandidate candidate : ranked) {
            if (candidateId.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    private static String describe(String prefix, CatalogCandidate winner) {
        String source = winner.sourceId == null ? "unknown" : winner.sourceId.id;
        return prefix + ":" + source
                + "|" + winner.timingLevel.name().toLowerCase(java.util.Locale.ROOT)
                + "|" + winner.matchMethod.name().toLowerCase(java.util.Locale.ROOT)
                + "|" + (winner.complete ? "complete" : "incomplete")
                + "|" + winner.candidateId;
    }
}
