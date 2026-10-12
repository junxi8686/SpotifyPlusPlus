package com.spotifyplusplus.lyrics.providers;

import org.junit.Test;

import com.spotifyplusplus.lyrics.LyricsDocument;

import static org.junit.Assert.assertEquals;

/**
 * The string handed to {@link LyricsFetchDiagnosticsState} is what the settings diagnostics card
 * prints as "Source chosen", so a withdrawn route must never be named for a live one.
 */
public class LyricsRepositorySourceLabelTest {
    @Test
    public void liveSpicyOrgResponseIsNotLabelledAsTheRetiredAppleRoute() {
        assertEquals("spicy_org", LyricsRepository.sourceLabel(document("spicy_org"), "apple_music"));
    }

    @Test
    public void retiredSpicyFetchSourceStillReadsAsItsAlias() {
        assertEquals("apple_music", LyricsRepository.sourceLabel(document("spicy_api"), "native"));
    }

    @Test
    public void existingLabelsAreUnchanged() {
        assertEquals("cache", LyricsRepository.sourceLabel(document("apple_music_cache"), "native"));
        assertEquals("apple_music", LyricsRepository.sourceLabel(document("apple_music_lenerd"), "native"));
        assertEquals("native", LyricsRepository.sourceLabel(document("spotify_native_model"), "apple_music"));
        assertEquals("lrclib", LyricsRepository.sourceLabel(document("lrclib"), "apple_music"));
        assertEquals("amll", LyricsRepository.sourceLabel(document("amll"), "apple_music"));
    }

    @Test
    public void unknownFetchSourceFallsBackToTheCallersDefault() {
        assertEquals("apple_music", LyricsRepository.sourceLabel(document(""), "apple_music"));
        assertEquals("apple_music", LyricsRepository.sourceLabel(null, "apple_music"));
    }

    private static LyricsDocument document(String fetchSource) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = fetchSource;
        return document;
    }
}
