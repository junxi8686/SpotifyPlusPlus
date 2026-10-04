package com.spotifyplusplus.lyrics.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Shared source-selection preferences used by fullscreen and now-playing lyrics. */
public final class LyricsSourcePreferences {
    public enum Source {
        APPLE_MUSIC("apple"), SPICY("spicy"), SPOTIFY("spotify"), AMLL("amll"), LRCLIB("lrclib"),
        QQ("qq"), NETEASE("netease");
        public final String id;
        Source(String id) { this.id = id; }
        public static Source parse(String value) {
            if (value == null) return null;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (Source source : values()) if (source.id.equals(v) || source.name().toLowerCase(Locale.ROOT).equals(v)) return source;
            if ("apple_music".equals(v) || "aml".equals(v) || "lenerd".equals(v)) return APPLE_MUSIC;
            return null;
        }
        @Override public String toString() { return id; }
    }

    public enum RankingMode {
        AUTO("auto"), SOURCE_ORDER("order");
        public final String id;
        RankingMode(String id) { this.id = id; }
        public static RankingMode parse(String value) {
            if (value == null) return AUTO;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (RankingMode mode : values()) if (mode.id.equals(v) || mode.name().toLowerCase(Locale.ROOT).equals(v)) return mode;
            if ("auto".equals(v)) return AUTO;
            if ("source order".equals(v)) return SOURCE_ORDER;
            // Legacy three-way ranking collapsed to Auto: both old automatic modes
            // prioritized content over position, so they migrate to AUTO.
            if ("smart ranking".equals(v) || "smart_ranking".equals(v) || "smart".equals(v)) return AUTO;
            if ("sync type".equals(v) || "sync_type".equals(v) || "sync".equals(v)) return AUTO;
            return AUTO;
        }
    }

    private static final String PREFS = "SpotifyPlusLyricsSourceSelection";
    private static final String MODE = "ranking_mode";
    private static final String ORDER = "source_order";
    private static final String ENABLED_PREFIX = "source_enabled_";
    private static final String OVERRIDE_PREFIX = "override_";
    private static final String OVERRIDE_ORDER = "override_order";
    private static final int MAX_OVERRIDES = 200;
    private static final List<Source> DEFAULT_ORDER = Collections.unmodifiableList(
            java.util.Arrays.asList(Source.APPLE_MUSIC, Source.SPICY, Source.SPOTIFY, Source.AMLL,
                    Source.LRCLIB, Source.QQ, Source.NETEASE));

    private LyricsSourcePreferences() {}

