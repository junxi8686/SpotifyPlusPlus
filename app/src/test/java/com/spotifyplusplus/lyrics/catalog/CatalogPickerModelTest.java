package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.catalog.CatalogPickerModel.Row;
import com.spotifyplusplus.lyrics.catalog.CatalogPickerModel.RowKind;
import com.spotifyplusplus.lyrics.catalog.CatalogRanker.Resolution;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.ProviderStatus;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SelectionMode;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CatalogPickerModelTest {
    private static int nextId;

    private static CatalogCandidate cand(SourceId source, TimingLevel timing) {
        String id = source.id + "|item|d" + (nextId++);
        return new CatalogCandidate(id, "track", source, "item", MatchMethod.STRONG_SEARCH, 0.9,
                0L, timing, true, true, true, false, false, false, true, "d", "{}", "[]",
                new byte[0], 1, 1, 0L);
    }

    private static Resolution auto(CatalogCandidate winner) {
        return CatalogResolver.resolve(Collections.singletonList(winner), null, null);
    }

    private static Row sourceRow(List<Row> rows, SourceId source) {
        for (Row row : rows) {
            if (row.kind == RowKind.SOURCE && source.equals(row.sourceId)) return row;
        }
        return null;
    }

    @Test
    public void autoRowShowsWinnerWithoutDuplicatingIt() {
        CatalogCandidate lrclibFixture = cand(SourceId.LRCLIB, TimingLevel.SYLLABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclibFixture),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, auto(lrclibFixture));

        assertEquals(RowKind.AUTO, rows.get(0).kind);
        assertEquals("Auto", rows.get(0).title);
        // No subtitle: the automatic visit only asks the sources that are due, so the row must not
        // claim it compared against every source.
        assertEquals("", rows.get(0).subtitle);
        assertTrue(rows.get(0).selected);
        Row winner = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(winner != null && winner.selected);
    }

    @Test
    public void storedSourceRowShowsMatchStatusAndCapabilities() {
        CatalogCandidate lrclibFixture = cand(SourceId.LRCLIB, TimingLevel.LINE);
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.LRCLIB, ProviderStatus.AVAILABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclibFixture), states,
                null, auto(lrclibFixture));

        Row row = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(row != null);
        assertEquals("LRCLIB · Line", row.title);
        assertEquals("Available · Translation", row.subtitle);
        assertTrue(row.selected);
        assertTrue(row.stored);
        assertEquals(CatalogPickerModel.DataMark.HAVE, row.mark);
        assertTrue(!row.checkable);
    }

    @Test
    public void uncheckedEnabledSourceOffersATapToCheck() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        Row qq = sourceRow(rows, SourceId.QQ);
        Row netease = sourceRow(rows, SourceId.NETEASE);
        assertTrue(qq != null && netease != null);
        assertEquals("QQ Music", qq.title);
        assertEquals("Tap to check", qq.subtitle);
        assertTrue(!qq.stored);
        assertTrue(qq.checkable);
    }

    @Test
    public void disabledSourceStillOffersTrackScopedCheck() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>singletonMap(SourceId.QQ,
                        ProviderStatus.DISABLED),
                null, null);

        Row qq = sourceRow(rows, SourceId.QQ);
        assertTrue(qq != null);
        assertEquals("Tap to check", qq.subtitle);
        assertTrue(!qq.stored);
        assertTrue(qq.checkable);
    }

    @Test
    public void failedSourceSurfacesItsStatus() {
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.LRCLIB, ProviderStatus.TRANSIENT_ERROR);
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                states, null, null);

        Row lrclib = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(lrclib != null);
        assertEquals("Failed · tap to retry", lrclib.subtitle);
        assertTrue(lrclib.checkable);
        assertEquals(CatalogPickerModel.DataMark.EMPTY, lrclib.mark);
    }

    @Test
    public void aStoredDocumentOutranksAFailedRefreshAttempt() {
        // The row used to render the persisted per-source attempt record, so a single failed
        // background refresh repainted a source whose document was on screen as "Failed" - which
        // is what the owner saw: lyrics playing from SpicyLyrics.org while its row said it had
        // failed. The row reports the document it holds now; a failure survives only where it is
        // the truth, which is having nothing stored or holding something the renderer refuses.
        CatalogCandidate lrclib = cand(SourceId.LRCLIB, TimingLevel.LINE);
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.LRCLIB, ProviderStatus.TRANSIENT_ERROR);

        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclib), states, null,
                null);

        Row row = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(row != null);
        assertTrue("a stored document must not read as failed, got: " + row.subtitle,
                row.subtitle.startsWith("Available"));
        assertTrue(row.checkable == false);
        assertEquals(CatalogPickerModel.DataMark.HAVE, row.mark);
    }

    @Test
    public void aStoredDocumentWithNoAttemptRecordReadsAsAvailable() {
        // The other half of the same rule: no record at all is not a failure either.
        CatalogCandidate lrclib = cand(SourceId.LRCLIB, TimingLevel.LINE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclib),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        Row row = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(row != null);
        assertTrue("a stored document must read as available, got: " + row.subtitle,
                row.subtitle.startsWith("Available"));
    }

    @Test
    public void manualSelectionMarksThePinnedSourceRow() {
        CatalogCandidate lrclib = cand(SourceId.LRCLIB, TimingLevel.LINE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                lrclib.candidateId, SourceId.LRCLIB, "item", "");
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclib),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, auto(lrclib));

        assertTrue(!rows.get(0).selected);
        assertEquals("", rows.get(0).subtitle);
        Row pinned = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(pinned != null && pinned.selected);
    }

    @Test
    public void manualSelectionKeepsPinnedProviderVariantVisible() {
        CatalogCandidate pinnedLine = cand(SourceId.LRCLIB, TimingLevel.LINE);
        CatalogCandidate newerSyllable = cand(SourceId.LRCLIB, TimingLevel.SYLLABLE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                pinnedLine.candidateId, SourceId.LRCLIB, "item", pinnedLine.canonicalDigest);

        List<Row> rows = CatalogPickerModel.build(Arrays.asList(newerSyllable, pinnedLine),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual,
                auto(newerSyllable));

        Row lrclibRow = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(lrclibRow.selected);
        assertEquals(pinnedLine.candidateId, lrclibRow.candidateId);
        assertEquals("LRCLIB · Line", lrclibRow.title);
    }

    @Test
    public void theAutoRowNeverCarriesSubtitleText() {
        CatalogCandidate lrclibFixture = cand(SourceId.LRCLIB, TimingLevel.LINE);
        CatalogCandidate syllable = cand(SourceId.AMLL, TimingLevel.SYLLABLE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                lrclibFixture.candidateId, SourceId.LRCLIB, "item", "");

        // Every reachable combination: a winner, a manual pin over a winner, and no winner at all.
        List<Row> withWinner = CatalogPickerModel.build(Collections.singletonList(lrclibFixture),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, auto(lrclibFixture));
        List<Row> withManual = CatalogPickerModel.build(Arrays.asList(lrclibFixture, syllable),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, auto(syllable));
        List<Row> withNothing = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);
        List<Row> manualWithNothing = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, null);

        for (List<Row> rows : Arrays.asList(withWinner, withManual, withNothing, manualWithNothing)) {
            assertEquals(RowKind.AUTO, rows.get(0).kind);
            assertTrue(rows.get(0).title.startsWith("Auto"));
            assertEquals("", rows.get(0).subtitle);
        }
    }

    @Test
    public void emptyCatalogStillOffersActions() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        // Seven sources: SpicyLyrics.org, Apple Music, Spotify, AMLL, LRCLIB, QQ and NetEase,
        // matching CatalogPolicy.automaticOrder() and the DEFAULT_ORDER in
        // LyricsSourcePreferences. Apple Music is a selectable source again (owner decision,
        // 2026-10-29), restored to upstream v2.1.4's table.
        assertEquals(1 + 7 + 2, rows.size());
        assertEquals("Auto · nothing stored yet", rows.get(0).title);
        // The Auto row carries its state in the title and the selected-green colour only. No
        // subtitle in any state, so no state text can be parked in this menu again.
        assertEquals("", rows.get(0).subtitle);
        assertEquals(SourceId.SPICY_ORG, rows.get(1).sourceId);
        assertEquals(SourceId.APPLE, rows.get(2).sourceId);
        assertEquals("Apple Music", rows.get(2).title);
        // The "check all sources in order" row is gone. Its command walked every enabled source in
        // sequence, and one that never called back left the row on "checking" with nothing on
        // screen able to clear it. The per-source rows do the same job one at a time.
        // The escape hatch for a release catalogued under a name no respelling reaches.
        // Indices follow the seven source rows plus the Auto row.
        assertEquals(RowKind.ACTION_MANUAL_SEARCH, rows.get(8).kind);
        assertEquals("Search by name…", rows.get(8).title);
        assertEquals(RowKind.ACTION_DELETE_TRACK, rows.get(9).kind);
        assertEquals("Clear saved lyrics", rows.get(9).title);
    }

    @Test
    public void spotifyUncheckedOffersExplicitCheck() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        Row spotify = sourceRow(rows, SourceId.SPOTIFY_NATIVE);
        assertTrue(spotify != null);
        assertEquals("Spotify", spotify.title);
        assertEquals("Tap to check", spotify.subtitle);
        assertTrue(!spotify.stored);
        assertTrue(spotify.checkable);
        assertEquals(CatalogPickerModel.DataMark.NONE, spotify.mark);
    }

    @Test
    public void spotifyFailureAndEnabledCheckOrderAreVisible() {
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.SPOTIFY_NATIVE, ProviderStatus.TRANSIENT_ERROR);
        CatalogPolicy policy = new CatalogPolicy(java.util.Arrays.asList(
                SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB), false);
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                states, null, null, policy);

        Row spotify = sourceRow(rows, SourceId.SPOTIFY_NATIVE);
        assertEquals("Failed · tap to retry", spotify.subtitle);
        assertEquals(CatalogPickerModel.DataMark.EMPTY, spotify.mark);
        // No row summarises the enabled order any more: the only row that did was the check-all
        // action, and it is gone. The per-source rows carry the same information themselves.
        for (Row row : rows) {
            assertTrue(!row.subtitle.startsWith("Check all"));
        }
    }

    @Test
    public void storedSourceAlwaysCarriesHaveMark() {
        CatalogCandidate lrclibFixture = cand(SourceId.LRCLIB, TimingLevel.LINE);
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.LRCLIB, ProviderStatus.AVAILABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(lrclibFixture), states,
                null, auto(lrclibFixture));

        Row row = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(row != null && row.stored);
        assertEquals("Available · Translation", row.subtitle);
    }

    @Test
    public void brandsStayUntranslated() {
        // Provider brands stay as authored: a catalogue row has to name its source exactly, and
        // the picker's own rows use the same labels.
        assertEquals("Apple Music", CatalogPickerModel.displaySource(SourceId.APPLE));
        assertEquals("SpicyLyrics.org", CatalogPickerModel.displaySource(SourceId.SPICY_ORG));
        assertEquals("Spotify", CatalogPickerModel.displaySource(SourceId.SPOTIFY_NATIVE));
        assertEquals("LRCLIB", CatalogPickerModel.displaySource(SourceId.LRCLIB));
        assertEquals("QQ Music", CatalogPickerModel.displaySource(SourceId.QQ));
    }

    @Test
    public void appleMusicHasAPickerRow() {
        // Apple Music is a normal selectable source again (owner decision, 2026-10-29), so it has
        // its own row between the Spicy slot and Spotify, and the automatic check walk asks it.
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);
        Row apple = sourceRow(rows, SourceId.APPLE);
        assertTrue(apple != null);
        assertEquals("Apple Music", apple.title);
        assertTrue(apple.checkable);
        assertEquals(2, rows.indexOf(apple));
        assertTrue(sourceRow(rows, SourceId.SPICY_ORG) != null);
    }

    @Test
    public void footerTimingFollowsDocumentType() {
        assertEquals("Syllable", CatalogPickerModel.displayTypeTiming("Syllable"));
        assertEquals("Word", CatalogPickerModel.displayTypeTiming("Word"));
        assertEquals("Line", CatalogPickerModel.displayTypeTiming("Line"));
        assertEquals("Unsynced", CatalogPickerModel.displayTypeTiming("Static"));
        assertEquals("Unsynced", CatalogPickerModel.displayTypeTiming(null));
    }
}
