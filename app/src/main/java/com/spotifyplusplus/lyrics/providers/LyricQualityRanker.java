package com.spotifyplusplus.lyrics.providers;

import com.spotifyplusplus.lyrics.LyricUtils;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

/** Pure source-quality scorer for lyric candidate arbitration. */
public final class LyricQualityRanker {
    public static final int REJECT = Integer.MIN_VALUE;

    private LyricQualityRanker() {
    }

    public static int score(LyricsDocument doc) {
        if (doc == null) return REJECT;
        int score = score(doc.fetchSource, doc.type, doc.spicyPoisoned, doc.provider, doc.spicyPackedPayload);
        if (score == REJECT) return REJECT;
        // Provider identity is only a weak prior. Penalize malformed timing so a
        // healthy candidate from another source can win automatic arbitration. The script
        // tiebreak has to read the lyrics themselves, not the provider name.
        return score + scriptBonus(doc) + lineSanityBonus(doc) + timingHealthAdjustment(doc);
    }

    public static int score(String fetchSource, String type, boolean poisoned, String provider) {
        return score(fetchSource, type, poisoned, provider, false);
    }

    /**
     * How good this delivery is.
     *
     * <p><b>Timing comes first and the provider does not override it.</b> The owner asked for
     * word-by-word lyrics to beat line-by-line lyrics, and line-by-line to beat nothing timed at
     * all, whoever supplies them. So the sync level now owns the score: each level gets a band
     * 10000 wide, and everything else is a tiebreak inside that band. A line-synced delivery from
     * any source therefore outranks an unsynced one from any other source, and a word-timed
     * delivery outranks a line-timed one - including Spotify's own.
     *
     * <p>Inside a band the order is: source capability, then Simplified over Traditional. The
     * provider used to be the primary key, which meant Spotify's native line-synced lyrics - often
     * Traditional - beat a Simplified word-synced delivery from NetEase or QQ. That is exactly the
     * case the owner reported: Spotify lyrics always won because any timed native delivery scored
     * 5000 while other providers' word timing scored 1150.
     */
    public static int score(String fetchSource, String type, boolean poisoned, String provider, boolean spicyPackedPayload) {
        if (poisoned) return REJECT;

        Source source = sourceOf(fetchSource, provider);
        Sync sync = syncOf(type);

        // The script tiebreak needs the lyrics themselves, so it is added by the document
        // overload; this string form only knows the timing band and the provider.
        return syncBand(sync) + sourceBonus(source, spicyPackedPayload);
    }

    /** Timing band. 10000 apart so no tiebreak can lift a lower level above a higher one. */
    private static int syncBand(Sync sync) {
        if (sync == Sync.SYLLABLE) return 50000;
        if (sync == Sync.WORD) return 40000;
        if (sync == Sync.LINE) return 30000;
        if (sync == Sync.STATIC) return 20000;
        return 15000;
    }

    /**
     * Provider capability inside a timing band. SpicyLyrics.org carries the richest payload when
     * it is packed; a plain Spicy fetch, Spotify's native text and LRCLIB all sit below it, and an
     * unrecognised provider sits below those.
     */
    private static int sourceBonus(Source source, boolean spicyPackedPayload) {
        if (source == Source.SPICY) return spicyPackedPayload ? 5000 : 4000;
        if (source == Source.NATIVE) return 3000;
        if (source == Source.LRCLIB) return 2000;
        return 1000;
    }

