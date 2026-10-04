package com.spotifyplusplus.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricsErrorTextTest {
    @Test
    public void theCommonNoLyricsMessagesReadInChinese() {
        assertEquals("按顺序尝试的所有来源都没有找到歌词",
                LyricsErrorText.localize("No lyric source in order produced lyrics"));
        assertEquals("所有歌词来源均已禁用",
                LyricsErrorText.localize("All lyric sources disabled"));
        assertEquals("该媒体类型无法获取歌词", LyricsErrorText.localize("Lyrics unavailable for this media"));
    }

    @Test
    public void aProviderPrefixIsTranslatedAndItsDetailKept() {
        assertEquals("QQ 音乐搜索失败：timeout",
                LyricsErrorText.localize("QQ Music search failed: timeout"));
        assertEquals("LRCLIB 来源不可用：no result",
                LyricsErrorText.localize("LRCLIB source unavailable: no result"));
    }

    @Test
    public void theLongerPhraseWinsOverItsPrefix() {
        // "QQ Music empty" is a prefix of nothing here, but "LRCLIB empty" sits inside
        // "LRCLIB lyrics empty" and must not pre-empt it.
        assertEquals("网易云搜索无结果", LyricsErrorText.localize("NetEase search empty"));
        assertEquals("网易云无结果", LyricsErrorText.localize("NetEase empty"));
    }

    @Test
    public void anUnknownFailureIsLeftAloneRatherThanHalfTranslated() {
        // Provider failures stay English precisely so a new one is still debuggable; a partial
        // translation would be worse than none.
        assertEquals("Something entirely new", LyricsErrorText.localize("Something entirely new"));
    }

    @Test
    public void blankInputIsSafe() {
        assertEquals("", LyricsErrorText.localize(null));
        assertEquals("", LyricsErrorText.localize(""));
    }

    @Test
    public void aRequestedReloadIsTranslated() {
        String out = LyricsErrorText.localize("Lyrics refresh failed: LRCLIB empty");
        assertTrue(out, out.startsWith("歌词刷新失败："));
        assertTrue(out, out.contains("LRCLIB 无结果"));
    }
}
