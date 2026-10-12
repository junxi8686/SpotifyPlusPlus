package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.LyricUtils;

import java.util.Locale;

/**
 * Catalog vocabulary: source identities, timing levels, match methods, provider states, and
 * selection modes shared by acquisition, selection, and rendering.
 *
 * <p>Direct Musixmatch is deliberately absent: there is no Musixmatch source ID, adapter, setting,
 * or rank. Spotify native stays {@code spotify_native}; its credit text may still name Musixmatch
 * because that is information inside Spotify's payload, not a second route.
 */
public final class CatalogSource {
    private CatalogSource() {
    }

    /**
     * Provider identity. Declaration order here is not the automatic tie-break: a tie falls to the
     * owner's configured source order, and behind it to the fixed list in {@code CatalogResolver} -
     * SpicyLyrics.org, Spotify native, AMLL, LRCLIB, QQ, NetEase - which is v1.1's order with the
     * slot the retired Apple route held at the head taken by SpicyLyrics.org. {@code APPLE} stays a
     * constant for stored routes, pins and orders, and is on neither list.
     */
    public enum SourceId {
        SPICY_ORG("spicy_org"),
        APPLE("apple"),
        SPOTIFY_NATIVE("spotify_native"),
        AMLL("amll"),
        LRCLIB("lrclib"),
        QQ("qq"),
        NETEASE("netease");

        public final String id;

        SourceId(String id) {
            this.id = id;
        }

        public static SourceId parse(String value) {
            if (value == null) return null;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (SourceId source : values()) {
                if (source.id.equals(v) || source.name().toLowerCase(Locale.ROOT).equals(v)) {
                    return source;
                }
            }
            return null;
        }

        @Override public String toString() {
            return id;
        }
    }

    /**
     * Actual timing a candidate carries. Declaration order is the document-type order, syllable to
     * unsynced, and it is the automatic ranking for every document except one written in Han - see
     * {@link #comparisonRank(boolean)}.
     */
    public enum TimingLevel {
        SYLLABLE,
        WORD,
        LINE,
        UNSYNCED;

        public static TimingLevel fromDocumentType(String type) {
            if ("Syllable".equalsIgnoreCase(type)) return SYLLABLE;
            if ("Word".equalsIgnoreCase(type)) return WORD;
            if ("Line".equalsIgnoreCase(type)) return LINE;
            return UNSYNCED;
        }

        /** True for the two tiers whose fill advances span by span inside the line. */
        public boolean isInLine() {
            return this == SYLLABLE || this == WORD;
        }

        /**
         * The rank the automatic comparison orders this tier by, for a document whose own text is
         * either Han or not.
         *
         * <p>For a Han document the two in-line tiers are one rank: both name a fill that advances
         * one character at a time, which is what every provider here emits and what the picker's
         * Chinese label already calls 逐字. If {@code hanText} is false the declaration order stands,
         * because Latin word timing is genuinely finer than Latin syllable timing. The caller
         * supplies the script rather than this enum reading the document, which is what keeps the
         * decision where the document is.
         */
        public int comparisonRank(boolean hanText) {
            if (hanText && isInLine()) return SYLLABLE.ordinal();
            return ordinal();
        }

        /**
         * Whether a seat at this timing is good enough to stop searching.
         *
         * <p>The threshold is {@link #WORD}, not {@link #LINE}. SpicyLyrics.org is asked first and
         * holds the head of the provider tie-break list, so a track where it did not reach
         * {@link #SYLLABLE} has nothing better than {@link #WORD} behind it, and word-level spans
         * are near-equivalent to syllable-level for karaoke fill. Stopping at {@code LINE} instead
         * parked the majority of tracks on line timing and made the four-level ranking a three-level
         * one: a {@code LINE} seat sealed the search, so a {@code WORD} or {@code SYLLABLE} document
         * from another source was never requested. Devices showed the same song at different quality
         * purely because a different source happened to answer first.
         *
         * <p>{@link #LINE} and {@link #UNSYNCED} therefore keep the search open. They are still
         * ranked against each other and against better documents; they just do not end it.
         */
        public boolean isFinalQuality() {
            return this == SYLLABLE || this == WORD;
        }
    }

    /**
     * How a candidate was matched to the track.
     *
     * <p>The resolver reads this for two things and nothing else: a {@link #WEAK} match is refused
     * before scoring, and the ranking pays for an exact match on every source except Spotify's own,
     * which is handed the Spotify id and looks the track up by it. See
     * {@code CatalogResolver.matchPoints} and {@code CatalogResolver.exactMatchIsEvidence}.
     */
    public enum MatchMethod {
        EXACT_SPOTIFY_ID,
        EXACT_PROVIDER_MAPPING,
        KARAOKE_SUBSTITUTION,
        STRONG_SEARCH,
        /** Weak matches are rejected by the resolver, never stored as playable. */
        WEAK;
    }

    /**
     * Persisted per-track per-source state. {@code CHECKING} is intentionally absent: in-flight
     * work lives in memory only and never touches the database.
     */
    public enum ProviderStatus {
        NOT_CHECKED,
        AVAILABLE,
        NOT_FOUND,
        TRANSIENT_ERROR,
        REJECTED,
        NEEDS_REFRESH,
        DISABLED;

        public static ProviderStatus parse(String value) {
            if (value == null) return null;
            try {
                return ProviderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** Whether the track follows the automatic winner or a manual pick. */
    public enum SelectionMode {
        AUTO,
        MANUAL;

        public static SelectionMode parse(String value) {
            if (value == null) return AUTO;
            try {
                return SelectionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return AUTO;
            }
        }
    }

    /**
     * The catalog's only track identity: the bare Spotify track ID. Accepts a full URI
     * ({@code spotify:track:xxx}) or an already-bare ID; anything else (episodes, local files,
     * empty input) yields "" and the caller must skip the catalog for it.
     */
    public static String bareTrackId(String uriOrId) {
        if (uriOrId == null) return "";
        String input = uriOrId.trim();
        if (input.isEmpty()) return "";
        if (input.contains(":")) return LyricUtils.trackIdFromUri(input);
        if (input.contains(" ") || input.contains("/")) return "";
        return input;
    }

    /**
     * Maps a legacy {@code fetchSource} (plus provider credit when the source is ambiguous) to a
     * catalog source. The retired {@code spicy} route maps to Apple; a direct-Musixmatch value
     * maps to null so the caller falls back to Auto instead of storing a dead route.
     */
    public static SourceId inferSourceId(String fetchSource, String provider) {
        String v = fetchSource == null ? "" : fetchSource.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "unknown".equals(v)) {
            v = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        }
        // Parsers tag deliveries with a route suffix (apple_music_cache, amll_ttml,
        // spotify_native_model:tag, qq_music); the prefix names the source.
        if (v.startsWith("spicy_org")) return SourceId.SPICY_ORG;
        if (v.startsWith("amll")) return SourceId.AMLL;
        if (v.startsWith("apple") || v.equals("aml") || v.startsWith("lenerd")
                || v.startsWith("spicy")) {
            return SourceId.APPLE;
        }
        if (v.startsWith("spotify") || v.startsWith("native")) return SourceId.SPOTIFY_NATIVE;
        if (v.startsWith("lrclib")) return SourceId.LRCLIB;
        if (v.startsWith("qq")) return SourceId.QQ;
        if (v.startsWith("netease")) return SourceId.NETEASE;
        return null;
    }
}
