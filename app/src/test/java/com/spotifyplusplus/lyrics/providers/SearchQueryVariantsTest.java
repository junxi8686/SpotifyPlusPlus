package com.spotifyplusplus.lyrics.providers;

import com.spotifyplusplus.SpotifyTrack;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchQueryVariantsTest {
    private static SpotifyTrack track(String title, String artist) {
        return new SpotifyTrack(title, artist, "", "spotify:track:1", 0L, "", 0L, "", 0L, false);
    }

    @Test
    public void reportedSpellingIsAlwaysTheFirstQuery() {
        List<String> queries = SearchQueryVariants.queries(track("夜に駆ける", "YOASOBI"));
        assertEquals("夜に駆ける YOASOBI", queries.get(0));
    }

    @Test
    public void aLatinTrackWithNothingToWidenAsksOnlyTheReportedSpellingAndTheBareTitle() {
        // No CJK, no marker and no bracket, so nothing is respelled. The bare title stays as the
        // last resort for a compilation credit no provider indexes; it is only ever reached when
        // the first query already missed.
        List<String> queries = SearchQueryVariants.queries(track("Shape of You", "Ed Sheeran"));
        assertEquals(2, queries.size());
        assertEquals("Shape of You Ed Sheeran", queries.get(0));
        assertEquals("Shape of You", queries.get(1));
    }

    @Test
    public void traditionalTitleAlsoAsksInSimplified() {
        List<String> queries = SearchQueryVariants.queries(track("離開地球表面", "五月天"));
        assertTrue(queries.toString(), queries.contains("离开地球表面 五月天"));
        // Simplified leads, because the search stops at the first hit: asked in the reported order,
        // a Traditional report seats a Traditional edition even when a Simplified one exists.
        assertEquals("离开地球表面 五月天", queries.get(0));
    }

    @Test
    public void simplifiedTitleAlsoAsksInTraditional() {
        List<String> queries = SearchQueryVariants.queries(track("离开地球表面", "五月天"));
        assertTrue(queries.toString(), queries.contains("離開地球表面 五月天"));
    }

    @Test
    public void aUnifiedTitleIsStillAskedInBothScriptsWithItsArtist() {
        List<String> queries = SearchQueryVariants.queries(track("后来", "刘若英"));
        assertEquals("后来 刘若英", queries.get(0));
        // Both title and artist carry the difference: 来/來 and 刘/劉.
        assertTrue(queries.toString(), queries.contains("後來 劉若英"));
    }

    @Test
    public void versionMarkerAndTrailingBracketAreAskedSeparately() {
        List<String> queries = SearchQueryVariants.queries(
                track("Bohemian Rhapsody (Remastered 2011)", "Queen"));
        assertTrue(queries.toString(), queries.contains("Bohemian Rhapsody (Remastered 2011) Queen"));
        assertTrue(queries.toString(), queries.contains("Bohemian Rhapsody Queen"));
    }

    @Test
    public void aBracketQualifierIsDroppedSoTheIndexedTitleIsAskedToo() {
        // Spotify carries the release's own subtitle; every provider indexes the bare title.
        List<String> queries = SearchQueryVariants.queries(
                track("从奇迹中诞生（2026哔哩哔哩拜年季主题曲）", "哔哩哔哩拜年纪"));
        assertTrue(queries.toString(), queries.contains("从奇迹中诞生 哔哩哔哩拜年纪"));
        assertTrue(queries.toString(),
                queries.contains("从奇迹中诞生（2026哔哩哔哩拜年季主题曲） 哔哩哔哩拜年纪"));
    }

    @Test
    public void aDashQualifierIsDroppedSoTheIndexedTitleIsAskedToo() {
        // The case that actually failed: Spotify reports "反乌托邦 - 拼接版" while LRCLIB, AMLL
        // and NetEase all index the bare "反乌托邦". No fixed marker list contains "拼接版", so
        // the strip has to be unconditional.
        List<String> queries = SearchQueryVariants.queries(track("反乌托邦 - 拼接版", "乌托邦P"));
        assertTrue(queries.toString(), queries.contains("反乌托邦 乌托邦P"));
        assertEquals("反乌托邦 - 拼接版 乌托邦P", queries.get(0));
    }

    @Test
    public void aTitleWithNoSpacedDashIsLeftWhole() {
        // A hyphen inside a word is part of the name, not a qualifier separator.
        assertEquals("Love-Part 1", SearchQueryVariants.withoutTrailingDash("Love-Part 1"));
        assertEquals("", SearchQueryVariants.withoutTrailingDash(""));
        assertEquals("-", SearchQueryVariants.withoutTrailingDash("-"));
    }

    @Test
    public void strippingAQualifierNeverEmptiesTheTitle() {
        assertEquals("Song", SearchQueryVariants.withoutTrailingBracket("Song (Live)"));
        assertEquals("Song (Live)", SearchQueryVariants.withoutTrailingBracket("Song (Live) (Remastered)"));
        // The whole title is the qualifier: there is nothing left, so the original is kept.
        assertEquals("(Live)", SearchQueryVariants.withoutTrailingBracket("(Live)"));
    }

    @Test
    public void artistOnlyIsUsedWhenTheTitleIsBlank() {
        List<String> queries = SearchQueryVariants.queries(track("", "五月天"));
        assertEquals("五月天", queries.get(0));
    }

    @Test
    public void planIsBoundedAndDeduplicated() {
        List<String> queries = SearchQueryVariants.queries(track("離開地球表面 (Live) [Remastered]", "五月天"));
        assertTrue(queries.size() <= SearchQueryVariants.MAX_QUERIES);
        assertEquals(queries.size(), queries.stream().distinct().count());
    }

    @Test
    public void aiAliasesArePreferredOnARetry() {
        // An alias set can only exist from the second visit onwards, and a second visit means the
        // local spellings already missed. So the reported spelling stays first and the aliases
        // come next - re-asking the spellings that just failed would be asking twice.
        List<String> queries = SearchQueryVariants.queries(track("離開地球表面", "五月天"),
                java.util.Collections.singletonList("Leaving the Earth's Surface"));
        assertEquals("離開地球表面 五月天", queries.get(0));
        assertEquals("Leaving the Earth's Surface", queries.get(1));
        // The Simplified spelling leads a first attempt and is deliberately absent here: this plan
        // only exists because that attempt already missed, so re-asking it would spend a request on
        // a question that has been answered.
        assertFalse(queries.toString(), queries.contains("离开地球表面 五月天"));
    }

    @Test
    public void everyAliasTheModelReturnedIsAsked() {
        List<String> aliases = java.util.Arrays.asList(
                "Leaving the Earth's Surface", "Mayday Leaving", "离开地球表面", "离开地球表面 (Live)");
        List<String> queries = SearchQueryVariants.queries(track("離開地球表面", "五月天"), aliases);
        for (String alias : aliases) {
            assertTrue(queries.toString(), queries.contains(alias));
        }
    }

    @Test
    public void thePlanStaysBoundedAndDeduplicatedWithAFullAliasSet() {
        List<String> aliases = java.util.Arrays.asList(
                "离开地球表面", "離開地球表面", "Leaving the Earth's Surface", "Live version");
        List<String> queries = SearchQueryVariants.queries(
                track("離開地球表面 (Live) [Remastered]", "五月天"), aliases);
        assertTrue(queries.toString(), queries.size() <= SearchQueryVariants.MAX_QUERIES);
        assertEquals(queries.size(), queries.stream().distinct().count());
    }

    @Test
    public void titleQueriesOnlyCoverTitles() {
        List<String> queries = SearchQueryVariants.titleQueries("離開地球表面 (Live)");
        assertEquals("離開地球表面 (Live)", queries.get(0));
        assertTrue(queries.toString(), queries.contains("离开地球表面 (Live)"));
        assertTrue(queries.toString(), queries.contains("離開地球表面"));
        assertTrue(queries.toString(), queries.contains("离开地球表面"));
        assertTrue(queries.size() <= SearchQueryVariants.MAX_QUERIES);
    }

    @Test
    public void blankInputProducesAnEmptyPlan() {
        assertTrue(SearchQueryVariants.queries(null).isEmpty());
        assertTrue(SearchQueryVariants.queries(track("", "")).isEmpty());
    }

    @Test
    public void scriptConversionRoundTripsTheCommonCases() {
        assertEquals("离开地球表面", ChineseScriptVariants.toSimplified("離開地球表面"));
        assertEquals("離開地球表面", ChineseScriptVariants.toTraditional("离开地球表面"));
        assertTrue(ChineseScriptVariants.hasTraditional("離開地球表面"));
        assertTrue(ChineseScriptVariants.hasSimplified("离开地球表面"));
        assertFalse(ChineseScriptVariants.hasTraditional("离开地球表面"));
        // One character pair each way, both inside an artist name.
        assertEquals("後來 劉若英", ChineseScriptVariants.toTraditional("后来 刘若英"));
        assertEquals("后来 刘若英", ChineseScriptVariants.toSimplified("後來 劉若英"));
        // Written identically in both scripts: conversion must not invent a difference.
        assertEquals("中文", ChineseScriptVariants.toSimplified("中文"));
        assertEquals("中文", ChineseScriptVariants.toTraditional("中文"));
        // The paired tables must stay index-aligned; this is the invariant that makes the
        // positional lookup safe at all.
        assertTrue(ChineseScriptVariants.available());
    }

    @Test
    public void latinTextIsLeftAlone() {
        assertEquals("Shape of You", ChineseScriptVariants.toSimplified("Shape of You"));
        assertEquals("Shape of You", ChineseScriptVariants.toTraditional("Shape of You"));
        assertFalse(ChineseScriptVariants.hasTraditional("Shape of You"));
    }
}
