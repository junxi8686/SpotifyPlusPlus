package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.LyricsLine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The capture hooks are found by shape, and a shape can match the wrong thing. When it did, the
 * panel showed a run of ConstraintLayout barrier ids as the lyrics of the track playing. Everything
 * below is a real string that reached the screen.
 */
public class NativeLyricsPlausibilityTest {

    private static List<LyricsLine> lines(String... texts) {
        List<LyricsLine> out = new ArrayList<>();
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            out.add(line);
        }
        return out;
    }

    @Test
    public void rejectsLayoutIdentifierRunsThatReachedTheScreen() {
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines(
                "fragment_container_bottom_overlap_touch_event_consumer,fragment_container_inset_bottom_spacing")));
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines(
                "status_bar_placeholder,bannerContainer")));
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines(
                "unobstructed_main_content_top_barrier")));
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines(
                "now_playing_attachments_container,now_playing_view_container,navigation_bar,limited_")));
    }

    @Test
    public void oneIdentifierRunDisqualifiesTheWholeDocument() {
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines(
                "Darling won't you break my heart",
                "unobstructed_main_content_top_barrier",
                "Won't you let me go")));
    }

    @Test
    public void acceptsOrdinaryLyrics() {
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "Darling won't you break my heart",
                "Take me hand, in the moonlight")));
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines("Won't you let me go")));
        // A single word is legitimate and carries no underscore, so it passes.
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines("Hello")));
    }

    @Test
    public void acceptsChineseJapaneseAndKoreanLyrics() {
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "我感觉爱重生了", "月光下的萤火虫")));
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "君の名前を呼んでいる", "夜が明けるまで")));
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "아직도 너를 기다려")));
    }

    @Test
    public void acceptsPunctuatedAndDuetLyrics() {
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "Take Me Hand (Acoustic)", "Cécile Corbel")));
        // An underscore inside a line of prose does not make it an identifier run: the spaces do
        // not allow one.
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines(
                "la la_la la la")));
    }

    @Test
    public void rejectsEmptyAndBlankOnlyDocuments() {
        assertFalse(NativeLyricsSource.looksLikeLyrics(null));
        assertFalse(NativeLyricsSource.looksLikeLyrics(new ArrayList<LyricsLine>()));
        assertFalse(NativeLyricsSource.looksLikeLyrics(lines("", "   ")));
    }

    @Test
    public void acceptsDocumentsWithSomeBlankLinesMixedIn() {
        assertTrue(NativeLyricsSource.looksLikeLyrics(lines("", "Darling won't you", "   ", "break my heart")));
    }

    @Test
    public void handlesNullEntriesWithoutThrowing() {
        List<LyricsLine> withNull = new ArrayList<>(Arrays.asList(new LyricsLine[1]));
        LyricsLine real = new LyricsLine();
        real.text = "Won't you let me go";
        withNull.add(real);
        assertTrue(NativeLyricsSource.looksLikeLyrics(withNull));
    }
}
