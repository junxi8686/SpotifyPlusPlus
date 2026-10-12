package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.catalog.AcquisitionPlanner.Plan;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.ProviderStatus;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences.Source;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * SpicyLyrics.org is a lyrics source on exactly the same terms as the other five.
 *
 * <p>Written because the source was silently absent from every automatic round while the picker
 * checked it happily: the settings preference that enables it had no catalogue identity, so it was
 * dropped from {@code enabledOrder} - and everything downstream reads that list. The planner builds
 * the sources it may ask from it, {@code eligibleForAuto} requires membership in it, and the picker
 * does not consult it at all, which is why a tap worked and nothing else did. These assertions pin
 * the two mappings that were wrong, and the behaviour that follows from them.
 */
public class CatalogPolicySourceParityTest {
    /** The owner's stored preference order, SpicyLyrics.org first. */
    private static final List<SourceId> OWNER_ORDER = Arrays.asList(SourceId.SPICY_ORG,
            SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE);

    private static CatalogCandidate candidate(SourceId source) {
        return new CatalogCandidate(source.id + "|item|digest", "track", source, "item",
                MatchMethod.EXACT_SPOTIFY_ID, 0.8, 0L, TimingLevel.LINE, true, true, false, false,
                false, false, false, "digest", "{}", "[]", new byte[0], 1, 1,
                System.currentTimeMillis());
    }

    private static CatalogState emptyState() {
        return new CatalogState("track", Collections.<CatalogCandidate>emptyList(),
                new EnumMap<SourceId, ProviderRecord>(SourceId.class),
                CatalogSelection.auto("track"), null, true);
    }

    @Test public void everySelectablePreferenceSourceHasACatalogIdentity() {
        List<SourceId> mapped = new ArrayList<>();
        for (Source source : LyricsSourcePreferences.selectableSources()) {
            SourceId identity = CatalogPolicy.sourceId(source);
            assertNotNull("no catalogue identity for " + source, identity);
            mapped.add(identity);
        }
        // SpicyLyrics.org is the live preference; SPICY is the retired desktop-token alias that
        // resolves to it. Neither may be the one that returns null.
        assertEquals(SourceId.SPICY_ORG, CatalogPolicy.sourceId(Source.SPICY_ORG));
        assertEquals(SourceId.SPICY_ORG, CatalogPolicy.sourceId(Source.SPICY));
        assertTrue(mapped.contains(SourceId.SPICY_ORG));
        assertEquals(mapped.size(), new java.util.HashSet<>(mapped).size());
    }

    @Test public void everyPickerSourceRoundTripsThroughALivePreferenceSource() {
        for (SourceId source : CatalogPickerModel.SOURCE_ORDER) {
            Source preference = CatalogPolicy.preferenceSource(source);
            assertNotNull("no preference source for " + source, preference);
            // The repository turns this back into a request label. The retired SPICY alias is the
            // Apple Music route, so mapping SpicyLyrics.org to it asked Apple for its slot.
            assertFalse(source + " maps to the retired alias " + preference,
                    LyricsSourcePreferences.isRetired(preference));
            assertEquals(source, CatalogPolicy.sourceId(preference));
        }
    }

    @Test public void spicyOrgIsInTheAutomaticOrderAndEligibleForAuto() {
        CatalogPolicy policy = new CatalogPolicy(OWNER_ORDER, false);

        assertTrue(policy.enabled(SourceId.SPICY_ORG));
        assertEquals(OWNER_ORDER, policy.automaticOrder());
        assertEquals(SourceId.SPICY_ORG, policy.automaticOrder().get(0));
        assertTrue(policy.eligibleForAuto(candidate(SourceId.SPICY_ORG)));
        assertTrue(policy.eligibleForDisplay(candidate(SourceId.SPICY_ORG)));

        // The planner proposes it in the first round of an unseen track, beside the sources the
        // owner listed after it.
        Plan plan = AcquisitionPlanner.racePlan(emptyState(), policy, null,
                System.currentTimeMillis(), true, Collections.<SourceId>emptySet());
        assertTrue(plan.fetches());
        assertEquals(OWNER_ORDER, plan.scope.sources);
    }

