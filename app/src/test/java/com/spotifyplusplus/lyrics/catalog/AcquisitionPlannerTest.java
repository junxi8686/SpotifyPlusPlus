package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.*;

import com.spotifyplusplus.lyrics.catalog.AcquisitionPlanner.Plan;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.*;
import org.junit.Test;
import java.util.*;

/** Provider outcomes drive sequential fallback; content completeness gates donor acquisition. */
public class AcquisitionPlannerTest {
    private static final String TRACK = "track1";
    private static final long NOW = System.currentTimeMillis();
    private static final CatalogPolicy AUTO = new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG,
            SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB, SourceId.AMLL,
            SourceId.QQ, SourceId.NETEASE), false);

    private static CatalogCandidate candidate(SourceId source, TimingLevel timing, boolean complete) {
        return candidate(source, timing, complete, NOW);
    }

    private static CatalogCandidate candidate(SourceId source, TimingLevel timing, boolean complete,
                                               long fetchedAt) {
        return new CatalogCandidate(source.id, TRACK, source, "item", MatchMethod.EXACT_SPOTIFY_ID,
                .9, 0, timing, complete, true, false, false, false, false, false,
                source.id, "", "[]", null, 1, 1, fetchedAt);
    }

    private static CatalogState state(CatalogCandidate seat, ProviderRecord... records) {
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        for (ProviderRecord record : records) providers.put(record.source, record);
        return new CatalogState(TRACK, seat == null ? Collections.emptyList()
                : Collections.singletonList(seat), providers, CatalogSelection.auto(TRACK), null, true);
    }

    private static ProviderRecord answered(SourceId source, ProviderStatus status, long at, int attempts) {
        return new ProviderRecord(source, status, at, at, 0, attempts);
    }

    private static Plan plan(CatalogState state, CatalogPolicy policy, boolean local) {
        return AcquisitionPlanner.plan(state, policy, CatalogDecisions.render(state, policy), NOW, local);
    }

    @Test public void unseenTrackAsksOnlyRouterThenPrimaryAfterMiss() {
        Plan first = plan(state(null), AUTO, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), first.scope.sources);
        assertTrue(first.scope.sourceOrderMode);
        Plan backup = plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW, 1)), AUTO, true);
        assertEquals(Collections.singletonList(SourceId.APPLE), backup.scope.sources);
    }

    @Test public void aHeldSeatDoesNotSuppressTheRemainingDueSources() {
        // The reported bug, as a rule: the first answer used to take the seat and mark every other
        // source settled-behind-seat for a day, so a wrong-language, untranslated winner stayed on
        // screen with nothing else ever asked. A seat is now only a reason to have something to
        // display while the round keeps running, so the next due source in the owner's order is
        // asked even though a seat is held.
        for (TimingLevel timing : TimingLevel.values()) {
            CatalogCandidate org = candidate(SourceId.SPICY_ORG, timing, true);
            CatalogState seated = state(org);
            assertEquals("a seated org answer still leaves every other source to ask",
                    Collections.singletonList(SourceId.APPLE), plan(seated, AUTO, true).scope.sources);
            assertEquals("and the walk says why it is asking",
                    "seat-refresh", plan(seated, AUTO, true).reason);
            Plan racing = AcquisitionPlanner.racePlan(seated, AUTO,
                    CatalogDecisions.render(seated, AUTO), NOW, true, Collections.emptySet());
            assertTrue("the race asks every source that has not answered: " + racing.scope.sources,
                    racing.scope.sources.contains(SourceId.APPLE));
            assertTrue(racing.scope.sources.contains(SourceId.LRCLIB));
            assertTrue(racing.scope.sources.contains(SourceId.QQ));
            assertFalse("but never the source that already answered",
                    racing.scope.sources.contains(SourceId.SPICY_ORG));
        }
    }

    @Test public void everyEnabledSourceIsAskedButEachOnlyOncePerRound() {
        // Progressive acquisition multiplied by a round: the race asks all unanswered sources, and a
        // source that has answered is not asked again - its own record makes it due only at its
        // refresh horizon. Nothing about this loop can spin: after the round every source is either
        // answered, refused with a horizon, or attempted.
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, true);
        CatalogState stored = state(org);
        Plan race = AcquisitionPlanner.racePlan(stored, AUTO, CatalogDecisions.render(stored, AUTO),
                NOW, true, Collections.emptySet());
        assertEquals(Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB,
                SourceId.AMLL, SourceId.QQ, SourceId.NETEASE), race.scope.sources);
        assertEquals("race-due-sources", race.reason);

        // The same round once every one of them has answered.
        Plan done = AcquisitionPlanner.racePlan(state(org,
                        answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0),
                        answered(SourceId.SPOTIFY_NATIVE, ProviderStatus.AVAILABLE, NOW, 0),
                        answered(SourceId.LRCLIB, ProviderStatus.AVAILABLE, NOW, 0),
                        answered(SourceId.AMLL, ProviderStatus.AVAILABLE, NOW, 0),
                        answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0),
                        answered(SourceId.NETEASE, ProviderStatus.AVAILABLE, NOW, 0)),
                AUTO, CatalogDecisions.render(state(org), AUTO), NOW, false, Collections.emptySet());
        assertFalse("nothing is left to ask", done.fetches());
        assertEquals("round-complete", done.reason);
    }

    @Test public void lowerCachedSeatCannotSuppressEarlierEligiblePrimary() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.SYLLABLE, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG),
                plan(state(apple), AUTO, true).scope.sources);
        CatalogCandidate fallback = candidate(SourceId.QQ, TimingLevel.WORD, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG),
                plan(state(fallback), AUTO, true).scope.sources);
    }

    @Test public void aSeatedBackupNoLongerStopsTheRestOfTheRoundButItsRetryHorizonSurvives() {
        // The seat used to end the round here. It no longer does: the source still in its transient
        // backoff is not due, and the next enabled source that has never been asked is.
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.UNSYNCED, true);
        Plan after = plan(state(apple,
                answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0),
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW, 1)), AUTO, true);
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), after.scope.sources);
        assertEquals("and the failed source's own horizon is still reported",
                NOW + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, after.retryAtMs);
    }

    @Test public void syncUpgradeNoLongerGatesAcquisitionAndNeverReasksAnAnsweredDonor() {
        // The setting decides whether donor timing is used, not whether donor candidates are
        // fetched: with progressive re-ranking every enabled source is a candidate for the seat, and
        // a source that has answered is only due again at its own refresh horizon.
        CatalogPolicy enabled = new CatalogPolicy(AUTO.enabledOrder, false, false, true);
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, true);
        assertEquals("the next unasked source in order, setting or not",
                Collections.singletonList(SourceId.APPLE), plan(state(org), enabled, true).scope.sources);
        assertEquals(Collections.singletonList(SourceId.APPLE), plan(state(org), AUTO, true).scope.sources);
        assertEquals("an answered donor is behind its horizon, so the walk moves past it",
                Collections.singletonList(SourceId.APPLE),
                plan(state(org, answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0)),
                        enabled, true).scope.sources);
    }

    @Test public void partialPrimaryKeepsTheSeatWhileTheRoundContinues() {
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.WORD, false);
        CatalogState stored = state(org);
        assertEquals("the incomplete org answer is still displayed",
                Collections.singletonList(SourceId.APPLE), plan(stored, AUTO, true).scope.sources);
        CatalogPolicy upgrade = new CatalogPolicy(AUTO.enabledOrder, false, false, true);
        Plan next = plan(stored, upgrade, true);
        assertEquals(Collections.singletonList(SourceId.APPLE), next.scope.sources);
        assertEquals("seat-refresh", next.reason);
        assertSame("and it is not displaced by a plan", org, CatalogDecisions.render(stored, upgrade).winner);
    }

    @Test public void manualPinSuppressesAutomaticAcquisition() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.UNSYNCED, true);
        CatalogState stored = state(apple);
        CatalogSelection pin = new CatalogSelection(TRACK, SelectionMode.MANUAL,
                apple.candidateId, apple.sourceId, "item", apple.canonicalDigest);
        stored = new CatalogState(TRACK, stored.candidates, stored.providers, pin, null, true);
        assertEquals("manual-pin", plan(stored, AUTO, true).reason);
    }

    @Test public void configuredSourceOrderRemainsAnExplicitPreferenceWalk() {
        CatalogPolicy order = new CatalogPolicy(Arrays.asList(SourceId.NETEASE, SourceId.APPLE), true);
        assertEquals(Collections.singletonList(SourceId.NETEASE), plan(state(null), order, true).scope.sources);
    }

    @Test public void priorFailuresSkipToNextDueSourceWithoutErasingTheirRetryHorizon() {
        CatalogState stored = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW, 1),
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, NOW, 1));
        Plan next = plan(stored, AUTO, true);
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), next.scope.sources);
        assertEquals(NOW + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, next.retryAtMs);
        assertEquals(Collections.singletonList(SourceId.LRCLIB), plan(stored, AUTO, false).scope.sources);
    }

    @Test public void durableMissBecomesDueAfterHorizon() {
        CatalogPolicy orgOnly = new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false);
        assertFalse(plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW, 1)), orgOnly, true).fetches());
        assertTrue(plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW - AcquisitionPlanner.NOT_FOUND_RETRY_MS, 1)), orgOnly, true).fetches());
    }

    @Test public void transientFailuresBackOffToCap() {
        assertEquals(30_000, AcquisitionPlanner.transientBackoffMs(1));
        assertEquals(60_000, AcquisitionPlanner.transientBackoffMs(2));
        assertEquals(120_000, AcquisitionPlanner.transientBackoffMs(3));
        assertEquals(AcquisitionPlanner.TRANSIENT_MAX_RETRY_MS, AcquisitionPlanner.transientBackoffMs(40));
        assertEquals(Long.MAX_VALUE, AcquisitionPlanner.dueAtMs(
                answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0), false));
        assertEquals(0, AcquisitionPlanner.dueAtMs(ProviderRecord.notChecked(SourceId.APPLE), false));
        assertEquals(0, AcquisitionPlanner.dueAtMs(answered(SourceId.APPLE,
                ProviderStatus.NEEDS_REFRESH, NOW, 0), false));
    }

    @Test public void ineligibleKaraokeCandidateCanBeRecheckedWithoutKaraokeSubstitution() {
        CatalogCandidate karaoke = CatalogDecisionsTest.cand(SourceId.QQ, "item", "digest",
                TimingLevel.WORD, MatchMethod.KARAOKE_SUBSTITUTION);
        CatalogPolicy qq = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, false);
        Plan retry = plan(state(karaoke, answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0)), qq, true);
        assertTrue(retry.fetches());
        assertFalse(retry.scope.karaokeOriginalLyrics);
    }

    @Test public void slowFailuresFinishUnaskedSourcesBeforeDueEarlierRetries() {
        CatalogState failed = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.APPLE, ProviderStatus.TRANSIENT_ERROR, NOW - 40_000, 1));
        Set<SourceId> attempted = EnumSet.of(SourceId.SPICY_ORG, SourceId.APPLE);
        Plan next = AcquisitionPlanner.plan(failed, AUTO, CatalogDecisions.render(failed, AUTO),
                NOW, true, attempted);
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), next.scope.sources);
        attempted.add(SourceId.SPOTIFY_NATIVE);
        Plan afterNative = AcquisitionPlanner.plan(failed, AUTO, CatalogDecisions.render(failed, AUTO),
                NOW, false, attempted);
        assertEquals(Collections.singletonList(SourceId.LRCLIB), afterNative.scope.sources);
        attempted.add(SourceId.LRCLIB);
        assertEquals(Collections.singletonList(SourceId.AMLL), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
    }

    @Test public void initialFallbackWalkReachesOptionalDonorsBeforeDuePrimaryRetry() {
        CatalogState failed = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.APPLE, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.SPOTIFY_NATIVE, ProviderStatus.NOT_FOUND, NOW, 1),
                answered(SourceId.LRCLIB, ProviderStatus.NOT_FOUND, NOW, 1),
                answered(SourceId.AMLL, ProviderStatus.NOT_FOUND, NOW, 1));
        Set<SourceId> attempted = EnumSet.of(SourceId.SPICY_ORG, SourceId.APPLE,
                SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB, SourceId.AMLL);
        assertEquals(Collections.singletonList(SourceId.QQ), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
        attempted.add(SourceId.QQ);
        assertEquals(Collections.singletonList(SourceId.NETEASE), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
        attempted.add(SourceId.NETEASE);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
    }

    @Test public void anOrgRefreshStillRunsOnceTheOtherSourcesAreSettled() {
        // The org soft refresh is unchanged by the seat rule. What changed is the company it keeps:
        // an unattempted backup is now asked first, which is the progressive behaviour the owner
        // asked for, so the refresh runs when nothing else is left due.
        CatalogPolicy two = new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG, SourceId.APPLE), false);
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, true,
                NOW - 22L * 24 * 60 * 60 * 1000);
        CatalogState stored = state(org,
                answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0),
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1));
        Plan next = AcquisitionPlanner.plan(stored, two, CatalogDecisions.render(stored, two),
                NOW, true, EnumSet.of(SourceId.SPICY_ORG));
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), next.scope.sources);
        assertEquals("org-refresh", next.reason);
    }

    @Test public void disabledAndExplicitRefreshContractsRemainAvailable() {
        assertEquals("all-sources-disabled", plan(state(null), new CatalogPolicy(null, false), true).reason);
        Plan refresh = AcquisitionPlanner.refreshAll(AUTO);
        assertEquals(AUTO.enabledOrder, refresh.scope.sources);
        Plan ordered = AcquisitionPlanner.refreshAllInOrder(AUTO);
        assertTrue(ordered.scope.sourceOrderMode);
        assertEquals(AUTO.enabledOrder, ordered.scope.sources);
    }

    @Test public void policySnapshotAndScopeKeepUpgradeAndKaraokeIdentity() {
        CatalogPolicy off = new CatalogPolicy(AUTO.enabledOrder, false, true, false);
        CatalogPolicy on = new CatalogPolicy(AUTO.enabledOrder, false, true, true);
        assertNotEquals(off, on);
        assertTrue(AcquisitionPlanner.refreshAll(on).scope.karaokeOriginalLyrics);
        assertTrue(AcquisitionPlanner.refreshAll(on).scope.key().contains("karaoke-original"));
    }
}