    public static RankingMode rankingMode(Context context) {
        if (context == null) return RankingMode.AUTO;
        return RankingMode.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "auto"));
    }

    public static void setRankingMode(Context context, RankingMode mode) {
        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE, (mode == null ? RankingMode.AUTO : mode).id).apply();
    }

    public static List<Source> sourceOrder(Context context) {
        if (context == null) return DEFAULT_ORDER;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ORDER, "");
        return parseOrder(raw);
    }

    public static void setSourceOrder(Context context, List<Source> order) {
        if (context == null) return;
        List<Source> normalized = normalizeOrder(order);
        StringBuilder out = new StringBuilder();
        for (Source source : normalized) { if (out.length() > 0) out.append(','); out.append(source.id); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ORDER, out.toString()).apply();
    }

    public static boolean sourceEnabled(Context context, Source source) {
        if (source == null || source == Source.SPICY) return false;
        if (context == null) return enabledByDefault(source);
        // Deliberately NOT forced off here when the blanking setting is on.
        //
        // Reading that setting into this answer made the source switch impossible to turn on:
        // the tap set it, Save persisted it, the next render asked this method again, and this
        // line handed back false - so the switch snapped shut every time. It also dropped
        // Spotify out of the enabled order, which is why the check-all list never named it.
        //
        // The two controls are independent by design. Emptying Spotify's lyrics already makes it
        // useless as a source, and that consequence is visible on its own; a switch that refuses
        // to move is not.
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_PREFIX + source.id, enabledByDefault(source));
    }

    /** Whether Spotify's own lyrics are excluded from resolution entirely. */
    public static boolean ignoresSpotifyLyrics(Context context) {
        if (context == null) return false;
        try {
            return Boolean.TRUE.equals(
                    com.spotifyplusplus.SpotifyPlusConfig.from(context)
                            .get(com.spotifyplusplus.Settings.IGNORE_SPOTIFY_LYRICS));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Network search sources are opt-in; established ID-based sources retain their defaults. */
    public static boolean enabledByDefault(Source source) {
        return source != null && source != Source.SPICY
                && source != Source.QQ && source != Source.NETEASE;
    }

    /**
     * The opt-in search sources that are currently off, in the order worth mentioning.
     *
     * <p>Exists so a "no lyrics found" screen can say why a track might still be findable: these
     * two are the only sources that stay off unless the owner turns them on, and for a Chinese
     * release they are frequently the only ones that carry it at all. Without this the screen
     * reports a dead end for a track that is one settings toggle away from working.
     */
    public static List<Source> disabledOptInSources(Context context) {
        List<Source> out = new ArrayList<>();
        for (Source source : new Source[]{Source.NETEASE, Source.QQ}) {
            if (!sourceEnabled(context, source)) out.add(source);
        }
        return Collections.unmodifiableList(out);
    }

    public static void setSourceEnabled(Context context, Source source, boolean enabled) {
        if (context == null || source == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED_PREFIX + source.id, enabled).apply();
    }

    public static List<Source> enabledSourceOrder(Context context) {
        List<Source> result = new ArrayList<>();
        for (Source source : sourceOrder(context)) if (sourceEnabled(context, source)) result.add(source);
        return Collections.unmodifiableList(result);
    }

    /** Returns null for Auto (no track-specific override). */
    public static Source trackOverride(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return null;
        Source source = Source.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(OVERRIDE_PREFIX + Digests.sha256(trackId), null));
        return source;
    }

    /** Selecting null/Auto removes the override. Overrides are bounded to the 200 most recent tracks. */
    public static void setTrackOverride(Context context, String trackId, Source source) {
        if (context == null || trackId == null || trackId.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = OVERRIDE_PREFIX + Digests.sha256(trackId);
        synchronized (LyricsSourcePreferences.class) {
            SharedPreferences.Editor editor = prefs.edit();
            if (source == null) editor.remove(key); else editor.putString(key, source.id);
            LinkedHashMap<String, Long> recency = parseRecency(prefs.getString(OVERRIDE_ORDER, ""));
            recency.remove(key);
            if (source != null) recency.put(key, System.currentTimeMillis());
            while (recency.size() > MAX_OVERRIDES) {
                String eldest = recency.keySet().iterator().next();
                recency.remove(eldest);
                editor.remove(eldest);
            }
            StringBuilder order = new StringBuilder();
            for (String k : recency.keySet()) { if (order.length() > 0) order.append(','); order.append(k); }
            editor.putString(OVERRIDE_ORDER, order.toString()).apply();
        }
    }

    /** String convenience overload used by settings/UI adapters; "auto" clears the override. */
    public static void setTrackOverride(Context context, String trackId, String source) {
        setTrackOverride(context, trackId, Source.parse(source));
    }

    public static List<Source> defaultOrder() { return DEFAULT_ORDER; }

    private static List<Source> parseOrder(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_ORDER;
        List<Source> result = new ArrayList<>();
        for (String part : raw.split(",")) { Source source = Source.parse(part); if (source != null && !result.contains(source)) result.add(source); }
        return result.isEmpty() ? DEFAULT_ORDER : normalizeOrder(result);
    }
    private static List<Source> normalizeOrder(List<Source> order) {
        List<Source> result = new ArrayList<>();
        if (order != null) for (Source source : order) if (source != null && !result.contains(source)) result.add(source);
        for (Source source : DEFAULT_ORDER) if (!result.contains(source)) result.add(source);
        return Collections.unmodifiableList(result);
    }
    private static LinkedHashMap<String, Long> parseRecency(String raw) {
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        if (raw != null) for (String row : raw.split(",")) { if (!row.startsWith(OVERRIDE_PREFIX)) continue; map.put(row, 0L); }
        return map;
    }
}
