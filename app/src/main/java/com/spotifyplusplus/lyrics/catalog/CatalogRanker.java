package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;

import java.util.List;

/**
 * The one automatic-ranking entry point for catalog candidates, and the place the decision layer
 * holds its result.
 *
 * <p>There is one rule: the points system in {@link CatalogResolver}. This class was the seam that
 * chose between two algorithms while the tiered comparison still existed; the owner removed that
 * comparison, so the seam is gone and what remains is an entry point. It is kept rather than
 * deleted because it is the surface the decision layer (<code>CatalogDecisions</code>,
 * <code>CatalogPickerModel</code>, <code>CatalogStore</code>) is written against, and because the
 * owner's configured source order has to reach the ranking rule from here - a single place to see
 * that Auto, the picker's order and the reason strings all read one policy.
 *
 * <p>The order comes from {@link CatalogPolicy#enabledOrder}, lifted out of the settings store by
 * {@link CatalogPolicy#read(android.content.Context)}. It is not part of any stored candidate,
 * digest, request identity or cache key, and it is not cached here, so changing it takes effect on
 * the next resolution with no reinstall, no cache clear and no re-fetch.
 */
public final class CatalogRanker {
    private CatalogRanker() {
    }

    /**
     * Resolution outcome: the candidate to render, the machine-readable reason, and whether the
     * winner is standing in for a missing manual pin.
     *
     * <p>Immutable and carrying no lyric text: a reason names sources, timing tiers, status and
     * candidate IDs only, so one can be logged or shown without leaking a lyric body.
     */
    public static final class Resolution {
        /** The candidate the rule elected, or null when the round has no seat. */
        public final CatalogCandidate winner;
        /** e.g. {@code auto:points:amll|line|exact_spotify_id|complete}, never lyric text. */
        public final String reason;
        /** True when the winner is standing in for a missing manual pin. */
        public final boolean temporary;

        public Resolution(CatalogCandidate winner, String reason, boolean temporary) {
            this.winner = winner;
            this.reason = reason == null ? "" : reason;
            this.temporary = temporary;
        }
    }

    public static Resolution resolve(List<CatalogCandidate> candidates,
                                     CatalogSelection selection,
                                     CatalogCandidate incumbent,
                                     CatalogPolicy policy) {
        return resolve(candidates, selection, incumbent,
                policy == null ? null : policy.enabledOrder);
    }

    /**
     * Resolves which candidate renders.
     *
     * <p>{@code configuredOrder} is the owner's source order, used as the tie-break behind the
     * points total and ahead of the fixed provider list.
     */
    public static Resolution resolve(List<CatalogCandidate> candidates,
                                     CatalogSelection selection,
                                     CatalogCandidate incumbent,
                                     List<SourceId> configuredOrder) {
        return CatalogResolver.resolveWithIncumbent(
                candidates, selection, incumbent, configuredOrder);
    }

    /**
     * Applies the runtime source policy, then resolves Auto.
     *
     * <p>The policy filter runs first because it is about which sources may be acquired at all - the
     * rule never gets to see a disabled source's candidate. Source order mode is the owner's
     * explicit order and is applied before the rule as well, so it behaves the same whichever
     * entries the policy enables.
     */
    public static Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                               CatalogSelection selection,
                                               CatalogCandidate incumbent,
                                               CatalogPolicy policy) {
        return resolveConfigured(candidates, selection, incumbent, policy,
                policy == null ? null : policy.enabledOrder, policy == null || policy.sourceOrderMode);
    }

    /**
     * As {@link #resolveConfigured(List, CatalogSelection, CatalogCandidate, CatalogPolicy)}, with
     * the enabled order stated directly, for a caller that has already filtered the candidates.
     */
    static Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                        CatalogSelection selection,
                                        CatalogCandidate incumbent,
                                        CatalogPolicy policy,
                                        List<SourceId> enabledOrder,
                                        boolean sourceOrderMode) {
        return CatalogResolver.resolveConfigured(candidates, selection, incumbent, enabledOrder,
                sourceOrderMode, policy != null && policy.preferChineseTranslation);
    }

    /**
     * Ranked best-first; weak matches excluded, never null.
     *
     * <p>Used by the picker, which lists the stored rows in decision order. Routing it through the
     * same rule is what keeps that list and the automatic seat telling the same story.
     */
    public static List<CatalogCandidate> ranked(List<CatalogCandidate> candidates,
                                                List<SourceId> configuredOrder) {
        return CatalogResolver.ranked(candidates, configuredOrder);
    }

    /**
     * The name of the rule that decides, so a log line can say which algorithm produced the seat
     * without running a resolution.
     *
     * <p>It takes no argument because there is one rule. The parameter it used to carry was left
     * behind by the algorithm switch that was cancelled, and a method that accepted an algorithm
     * would keep suggesting a choice the module no longer offers.
     */
    public static String ruleName() {
        return CatalogRankingAlgorithm.POINTS.ruleName();
    }
}
