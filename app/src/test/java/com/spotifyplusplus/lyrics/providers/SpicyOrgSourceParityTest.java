package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.catalog.CatalogPolicy;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences;
import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The steps that turn the owner's stored source preferences into the automatic source list.
 *
 * <p>This is the seam the defect lived in and no test covered: the settings order named
 * {@code spicy_org} first, the preference was enabled with a client key stored - and the catalogue
 * identity for that preference was missing, so it never reached {@code enabledOrder}. The source
 * was then absent from every automatic round while a picker tap still worked, because the picker
 * walks {@code CatalogSource} ids and never consults this mapping.
 *
 * <p>The one link that cannot be exercised here is the stored-key check itself:
 * {@code SpicyOrgKeyStore.load} decrypts through the Android Keystore, which does not exist off
 * device. Key presence is asserted on the device instead; what is asserted here is that every
 * preference the owner can enable has an identity, in the owner's order.
 */
public class SpicyOrgSourceParityTest {

    private static final List<LyricsSourcePreferences.Source> OWNER_ORDER = Arrays.asList(
            LyricsSourcePreferences.Source.SPICY_ORG, LyricsSourcePreferences.Source.SPOTIFY,
            LyricsSourcePreferences.Source.AMLL, LyricsSourcePreferences.Source.LRCLIB,
            LyricsSourcePreferences.Source.QQ, LyricsSourcePreferences.Source.NETEASE);

    /** The catalogue order that stored preference list must produce, one for one. */
    private static final List<SourceId> MAPPED = Arrays.asList(SourceId.SPICY_ORG,
            SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ,
            SourceId.NETEASE);

    private static FakeAndroidContext ownerPreferences() {
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsSourcePreferences.setSourceOrder(context, OWNER_ORDER);
        for (LyricsSourcePreferences.Source source : LyricsSourcePreferences.selectableSources()) {
            LyricsSourcePreferences.setSourceEnabled(context, source, true);
        }
        return context;
    }

    @Test public void theStoredOwnerOrderMapsToAllSixCatalogSourcesInThatOrder() {
        FakeAndroidContext context = ownerPreferences();
        assertEquals(OWNER_ORDER, LyricsSourcePreferences.sourceOrder(context));

        List<SourceId> mapped = new ArrayList<>();
        for (LyricsSourcePreferences.Source source : LyricsSourcePreferences.sourceOrder(context)) {
            SourceId identity = CatalogPolicy.sourceId(source);
            assertNotNull("no catalogue identity for " + source, identity);
            mapped.add(identity);
        }
        assertEquals(MAPPED, mapped);

        // And the policy built from it elects the source settings put first.
        CatalogPolicy policy = new CatalogPolicy(mapped, false);
        assertTrue(policy.enabled(SourceId.SPICY_ORG));
        assertEquals(MAPPED, policy.automaticOrder());
    }

    @Test public void withoutAKeySpicyOrgIsNotAnEnabledPreference() {
        FakeAndroidContext context = ownerPreferences();
        // Nothing to serve with, so it does not report itself enabled and never reaches the
        // automatic order - the credential rule, not a mapping one.
        assertTrue(!SpicyOrgKeyStore.has(context));
        assertTrue(!LyricsSourcePreferences.sourceEnabled(context,
                LyricsSourcePreferences.Source.SPICY_ORG));
        assertTrue(!LyricsSourcePreferences.enabledSourceOrder(context)
                .contains(LyricsSourcePreferences.Source.SPICY_ORG));

        // The other five are unaffected: the automatic list is the same order without it.
        List<SourceId> mapped = new ArrayList<>();
        for (LyricsSourcePreferences.Source source
                : LyricsSourcePreferences.enabledSourceOrder(context)) {
            mapped.add(CatalogPolicy.sourceId(source));
        }
        assertEquals(MAPPED.subList(1, MAPPED.size()), mapped);
    }
}