    @Test public void aDisabledPreferenceKeepsSpicyOrgOutOfThePlanAndTheSeat() {
        List<SourceId> withoutOrg = Arrays.asList(SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
                SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE);
        CatalogPolicy policy = new CatalogPolicy(withoutOrg, false);

        assertFalse(policy.enabled(SourceId.SPICY_ORG));
        assertFalse(policy.eligibleForAuto(candidate(SourceId.SPICY_ORG)));
        assertFalse(policy.automaticOrder().contains(SourceId.SPICY_ORG));
        Plan plan = AcquisitionPlanner.racePlan(emptyState(), policy, null,
                System.currentTimeMillis(), true, Collections.<SourceId>emptySet());
        assertFalse(plan.scope.sources.contains(SourceId.SPICY_ORG));
    }

    @Test public void recordedAccessLossHidesStoredResultsWithoutUnlistingTheSource() {
        // spicyOrgTerminated: what the display rule protects, and the only thing it protects. The
        // source stays listed and stays in the plan, so the request goes out and the response
        // decides; its cached lyrics do not render until the server confirms access again.
        CatalogPolicy terminated = new CatalogPolicy(OWNER_ORDER, false, false, false, 7L, true, 3L);

        assertFalse(terminated.eligibleForDisplay(candidate(SourceId.SPICY_ORG)));
        assertFalse(terminated.eligibleForAuto(candidate(SourceId.SPICY_ORG)));
        assertTrue(terminated.enabled(SourceId.SPICY_ORG));
        assertTrue(terminated.automaticOrder().contains(SourceId.SPICY_ORG));
        Plan plan = AcquisitionPlanner.racePlan(emptyState(), terminated, null,
                System.currentTimeMillis(), true, Collections.<SourceId>emptySet());
        assertTrue(plan.scope.sources.contains(SourceId.SPICY_ORG));

        // The other five are unaffected by the flag in either direction. QQ and NetEase are left
        // out because their own provider-timing rule needs a real encoded document, which is a
        // different policy from this one.
        for (SourceId source : Arrays.asList(SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
                SourceId.LRCLIB)) {
            assertTrue(terminated.eligibleForDisplay(candidate(source)));
            assertTrue(terminated.eligibleForAuto(candidate(source)));
        }
    }

    /** A stored outcome that is not due is the planner's business, not the policy's. */
    @Test public void answeredSourcesAreStillListedEvenWhenNotDue() {
        Map<SourceId, ProviderRecord> records = new EnumMap<>(SourceId.class);
        records.put(SourceId.SPICY_ORG, new ProviderRecord(SourceId.SPICY_ORG,
                ProviderStatus.AVAILABLE, System.currentTimeMillis(),
                System.currentTimeMillis(), 0, 0));
        CatalogState state = new CatalogState("track", Collections.<CatalogCandidate>emptyList(),
                records, CatalogSelection.auto("track"), null, true);

        CatalogPolicy policy = new CatalogPolicy(OWNER_ORDER, false);
        Plan plan = AcquisitionPlanner.racePlan(state, policy, null,
                System.currentTimeMillis(), true, Collections.<SourceId>emptySet());
        // Already answered and not an upgrade probe: not asked this round, but still enabled and
        // still eligible, which is the difference between "out of rounds" and "out of the running".
        assertTrue(plan.fetches());
        assertFalse(plan.scope.sources.contains(SourceId.SPICY_ORG));
        assertTrue(policy.enabled(SourceId.SPICY_ORG));
        assertTrue(policy.eligibleForAuto(candidate(SourceId.SPICY_ORG)));
    }
}
