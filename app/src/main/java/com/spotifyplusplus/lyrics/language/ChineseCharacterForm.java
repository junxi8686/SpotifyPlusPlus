package com.spotifyplusplus.lyrics.language;

/**
 * Which script the displayed lyrics should be shown in.
 *
 * <p>Two values, not three. {@link #ORIGINAL} shows exactly what the source provided, which is the
 * default and the only state in which this feature does nothing at all. {@link #SIMPLIFIED} converts
 * a displayed document to Simplified Chinese.
 *
 * <p>A "Traditional" direction is deliberately absent rather than offered and broken. Converting
 * towards Traditional needs OpenCC's Simplified-to-Traditional phrase dictionary, which is about
 * 1.05 MB of the 1.13 MB bundle - thirty times the size of the direction that ships - and without it
 * the conversion would fall back to per-character substitution, which gets phrase-sensitive words
 * wrong: {@code 头发} would come out as {@code 頭发} rather than {@code 頭髮}. An option that
 * silently mangles a word is worse than an option that does not exist yet, so this direction is a
 * follow-up decision (bundle the phrase table, or leave it out) rather than a value here.
 */
public enum ChineseCharacterForm {
    /** Show the source text unchanged. The default; nothing is converted and nothing is loaded. */
    ORIGINAL,
    /** Display a Traditional or mixed document in Simplified Chinese. */
    SIMPLIFIED
}