    /**
     * Simplified-over-Traditional tiebreak, read from the lyrics the document actually carries.
     *
     * <p>Only the first few lines are inspected: a script preference is a whole-document property,
     * so a bounded sample answers it without walking a long song, and the lyrics are the only
     * honest source for it - a provider's name says nothing about which script it returned.
     */
    private static int scriptBonus(LyricsDocument doc) {
        if (doc == null || doc.lines == null || doc.lines.isEmpty()) return 250;
        StringBuilder sample = new StringBuilder();
        int seen = 0;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.text == null || line.text.isEmpty()) continue;
            sample.append(line.text).append('\n');
            if (++seen >= SCRIPT_SAMPLE_LINES) break;
        }
        if (sample.length() == 0) return 250;
        String text = sample.toString();
        if (!ChineseScriptVariants.available()) return 250;

        boolean traditional = ChineseScriptVariants.hasTraditional(text);
        boolean simplified = ChineseScriptVariants.hasSimplified(text);
        // Simplified text wins; Traditional loses to it but still beats text with no Han at all,
        // so a Chinese source is not pushed below a non-Chinese one by accident. Mixed text is
        // treated as neutral rather than guessed at.
        if (simplified && !traditional) return 500;
        if (traditional && !simplified) return 0;
        return 250;
    }

    private static final int SCRIPT_SAMPLE_LINES = 8;

    /**
     * Auto-ranking comparison, kept deliberately simple: sync level wins first (syllable >
     * word > line > static/none), ties break by source (SpicyLyrics.org > Spotify > LRCLIB >
     * unknown). Poisoned candidates never win. Source order mode follows fetch position.
     */
    public static boolean preferAuto(LyricsDocument candidate, LyricsDocument currentBest) {
        if (candidate == null || candidate.spicyPoisoned) return false;
        if (currentBest == null || currentBest.spicyPoisoned) return true;
        int left = syncLevel(candidate.type);
        int right = syncLevel(currentBest.type);
        if (left != right) return left > right;
        return sourceTier(candidate) > sourceTier(currentBest);
    }

    /** Source tiebreak tier: Apple Music (any fetcher) > Spotify > LRCLIB > unknown. */
    static int sourceTier(LyricsDocument doc) {
        if (doc == null) return -1;
        String hay = (LyricUtils.safe(doc.fetchSource) + " " + LyricUtils.safe(doc.provider))
                .toLowerCase(java.util.Locale.US);
        if (hay.contains("apple") || hay.contains("spicy") || hay.contains("aml")
                || hay.contains("lenerd")) {
            return 3;
        }
        if (hay.contains("spotify") || hay.contains("native") || hay.contains("musixmatch")) {
            return 2;
        }
        if (hay.contains("lrclib")) return 1;
        return 0;
    }

    /** Sync-level rank: syllable (3) > word (2) > line (1) > static (0) > unknown (-1). */
    public static int syncLevel(String type) {
        if ("Syllable".equalsIgnoreCase(type)) return 3;
        if ("Word".equalsIgnoreCase(type)) return 2;
        if ("Line".equalsIgnoreCase(type)) return 1;
        if ("Static".equalsIgnoreCase(type)) return 0;
        return -1;
    }

    private static int lineSanityBonus(LyricsDocument doc) {
        if (doc.lines == null || doc.lines.isEmpty()) return -1000;
        return Math.min(doc.lines.size(), 200);
    }

    private static int timingHealthAdjustment(LyricsDocument doc) {
        if (doc.lines == null || doc.lines.isEmpty() || "Static".equalsIgnoreCase(doc.type)) return 0;
        int invalid = 0;
        long previous = Long.MIN_VALUE;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.startMs < 0 || line.endMs < line.startMs) invalid++;
            if (line != null && line.startMs + 250 < previous) invalid++;
            if (line != null) previous = Math.max(previous, line.startMs);
        }
        return invalid == 0 ? 0 : -Math.min(5000, invalid * 1200);
    }

    private static Source sourceOf(String fetchSource, String provider) {
        String source = LyricUtils.safe(fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("lrclib")) return Source.LRCLIB;
        if (source.contains("spotify_native") || source.contains("native spotify")) return Source.NATIVE;
        if (source.contains("spicy") || source.contains("apple") || source.contains("lenerd")
                || source.contains("amll")) return Source.SPICY;

        String providerLabel = LyricUtils.safe(provider).toLowerCase(java.util.Locale.US);
        if (providerLabel.contains("lrclib")) return Source.LRCLIB;
        if (providerLabel.contains("native spotify") || providerLabel.contains("musixmatch")) {
            return Source.NATIVE;
        }
        if (providerLabel.contains("spicy") || providerLabel.contains("apple") || providerLabel.contains("lenerd")
                || providerLabel.contains("amll")) return Source.SPICY;
        return Source.UNKNOWN;
    }

    private static Sync syncOf(String type) {
        if ("Syllable".equalsIgnoreCase(type)) return Sync.SYLLABLE;
        if ("Word".equalsIgnoreCase(type)) return Sync.WORD;
        if ("Line".equalsIgnoreCase(type)) return Sync.LINE;
        if ("Static".equalsIgnoreCase(type)) return Sync.STATIC;
        return Sync.UNKNOWN;
    }

    private enum Source {
        SPICY,
        NATIVE,
        LRCLIB,
        UNKNOWN
    }

    private enum Sync {
        SYLLABLE,
        WORD,
        LINE,
        STATIC,
        UNKNOWN
    }
}
