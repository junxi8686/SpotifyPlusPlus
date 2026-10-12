package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogRanker.Resolution;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SelectionMode;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.providers.SpicyOrgPolicy;
import com.spotifyplusplus.xposed.XpLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Decides whether a track visit asks any provider, and which ones. Pure: the stored catalog
 * state, the policy, and the clock are the only inputs, so a revisit makes the same decision
 * offline, after a restart, and in any callback order.
 *
 * <p>Rules:
 * <ul>
 *   <li>A manual pin stops fallback probes. An enabled org pin still soft-refreshes its source.</li>
 *   <li>A seat does not stop acquisition. Every enabled source that is due is still asked, so a
 *       later answer can outrank the one on screen - the owner asked for progressive re-ranking, not
 *       first-answer-wins, and the first answer is displayed immediately by the repository while the
 *       rest of the round continues.</li>
 *   <li>The round is complete when no source is due: every enabled source has answered, been refused
 *       with a retry horizon, or was already attempted during this visit.</li>
 *   <li>Ask one due source, then re-plan after its terminal outcome. Cached lower-priority
 *       lyrics stay visible while earlier primary sources are checked.</li>
 *   <li>Only the sync-upgrade option opens donor probes; missing verses do not imply aligned timing.</li>
 *   <li>Explicit picker checks bypass this planner entirely: the owner asked for that source.</li>
 * </ul>
 */
public final class AcquisitionPlanner {
    /** A provider that answered "no lyrics" (or returned only a rejected item) is asked again after this. */
    public static final long NOT_FOUND_RETRY_MS = 24L * 60L * 60L * 1000L;
    /** First retry after a transport or server failure; doubles per consecutive failure. */
    public static final long TRANSIENT_BASE_RETRY_MS = 30L * 1000L;
    public static final long TRANSIENT_MAX_RETRY_MS = 6L * 60L * 60L * 1000L;
    /** A static seat re-checks sources that already answered after this long. */
    public static final long STATIC_UPGRADE_RETRY_MS = NOT_FOUND_RETRY_MS;

    private AcquisitionPlanner() {
    }

    public enum Action {
        /** Ask the sources in {@link Plan#scope} now. */
        FETCH,
        /** Ask nothing now; {@link Plan#retryAtMs} says when a source becomes due, or 0 for never. */
        NONE
    }

    public static final class Plan {
        public final Action action;
        public final AcquisitionScope scope;
        /** Wall-clock time the next source becomes due; 0 when nothing ever will this visit. */
        public final long retryAtMs;
        public final String reason;

        Plan(Action action, AcquisitionScope scope, long retryAtMs, String reason) {
            this.action = action;
            this.scope = scope;
            this.retryAtMs = Math.max(0L, retryAtMs);
            this.reason = reason == null ? "" : reason;
        }

        public boolean fetches() {
            return action == Action.FETCH;
        }
    }

