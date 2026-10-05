package com.spotifyplusplus.lyrics.providers;

import org.junit.Test;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricQualityRankerTest {
    @Test
    public void officialSpicySyllableBeatsNativeLine() {
        assertTrue(score(spicy("Syllable", true, false)) > score(nativeDoc("Line")));
    }

    @Test
    public void officialSpicyLineBeatsNativeLineAtTheSameSyncLevel() {
        // Timing is equal here, so the provider decides - and the richer payload wins. This used to
        // be the other way round while the provider was the primary key; it is a tiebreak now.
        assertTrue(score(spicy("Line", true, false)) > score(nativeDoc("Line")));
    }

    @Test
    public void spicyFetchSourceWinsOverMusixmatchProviderLabel() {
        assertTrue(score(spicyProvider("Line", true, false, "Musixmatch")) > score(nativeDoc("Line")));
    }

    @Test
    public void nativeSyncedBeatsSpicyStatic() {
        assertTrue(score(nativeDoc("Line")) > score(spicy("Static", true, false)));
    }

    @Test
    public void plainSpicyLineLosesToSpicyLineButBeatsNothingTimedLess() {
        // A non-packed payload is the weaker of the two Spicy shapes, but it is still a real
        // document: what matters is that it does not outrank the packed one.
        assertTrue(score(spicy("Line", true, false)) > score(spicy("Line", false, false)));
        assertTrue(score(spicy("Line", false, false)) > score(spicy("Static", false, false)));
    }

    @Test
    public void timingOutranksProviderEvenAgainstSpotify() {
        // The owner's stated order: word timing beats line timing whoever supplies them, and line
        // timing beats no timing at all. The provider may not pull a lower sync level above a
        // higher one - that was the bug where Spotify's line-synced text always won.
        assertTrue(score(amll("Word")) > score(nativeDoc("Line")));
        assertTrue(score(netease("Line")) > score(nativeDoc("Static")));
        assertTrue(score(qq("Word")) > score(nativeDoc("Line")));
    }

    @Test
    public void simplifiedLyricsBeatTraditionalAtTheSameSyncLevelAndProvider() {
        LyricsDocument simplified = doc("Line", "netease", "NetEase");
        simplified.lines.clear();
        simplified.lines.addAll(lines("从奇迹中诞生 在这片大地"));
        LyricsDocument traditional = doc("Line", "netease", "NetEase");
        traditional.lines.clear();
        traditional.lines.addAll(lines("從奇蹟中誕生 在這片大地"));
        assertTrue(score(simplified) > score(traditional));
    }

    @Test
    public void preferNativeSyncedOverSuspiciousSpicyStatic() {
        assertTrue(score(nativeDoc("Line")) > score(spicy("Static", false, true)));
    }

    @Test
    public void nativeStaticBeatsPoisonedSpicyStatic() {
        assertTrue(score(nativeDoc("Static")) > score(spicy("Static", true, true)));
    }

    @Test
    public void officialSpicyStaticBeatsLrclibStatic() {
        assertTrue(score(spicy("Static", true, false)) > score(lrclib("Static")));
    }

    @Test
    public void officialSpicyStaticBeatsNativeStaticAtTheSameSyncLevel() {
        // Same timing band, richer provider: Spicy wins the tiebreak.
        assertTrue(score(spicy("Static", true, false)) > score(nativeDoc("Static")));
    }

    @Test
    public void lrclibSyncedBeatsSpicyStatic() {
        assertTrue(score(lrclib("Line")) > score(spicy("Static", true, false)));
    }

    @Test
    public void nativeSyncedBeatsLrclibSynced() {
        assertTrue(score(nativeDoc("Line")) > score(lrclib("Line")));
    }

    @Test
    public void plainSpicyStaticStillBeatsNativeStaticAtTheSameSyncLevel() {
        // Provider is only the tiebreak now, and Spicy outranks Spotify's native text at equal
        // timing. What must NOT change is that any timed document outranks an untimed one.
        assertTrue(score(spicy("Static", false, false)) > score(nativeDoc("Static")));
    }

    @Test
    public void autoRankingOrdersSyllableAboveWordAboveLineAboveStatic() {
        assertTrue(LyricQualityRanker.syncLevel("Syllable") > LyricQualityRanker.syncLevel("Word"));
        assertTrue(LyricQualityRanker.syncLevel("Word") > LyricQualityRanker.syncLevel("Line"));
        assertTrue(LyricQualityRanker.syncLevel("Line") > LyricQualityRanker.syncLevel("Static"));
    }

    @Test
    public void autoPrefersWordOverLineAcrossSources() {
        assertTrue(LyricQualityRanker.preferAuto(lrclib("Word"), nativeDoc("Line")));
    }

    @Test
    public void autoPrefersSyllableOverWordAcrossSources() {
        assertTrue(LyricQualityRanker.preferAuto(spicy("Syllable", true, false), lrclib("Word")));
    }

    @Test
    public void autoBreaksSyncTieBySourceTier() {
        assertTrue(LyricQualityRanker.preferAuto(nativeDoc("Line"), lrclib("Line")));
        assertTrue(LyricQualityRanker.preferAuto(appleDoc("Line"), nativeDoc("Line")));
        assertTrue(LyricQualityRanker.preferAuto(appleDoc("Static"), lrclib("Line")) == false);
        assertFalse(LyricQualityRanker.preferAuto(lrclib("Line"), nativeDoc("Line")));
        assertFalse(LyricQualityRanker.preferAuto(nativeDoc("Line"), nativeDoc("Line")));
    }

    @Test
    public void autoNeverPrefersAPoisonedCandidate() {
        assertFalse(LyricQualityRanker.preferAuto(spicy("Syllable", true, true), lrclib("Static")));
    }

    @Test
    public void amllWordRanksAsAppleTier() {
        assertTrue(score(amll("Word")) > score(appleDoc("Line")));
        assertTrue(score(amll("Word")) > score(appleDoc("Static")));
        assertTrue(score(amll("Word")) > score(lrclib("Static")));
        assertTrue(score(appleDoc("Syllable")) > score(amll("Word")));
        assertTrue(LyricQualityRanker.preferAuto(amll("Word"), appleDoc("Line")));
        assertFalse(LyricQualityRanker.preferAuto(appleDoc("Line"), amll("Word")));
    }

    private static LyricsDocument appleDoc(String type) {
        return doc(type, "apple_music_lenerd", "Apple Music");
    }

    private static int score(LyricsDocument doc) {
        return LyricQualityRanker.score(doc);
    }

    private static LyricsDocument spicy(String type, boolean packed, boolean poisoned) {
        return spicyProvider(type, packed, poisoned, "Spicy Lyrics");
    }

    private static LyricsDocument spicyProvider(String type, boolean packed, boolean poisoned, String provider) {
        LyricsDocument doc = doc(type, "spicy_api", provider);
        doc.spicyPackedPayload = packed;
        doc.spicyPoisoned = poisoned;
        return doc;
    }

    private static LyricsDocument nativeDoc(String type) {
        return doc(type, "spotify_native_model", "Musixmatch");
    }

    private static LyricsDocument lrclib(String type) {
        return doc(type, "lrclib", "LRCLIB");
    }

    private static LyricsDocument amll(String type) {
        return doc(type, "amll_ttml", "AMLL");
    }

    private static LyricsDocument netease(String type) {
        return doc(type, "netease", "NetEase");
    }

    private static LyricsDocument qq(String type) {
        return doc(type, "qq_music", "QQ Music");
    }

    /** Replaces a fixture's body with the given lines, for the script-preference cases. */
    private static List<LyricsLine> lines(String... texts) {
        List<LyricsLine> out = new ArrayList<>();
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            out.add(line);
        }
        return out;
    }

    private static LyricsDocument doc(String type, String fetchSource, String provider) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = type;
        doc.fetchSource = fetchSource;
        doc.provider = provider;
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        doc.lines.add(line);
        return doc;
    }
}
