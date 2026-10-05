package com.spotifyplusplus.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.providers.LyricQualityRanker;

import org.junit.Test;

/** Auto ranking accepts syllable > word > line > static, and legacy modes migrate to it. */
public class LyricsSourceRankingTest {
    @Test
    public void legacyRankingModesParseToAuto() {
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("Smart ranking"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("Sync type"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("smart"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("sync"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse(null));
    }

    @Test
    public void sourceOrderModeSurvivesMigration() {
        assertEquals(LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                LyricsSourcePreferences.RankingMode.parse("Source order"));
        assertEquals(LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                LyricsSourcePreferences.RankingMode.parse("order"));
    }

    @Test
    public void searchProvidersAreOptInWhileEstablishedSourcesKeepTheirDefaults() {
        assertFalse(LyricsSourcePreferences.enabledByDefault(null));
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.SPICY));
        // Both on by default now. They were opt-in while network search sources were treated as
        // expensive, which meant a fresh install never resolved from either - and they are the
        // two that carry Chinese catalogue text.
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.QQ));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.NETEASE));
        // Apple Music and the retired desktop remote are withdrawn: neither may report itself
        // enabled, and neither may appear in the selectable list, whatever a stored preference says.
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.APPLE_MUSIC));
        assertTrue(LyricsSourcePreferences.isRetired(
                LyricsSourcePreferences.Source.APPLE_MUSIC));
        assertTrue(LyricsSourcePreferences.isRetired(
                LyricsSourcePreferences.Source.SPICY));
        assertFalse(LyricsSourcePreferences.selectableSources().contains(
                LyricsSourcePreferences.Source.APPLE_MUSIC));
        assertFalse(LyricsSourcePreferences.selectableSources().contains(
                LyricsSourcePreferences.Source.SPICY));
        assertFalse(LyricsSourcePreferences.defaultOrder().contains(
                LyricsSourcePreferences.Source.APPLE_MUSIC));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.SPOTIFY));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.AMLL));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.LRCLIB));
    }

    @Test
    public void candidateSyncLevelMatchesAutoOrder() {
        assertEquals(3, LyricQualityRanker.syncLevel("Syllable"));
        assertEquals(2, LyricQualityRanker.syncLevel("Word"));
        assertEquals(1, LyricQualityRanker.syncLevel("Line"));
        assertEquals(0, LyricQualityRanker.syncLevel("Static"));
    }
}
