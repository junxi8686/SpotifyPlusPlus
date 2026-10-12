package com.spotifyplusplus.lyrics.catalog;

/**
 * The automatic ranking rule the build ships, as an identity rather than a choice.
 *
 * <p>One rule exists: the points system implemented in {@link CatalogResolver}. It replaced the
 * tiered comparison outright - the owner removed that comparison rather than keeping it beside this
 * one - and the beta setting that offered the two side by side ("Points", "Tiered") was then removed
 * as well, because a row with one value is not a setting. Nothing reads a stored ranking algorithm
 * any more; the enum survives to name the rule in every decision trace and resolution reason.
 *
 * <p>A device that ran the beta build still holds {@code lyrics_ranking_algorithm=Points} in its
 * settings store. That value is now inert: no declaration claims the key, no row renders it, and no
 * code reads it, so the stale entry changes nothing and needs no migration.
 */
public enum CatalogRankingAlgorithm {
    /** The points system: elimination first, then a score, then the source order. */
    POINTS;

    /**
     * The name the decision trace and the resolution reason carry.
     *
     * <p>Lower-case and hyphenated so it reads as one token beside the other {@code key=value}
     * fields and inside a reason string. The beta build wrote {@code points-beta} here; the
     * stability of the rule is what changed, not the rule, so the token lost the stage marker and
     * nothing else.
     */
    public String ruleName() {
        return "points";
    }

    @Override public String toString() {
        return ruleName();
    }
}