    /**
     * Ask every source that is due right now, all at once.
     *
     * <p>{@link #plan} deliberately proposes one source per round so each provider's outcome is
     * recorded before the next is tried. That keeps the retry bookkeeping tidy but makes the owner
     * wait for the sum of every provider, and it meant a word-timed answer from QQ Music only
     * arrived after Spotify's and NetEase's line-timed answers had each taken a turn - tens of
     * seconds apart.
     *
     * <p>This applies the same eligibility rules as {@link #plan} - same hard filters, same retry
     * horizons - and then hands the whole due set to one racing scope. The repository consults them
     * concurrently, shows the first usable answer, and re-ranks on every later arrival, replacing the
     * displayed document only for a strictly better one.
     *
     * <p>A seat does not shrink the due set. That was the reported bug: the owner's wrong-language,
     * untranslated first answer took the seat and every other source was marked
     * {@code settled-behind-seat} for a day, so the better document was never fetched. Now a held seat
     * only means there is something on screen; the rest of the round still runs.
     */
    public static Plan racePlan(CatalogState state, CatalogPolicy policy, Resolution rendered,
                                long nowMs, boolean includeLocal, Set<SourceId> attemptedThisVisit) {
        Set<SourceId> attempted = attemptedThisVisit == null
                ? Collections.emptySet() : attemptedThisVisit;
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        if (p.enabledOrder.isEmpty()) {
            traceRound("race", p, Collections.<SourceId>emptyList(), null, "all-sources-disabled");
            return none(0L, "all-sources-disabled");
        }
        CatalogCandidate seat = rendered == null ? null : rendered.winner;
        boolean pinned = seat != null && !rendered.temporary
                && state.selection.mode == SelectionMode.MANUAL;
        boolean orgSeat = seat != null && seat.sourceId == SourceId.SPICY_ORG
                && p.enabled(SourceId.SPICY_ORG);
        if (pinned && !orgSeat) {
            traceRound("race", p, Collections.<SourceId>emptyList(), null, "manual-pin");
            return none(0L, "manual-pin");
        }

        // Same eligible set and order as plan(); only the number asked per round differs.
        List<SourceId> order = autoSources(p);
        List<SourceId> due = new ArrayList<>();
        Map<SourceId, String> blocked = new LinkedHashMap<>();
        long nextDue = 0L;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < order.size(); index++) {
                SourceId source = order.get(index);
                if (attempted.contains(source) != (pass == 1)) {
                    if (pass == 0) blocked.put(source, "already-attempted");
                    continue;
                }
                boolean orgRefresh = source == SourceId.SPICY_ORG && orgSeat;
                if (pinned && !orgRefresh) {
                    blocked.put(source, "manual-pin");
                    continue;
                }
                if (source == SourceId.SPOTIFY_NATIVE && !includeLocal) {
                    blocked.put(source, "local-already-tried");
                    continue;
                }
                ProviderRecord record = state.provider(source);
                long at = dueAt(record, state, p, source);
                if (orgRefresh) {
                    long refreshAt = seat.fetchedAtMs + SpicyOrgPolicy.REFRESH_AFTER_MS;
                    at = record.status == CatalogSource.ProviderStatus.AVAILABLE ? refreshAt
                            : Math.max(refreshAt, at);
                }
                if (at <= nowMs) {
                    if (!due.contains(source)) due.add(source);
                } else {
                    blocked.put(source, at == Long.MAX_VALUE ? "never"
                            : "due-in-" + Math.max(0L, at - nowMs) + "ms");
                    if (at != Long.MAX_VALUE && (nextDue == 0L || at < nextDue)) {
                        nextDue = at;
                    }
                }
            }
        }
        if (due.isEmpty()) {
            String reason = pinned ? "manual-pin"
                    : roundComplete(order, blocked) ? "round-complete" : "sources-pending";
            traceRound("race", p, due, blocked, reason);
            return none(nextDue, reason);
        }
        traceRound("race", p, due, blocked, "race-due-sources");
        return new Plan(Action.FETCH,
                new AcquisitionScope(due, p.sourceOrderMode, p.karaokeOriginalLyrics, true),
                nextDue, "race-due-sources");
    }

    /** Inclusion guard and due-check shared by {@link #racePlan}'s loop. */
    private static long dueAt(ProviderRecord record, CatalogState state, CatalogPolicy p,
                              SourceId source) {
        return record.status == CatalogSource.ProviderStatus.AVAILABLE
                && hasOnlyPolicyIneligibleCandidates(state, p, source)
                ? 0L : dueAtMs(record, false);
    }

    /**
     * @param rendered   what the catalog renders for this state (see {@link CatalogDecisions#render})
     * @param includeLocal false once Spotify native was already tried during this visit
     */
    public static Plan plan(CatalogState state, CatalogPolicy policy, Resolution rendered,
                            long nowMs, boolean includeLocal) {
        return plan(state, policy, rendered, nowMs, includeLocal, Collections.emptySet());
    }

    /** Finish the initial source walk before retrying a source already attempted this visit. */
    public static Plan plan(CatalogState state, CatalogPolicy policy, Resolution rendered,
                            long nowMs, boolean includeLocal, Set<SourceId> attemptedThisVisit) {
        Set<SourceId> attempted = attemptedThisVisit == null
                ? Collections.emptySet() : attemptedThisVisit;
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        if (p.enabledOrder.isEmpty()) {
            traceRound("walk", p, Collections.<SourceId>emptyList(), null, "all-sources-disabled");
            return none(0L, "all-sources-disabled");
        }
        CatalogCandidate seat = rendered == null ? null : rendered.winner;
        boolean pinned = seat != null && !rendered.temporary
                && state.selection.mode == SelectionMode.MANUAL;
        boolean orgSeat = seat != null && seat.sourceId == SourceId.SPICY_ORG
                && p.enabled(SourceId.SPICY_ORG);
        if (pinned && !orgSeat) {
            traceRound("walk", p, Collections.<SourceId>emptyList(), null, "manual-pin");
            return none(0L, "manual-pin");
        }
        List<SourceId> order = autoSources(p);
        Map<SourceId, String> blocked = new LinkedHashMap<>();
        long nextDue = 0L;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < order.size(); index++) {
                SourceId source = order.get(index);
                if (attempted.contains(source) != (pass == 1)) {
                    if (pass == 0) blocked.put(source, "already-attempted");
                    continue;
                }
                // A cached fallback can render while an earlier source is checked, and that earlier
                // source is still asked. A held seat never suppresses a source either: it is only a
                // reason to have something on screen while the rest of the round runs.
                boolean orgRefresh = source == SourceId.SPICY_ORG && orgSeat;
                if (pinned && !orgRefresh) {
                    blocked.put(source, "manual-pin");
                    continue;
                }
                if (source == SourceId.SPOTIFY_NATIVE && !includeLocal) {
                    blocked.put(source, "local-already-tried");
                    continue;
                }
                ProviderRecord record = state.provider(source);
                long at = record.status == CatalogSource.ProviderStatus.AVAILABLE
                        && hasOnlyPolicyIneligibleCandidates(state, p, source)
                        ? 0L : dueAtMs(record, false);
                if (orgRefresh) {
                    // Soft refresh does not retire the usable 21–30-day anchor. A failed attempt
                    // observes the provider horizon instead of repeatedly probing an overdue row.
                    long refreshAt = seat.fetchedAtMs + SpicyOrgPolicy.REFRESH_AFTER_MS;
                    at = record.status == CatalogSource.ProviderStatus.AVAILABLE ? refreshAt
                            : Math.max(refreshAt, at);
                }
                if (at <= nowMs) {
                    String reason = orgRefresh ? "org-refresh"
                            : seat == null ? "no-seat" : "seat-refresh";
                    // Re-plan after this provider's terminal outcome. Never speculate with a
                    // parallel fallback request while a prior route is still pending.
                    traceRound("walk", p, java.util.Collections.singletonList(source), blocked,
                            reason);
                    return new Plan(Action.FETCH, new AcquisitionScope(
                            java.util.Collections.singletonList(source), true,
                            p.karaokeOriginalLyrics), nextDue, reason);
                }
                blocked.put(source, at == Long.MAX_VALUE ? "never"
                        : "due-in-" + Math.max(0L, at - nowMs) + "ms");
                if (at != Long.MAX_VALUE && (nextDue == 0L || at < nextDue)) nextDue = at;
            }
        }
        String reason = pinned ? "manual-pin"
                : roundComplete(order, blocked) ? "round-complete" : "sources-pending";
        traceRound("walk", p, Collections.<SourceId>emptyList(), blocked, reason);
        return none(nextDue, reason);
    }

    /**
     * True when the round has nothing left to wait for: no source is due, and every enabled source
     * already has a stored outcome for this visit - answered, refused with a retry horizon, in a
     * backoff, or attempted during this visit.
     *
     * <p>This is how "stop when the round is complete" is expressed, and it is the replacement for
     * the old {@code anchor-satisfied} reason, which read as "a seat exists, so nothing more will be
     * asked". A round that ends because every source is settled is a finished round; a source still
     * marked due would have been asked, so it cannot be settled here.
     *
     * <p>It also cannot spin: nothing in this planner waits on a source in flight. A round that ends
     * with an unanswered source schedules the next attempt at that source's own horizon
     * ({@link #Plan#retryAtMs}), and the session stops after {@code MAX_AUTO_ATTEMPTS} rounds.
     */
    private static boolean roundComplete(List<SourceId> order, Map<SourceId, String> blocked) {
        for (SourceId source : order) {
            String settled = blocked.get(source);
            if (settled == null) return false;
            if (settled.startsWith("due-in-") || "never".equals(settled)
                    || "already-attempted".equals(settled)
                    || "local-already-tried".equals(settled)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * An owner-requested reload of the current track: every automatic source is asked regardless
     * of stored outcomes. The displayed seat stays until a commit elects a different one.
     */
    public static Plan refreshAll(CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        List<SourceId> sources = autoSources(p);
        if (sources.isEmpty()) {
            traceRound("refresh", p, Collections.<SourceId>emptyList(), null,
                    "all-sources-disabled");
            return none(0L, "all-sources-disabled");
        }
        traceRound("refresh", p, sources, null, "owner-refresh");
        return new Plan(Action.FETCH, new AcquisitionScope(sources, p.sourceOrderMode,
                p.karaokeOriginalLyrics), 0L,
                "owner-refresh");
    }

    /**
     * An owner-requested re-ask of every enabled source, walked in configured order.
     *
     * <p>This explicit preference walk bypasses stored retry horizons and stops at the first
     * usable response. Automatic acquisition instead asks one due source per plan and re-plans
     * after each outcome. Sync donors are requested only through the supplemental gate.
     */
    public static Plan refreshAllInOrder(CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        List<SourceId> sources = autoSources(p);
        if (sources.isEmpty()) {
            traceRound("refresh-ordered", p, Collections.<SourceId>emptyList(), null,
                    "all-sources-disabled");
            return none(0L, "all-sources-disabled");
        }
        traceRound("refresh-ordered", p, sources, null, "owner-refresh-ordered");
        return new Plan(Action.FETCH, new AcquisitionScope(sources, true,
                p.karaokeOriginalLyrics), 0L, "owner-refresh-ordered");
    }

    /**
     * One line per planning round, naming every source that is being asked and every enabled one
     * that is not, with the reason.
     *
     * <p>Exists because a source that never becomes a candidate is invisible in the decision
     * trace: that line can only list candidates that exist, so a source dropped before the
     * request - disabled, expired, held behind the seat, or not yet due - leaves no evidence at
     * all, and the round reads as if that provider had never been configured. Reading {@code
     * not-asked=[spicy_org:not-enabled]} is the whole diagnosis in one line.
     *
     * <p>Identical consecutive lines are printed once: the planner is re-run on every session
     * tick, and a round that decides the same thing is not new information.
     */
    private static void traceRound(String round, CatalogPolicy policy, List<SourceId> asked,
                                   Map<SourceId, String> blocked, String reason) {
        try {
            StringBuilder line = new StringBuilder("SpotifyPlusSpicy sources round=").append(round);
            line.append(" asked=").append(names(asked));
            List<String> notAsked = new ArrayList<>();
            if (blocked != null) {
                for (Map.Entry<SourceId, String> entry : blocked.entrySet()) {
                    if (asked != null && asked.contains(entry.getKey())) continue;
                    notAsked.add(entry.getKey() + ":" + entry.getValue());
                }
            }
            if (!notAsked.isEmpty()) line.append(" not-asked=").append(notAsked);
            line.append(" order=").append(names(autoSources(policy)));
            line.append(policy.sourceOrderMode ? " mode=order" : " mode=auto");
            if (reason != null && !reason.isEmpty()) line.append(" reason=").append(reason);
            String text = line.toString();
            if (text.equals(lastRoundLine)) return;
            lastRoundLine = text;
            XpLog.log(text);
        } catch (Throwable ignored) {
            // A trace must never change the plan it describes.
        }
    }

    /** Last line printed by {@link #traceRound}; identical repeats are suppressed. */
    private static volatile String lastRoundLine = "";

    private static String names(List<SourceId> sources) {
        StringBuilder out = new StringBuilder("[");
        if (sources != null) {
            for (SourceId source : sources) {
                if (out.length() > 1) out.append(',');
                out.append(source == null ? "none" : source.id);
            }
        }
        return out.append(']').toString();
    }

    /**
     * When one source's stored outcome makes it worth asking again. {@code Long.MAX_VALUE} means
     * never automatically.
     *
     * @param upgradeProbe true when a static seat already renders; a source that already answered
     *                     is then re-asked only after {@link #STATIC_UPGRADE_RETRY_MS}
     */
    public static long dueAtMs(ProviderRecord record, boolean upgradeProbe) {
        if (record == null) return 0L;
        switch (record.status) {
            case NOT_CHECKED:
            case NEEDS_REFRESH:
                return 0L;
            case TRANSIENT_ERROR:
                return record.lastAttemptMs + transientBackoffMs(record.attemptCount);
            case AVAILABLE:
                // A source that already delivered is only re-asked to look for an upgrade.
                return upgradeProbe ? record.lastAttemptMs + STATIC_UPGRADE_RETRY_MS : Long.MAX_VALUE;
            case NOT_FOUND:
            case REJECTED:
            case DISABLED:
                return record.lastAttemptMs + NOT_FOUND_RETRY_MS;
            default:
                return 0L;
        }
    }

    static long transientBackoffMs(int attempts) {
        long delay = TRANSIENT_BASE_RETRY_MS;
        for (int i = 1; i < Math.max(1, attempts) && delay < TRANSIENT_MAX_RETRY_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, TRANSIENT_MAX_RETRY_MS);
    }

    /**
     * Sources an automatic visit may ask, in the configured order. Selection still follows the
     * configured ranking mode after provider outcomes are stored.
     */
    static List<SourceId> autoSources(CatalogPolicy policy) {
        return policy.automaticOrder();
    }

    private static boolean hasOnlyPolicyIneligibleCandidates(CatalogState state,
                                                             CatalogPolicy policy,
                                                             SourceId source) {
        boolean found = false;
        for (CatalogCandidate candidate : state.candidates) {
            if (candidate.sourceId != source) continue;
            found = true;
            if (policy.eligibleForAuto(candidate)) return false;
        }
        return found;
    }

    private static Plan none(long retryAtMs, String reason) {
        return new Plan(Action.NONE, AcquisitionScope.NONE, retryAtMs, reason);
    }
}
