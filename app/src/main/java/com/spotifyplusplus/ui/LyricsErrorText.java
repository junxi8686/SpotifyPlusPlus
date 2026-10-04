package com.spotifyplusplus.ui;

import java.util.Locale;

/**
 * Chinese rendering of the fetch failures the lyric surface shows under "no lyrics found".
 *
 * <p>Providers report in English on purpose: {@code LyricsFetchErrors} classifies a durable
 * "no lyrics" outcome by matching phrases such as {@code "lrclib empty"}, and
 * {@code ProviderFailureClassifier} does the same for rejections. Translating those strings at the
 * source would silently reclassify every failure, so the English stays and this class translates
 * the copy the user reads instead.
 *
 * <p>Best effort by design: a failure this class does not know renders unchanged rather than as a
 * half-translated sentence, which keeps an unrecognised error debuggable.
 *
 * <p>Android-free: safe for unit tests.
 */
public final class LyricsErrorText {
    /**
     * Longest phrase first. Replacement is substring-based, so a short entry that is a prefix of a
     * longer one ({@code "QQ Music empty"} inside {@code "QQ Music empty response"}) must never run
     * before it.
     */
    private static final String[][] PHRASES = {
            {"Lyrics unavailable for podcasts/episodes", "播客/剧集无法获取歌词"},
            {"Lyrics unavailable for local files", "本地文件无法获取歌词"},
            {"Lyrics unavailable for this media", "该媒体类型无法获取歌词"},
            {"No lyric source in order produced lyrics", "按顺序尝试的所有来源都没有找到歌词"},
            {"No automatic lyric source is due", "当前没有到期的自动歌词来源"},
            {"All lyric sources disabled", "所有歌词来源均已禁用"},
            {"Apple Music lyrics empty", "Apple Music 歌词为空"},
            {"Apple Music request cancelled", "Apple Music 请求已取消"},
            {"AMLL source unavailable: no match", "AMLL 来源不可用：没有匹配"},
            {"NetEase search parse failed: ", "网易云搜索结果解析失败："},
            {"NetEase search HTTP ", "网易云搜索 HTTP "},
            {"NetEase search failed: ", "网易云搜索失败："},
            {"NetEase lyric failed: ", "网易云歌词获取失败："},
            {"QQ Music search failed: ", "QQ 音乐搜索失败："},
            {"QQ Music lyric failed: ", "QQ 音乐歌词获取失败："},
            {"AMLL lyrics empty", "AMLL 歌词为空"},
            {"NetEase search empty", "网易云搜索无结果"},
            {"QQ Music search empty", "QQ 音乐搜索无结果"},
            {"NetEase empty", "网易云无结果"},
            {"QQ Music empty", "QQ 音乐无结果"},
            {"LRCLIB source unavailable: ", "LRCLIB 来源不可用："},
            {"QQ Music source unavailable: ", "QQ 音乐来源不可用："},
            {"NetEase source unavailable: ", "网易云来源不可用："},
            {"Apple Music source unavailable: ", "Apple Music 来源不可用："},
            {"cached no-result", "已缓存的无结果"},
            {"No LRCLIB result", "LRCLIB 无结果"},
            {"LRCLIB lyrics empty", "LRCLIB 歌词为空"},
            {"LRCLIB empty", "LRCLIB 无结果"},
            {"Missing track id", "缺少曲目 ID"},
            {"Unknown lyrics source", "未知的歌词来源"},
            {"Lyrics refresh failed: ", "歌词刷新失败："},
            {"Lyrics unavailable (cached no-result)", "未找到歌词（已缓存的无结果）"},
            {"Lyrics unavailable", "未找到歌词"},
            {" (AI checked, none matched)", "（AI 已判断，都不是）"},
            {" (AI not enabled)", "（AI 未启用，无法自动判断）"},
            {"No match", "没有匹配"},
    };

    private LyricsErrorText() {
    }

    /** Chinese copy for a provider failure; the input when nothing matches or it is blank. */
    public static String localize(String error) {
        if (error == null || error.isEmpty()) return error == null ? "" : error;
        String out = error;
        for (String[] phrase : PHRASES) {
            if (out.contains(phrase[0])) out = out.replace(phrase[0], phrase[1]);
        }
        // Provider labels are quoted verbatim inside "X empty" style messages; Chinese reads
        // better without the stray Latin word once the surrounding sentence is Chinese.
        if (containsCjk(out)) out = out.replace(" empty", " 为空");
        return out;
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /** True when every letter left is part of a name we deliberately keep (LRCLIB, NetEase...). */
    static boolean isFullyLocalized(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String keep : new String[]{"lrclib", "apple music", "amll", "qq", "netease", "http", "api"}) {
            lower = lower.replace(keep, "");
        }
        for (int i = 0; i < lower.length(); i++) {
            if (Character.isLetter(lower.charAt(i)) && lower.charAt(i) < 128) return false;
        }
        return true;
    }
}
