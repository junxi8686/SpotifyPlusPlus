package com.spotifyplusplus.lyrics.providers;

/**
 * A phrase the owner typed, used instead of the track's own metadata for one track.
 *
 * <p>Every other widening in this package starts from what Spotify reported and tries to respell
 * it. That covers script, punctuation and version qualifiers, and nothing else: when a release is
 * catalogued under a genuinely different name - "拼接乌托邦" against Spotify's "反乌托邦 - 拼接版" -
 * no transform of the reported title reaches it, and the owner is looking at a song they can see
 * in the other app.
 *
 * <p>So the last resort is the owner's own words. The phrase is armed against one track id and
 * expires with it, which is what keeps it a one-track answer rather than a sticky global override
 * that silently rewrites every later search.
 *
 * <p>Android-free: safe for unit tests.
 */
public final class ManualLyricsQuery {
    private static volatile String phrase = "";
    private static volatile String trackKey = "";
    private static volatile String exactSource = "";
    private static volatile String exactItem = "";

    private ManualLyricsQuery() {
    }

    /**
     * Names one provider result to use outright, skipping search, ranking and any model call.
     *
     * <p>This is what the owner's own choice means: they picked a row out of a list of what the
     * provider actually returned, so there is nothing left to decide. Every automatic path exists
     * to answer "is this the same recording?"; that question has already been answered by the only
     * party who can answer it.
     */
    public static void armExact(String sourceId, String itemId, String trackKey) {
        String item = itemId == null ? "" : itemId.trim();
        exactSource = item.isEmpty() ? "" : (sourceId == null ? "" : sourceId);
        exactItem = item;
        phrase = "";
        ManualLyricsQuery.trackKey = item.isEmpty() || trackKey == null ? "" : trackKey;
    }

    /** The chosen provider item id when it belongs to {@code sourceId} and {@code trackKey}. */
    public static String exactFor(String sourceId, String trackKey) {
        String item = exactItem;
        if (item.isEmpty() || sourceId == null || trackKey == null) return "";
        if (!sourceId.equals(exactSource) || !trackKey.equals(ManualLyricsQuery.trackKey)) return "";
        return item;
    }

    /** True when a row the owner picked is waiting to be fetched. */
    public static boolean exactArmed() {
        return !exactItem.isEmpty();
    }

    /** Arms {@code query} for the track identified by {@code trackKey}, replacing any earlier one. */
    public static void arm(String query, String trackKey) {
        String value = query == null ? "" : query.trim();
        ManualLyricsQuery.phrase = value;
        ManualLyricsQuery.trackKey = value.isEmpty() || trackKey == null ? "" : trackKey;
    }

    /** The armed phrase when it belongs to {@code trackKey}, otherwise empty. Never consumes. */
    public static String queryFor(String trackKey) {
        String armed = phrase;
        if (armed.isEmpty() || trackKey == null || !trackKey.equals(ManualLyricsQuery.trackKey)) {
            return "";
        }
        return armed;
    }

    /** The armed phrase regardless of track, for tests and diagnostics. */
    public static String armed() {
        return phrase;
    }

    /** Disarms. Called when the owner clears the field, and when a search succeeds. */
    public static void clear() {
        phrase = "";
        trackKey = "";
        exactSource = "";
        exactItem = "";
    }
}
