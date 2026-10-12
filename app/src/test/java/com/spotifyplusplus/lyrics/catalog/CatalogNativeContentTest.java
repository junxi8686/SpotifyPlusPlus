package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

/**
 * A stored document that is not lyrics.
 *
 * <p>The capture path refuses a layout probe before it is stored, but that guard only sees captures
 * made after it existed. A row an older build already wrote is still in the database, and the
 * catalogue has to refuse it when it is read: it must not decode, must not render, and must not hold
 * the Auto seat. This is the read-boundary half of the same rule, using the same predicate rather
 * than upstream's name list, so there is one definition of "not lyrics".
 */
public class CatalogNativeContentTest {
    private static final CatalogPolicy POLICY = new CatalogPolicy(
            Collections.singletonList(CatalogSource.SourceId.SPOTIFY_NATIVE), false);
    /** The shape the capture guard refuses: a whitespace-free run of snake_case identifiers. */
    private static final String LAYOUT_LINE = "now_playing_view_container,navigation_bar";
    private static final String LYRICS_LINE = "Take me hand";

    private static CatalogCandidate candidate(CatalogSource.TimingLevel timing, String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "track1";
        doc.language = "en";
        doc.fetchSource = "spotify_native_db";
        doc.provider = "Spotify";
        doc.type = timing == CatalogSource.TimingLevel.WORD ? "Word" : "Line";
        long start = 1000L;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = start;
            line.endMs = start + 900L;
            start += 1000L;
            doc.lines.add(line);
        }
        SpotifyTrack track = new SpotifyTrack("Song", "Artist", "Album",
                "spotify:track:track1", 0, "", 0, null, 180000, false);
        return CatalogAdapters.buildCandidate(CatalogSource.SourceId.SPOTIFY_NATIVE, track, doc,
                CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, "track1", "", 1, 1);
    }

    private static CatalogState state(CatalogCandidate... candidates) {
        List<CatalogCandidate> stored = new ArrayList<>(Arrays.asList(candidates));
        java.util.Map<CatalogSource.SourceId, ProviderRecord> providers =
                new EnumMap<>(CatalogSource.SourceId.class);
        providers.put(CatalogSource.SourceId.SPOTIFY_NATIVE, new ProviderRecord(
                CatalogSource.SourceId.SPOTIFY_NATIVE, CatalogSource.ProviderStatus.AVAILABLE,
                1L, 1L, 1L, 0));
        return new CatalogState("track1", stored, providers, CatalogSelection.auto("track1"),
                Collections.<String>emptySet(), true);
    }

    @Test
    public void cachedLayoutCannotDecodeRenderOrHoldTheAutoSeat() {
        CatalogCandidate layout = candidate(CatalogSource.TimingLevel.WORD, LAYOUT_LINE);
        assertNull("the stored layout document must not decode", LyricsCatalog.decode(layout));

        CatalogState readable = LyricsCatalog.readableState(state(layout));
        assertTrue("and it is not part of the state that renders", readable.candidates.isEmpty());
        assertEquals("while its source is queued for a fresh attempt, so real lyrics can replace it",
                CatalogSource.ProviderStatus.NEEDS_REFRESH,
                readable.provider(CatalogSource.SourceId.SPOTIFY_NATIVE).status);
        assertNull("so it holds no Auto seat",
                CatalogDecisions.render(readable, POLICY).winner);

        // A real document beside it is unaffected: it decodes, and it takes the seat the layout
        // cannot. The layout row stays stored and invisible rather than being deleted.
        CatalogCandidate lyrics = candidate(CatalogSource.TimingLevel.WORD, LYRICS_LINE);
        assertNotNull("ordinary prose still decodes", LyricsCatalog.decode(lyrics));
        CatalogState mixed = state(layout, lyrics);
        CatalogState mixedReadable = LyricsCatalog.readableState(mixed);
        assertEquals(Collections.singletonList(lyrics.candidateId),
                idsOf(mixedReadable.candidates));
        assertEquals(lyrics.candidateId, CatalogDecisions.render(mixedReadable, POLICY).winner.candidateId);
        assertEquals("and both rows are still stored", 2, mixed.candidates.size());
    }

    /** A pin on the refused row renders the temporary stand-in rather than the layout text. */
    @Test
    public void aManualPinOnTheRefusedRowRendersTheStandIn() {
        CatalogCandidate layout = candidate(CatalogSource.TimingLevel.WORD, LAYOUT_LINE);
        CatalogCandidate lyrics = candidate(CatalogSource.TimingLevel.WORD, LYRICS_LINE);
        CatalogState stored = state(layout, lyrics);
        CatalogState pinned = new CatalogState("track1", stored.candidates, stored.providers,
                new CatalogSelection("track1", CatalogSource.SelectionMode.MANUAL,
                        layout.candidateId, layout.sourceId, layout.providerItemId,
                        layout.canonicalDigest),
                Collections.<String>emptySet(), true);

        CatalogRanker.Resolution resolution =
                CatalogDecisions.render(LyricsCatalog.readableState(pinned), POLICY);
        assertEquals("the pin cannot render, so the readable winner stands in for it",
                lyrics.candidateId, resolution.winner.candidateId);
        assertTrue("and it is marked temporary", resolution.temporary);
    }

    private static List<String> idsOf(List<CatalogCandidate> candidates) {
        List<String> ids = new ArrayList<>();
        for (CatalogCandidate candidate : candidates) ids.add(candidate.candidateId);
        return ids;
    }
}
