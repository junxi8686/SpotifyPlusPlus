package com.spotifyplusplus.lyrics.providers;

import com.spotifyplusplus.SpotifyTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Widens a text search so a title written in the other Chinese script still finds its lyrics.
 *
 * <p>Spotify reports the release's own script. A Taiwanese or Hong Kong release comes back in
 * Traditional ("離開地球表面"), while NetEase, QQ Music and AMLL index the same song under the
 * Simplified title ("离开地球表面"). The track is not missing from those services — the query is
 * simply a different string, and every one of those search endpoints is a plain substring match
 * that returns nothing for it.
 *
 * <p>So the query plan asks more than once: the reported title first, exactly as before, then the
 * same title and artist converted to the other script, then the title with its version markers and
 * trailing parenthetical removed, and finally any names an AI pass supplied. The original query is
 * always first and always present, which keeps behaviour for every track that already resolved
 * byte-identical to before.
 *
 * <p>Android-free: safe for unit tests.
 */
public final class SearchQueryVariants {
    /**
     * Hard cap on queries per provider visit. Each one is a request to a third-party service, and
     * the tail of the plan is worth strictly less than the head: past a handful of spellings a miss
     * is a miss.
     */
    public static final int MAX_QUERIES = 8;

    /** Aliases spent per plan. One query each, and the model already ordered them by usefulness. */
    static final int MAX_ALIAS_QUERIES = 4;

    /** Trailing " (Remastered 2011)" / "【动画版】" style qualifier. */
    private static final String TRAILING_BRACKET =
            "\\s*[(\\[（【][^)\\]）】]*[)\\]）】]\\s*$";

    /** Trailing " - Live" / " – 拼接版" style qualifier after a spaced dash. */
    private static final String TRAILING_DASH = "\\s+[-\\u2013\\u2014]\\s+.+$";

    private SearchQueryVariants() {
    }

    /** Query plan for a track, without any AI-supplied names. */
    public static List<String> queries(SpotifyTrack track) {
        return queries(track, Collections.<String>emptyList());
    }

    /**
     * Ordered, de-duplicated query strings for a search-based provider.
     *
     * @param extraTitles additional names for the same recording (AI-resolved aliases), tried after
     *                    every locally derived spelling and never instead of them
     */
    public static List<String> queries(SpotifyTrack track, List<String> extraTitles) {
        if (track == null) return Collections.emptyList();
        String title = text(track.title);
        String artist = text(track.artist);
        if (title.isEmpty() && artist.isEmpty()) return Collections.emptyList();

        LinkedHashSet<String> out = new LinkedHashSet<>();

        // The reported spelling is unchanged and still first: a track that resolves today keeps
        // resolving with the same query it always used.
        // Simplified first, ahead of the reported spelling - but only on a first attempt.
        //
        // Both editions usually exist, the provider answers whichever spelling it is asked for, and
        // the search stops at the first hit, so the reported title alone decides which edition gets
        // read: a Traditional report seats a Traditional edition even when a Simplified one is
        // right there. Asked in this order, a Simplified edition is what comes back when one
        // exists, and the reported spelling still answers when it does not.
        //
        // Skipped on a retry: an alias set can only exist from the second visit onwards, which
        // means these spellings have already been asked and already missed.
        if (extraTitles == null || extraTitles.isEmpty()) {
            offer(out, join(ChineseScriptVariants.toSimplified(title),
                    ChineseScriptVariants.toSimplified(artist)));
        }

        offer(out, join(title, artist));

        // A model's names come next, and can only ever be present from the second visit onwards -
        // the cache is filled by the very miss that made this plan worth widening. That ordering
        // is the point: on a retry the local spellings are already known not to work, so spending
        // the first requests on them again would be asking the same question twice.
        //
        // Each alias is asked on its own rather than paired with the artist. A model-supplied name
        // is often the one the track is filed under abroad, where the Spotify artist string is not
        // part of the record at all; pairing them would search for a combination that exists in
        // neither catalogue. The ranker still scores every hit against the real artist.
        if (extraTitles != null) {
            int used = 0;
            for (String extra : extraTitles) {
                if (used >= MAX_ALIAS_QUERIES) break;
                String alias = text(extra);
                if (alias.isEmpty()) continue;
                offer(out, alias);
                used++;
            }
        }

        // The bare reported title comes next, ahead of every respelling. An artist credit the
        // provider does not index - a compilation, a collaboration worded differently, a name
        // written in the other script - makes *every* title+artist query miss, and the title alone
        // is the query that still works. Asking it before the variants costs nothing when the
        // first query already hit, and saves a round of doomed requests when it did not.
        offer(out, join(title, ""));

        offer(out, join(ChineseScriptVariants.toTraditional(title),
                ChineseScriptVariants.toTraditional(artist)));
        for (String variant : titleVariants(title)) {
            offer(out, join(variant, artist));
        }

        List<String> limited = new ArrayList<>(out);
        return limited.size() <= MAX_QUERIES
                ? Collections.unmodifiableList(limited)
                : Collections.unmodifiableList(new ArrayList<>(limited.subList(0, MAX_QUERIES)));
    }

    /** Title spellings worth asking for, most specific first; the raw title is always first. */
    static List<String> titleVariants(String title) {
        List<String> out = new ArrayList<>(4);
        addUnique(out, title);
        addUnique(out, LrclibQueryPlanner.normalizedTitle(title));
        addUnique(out, withoutTrailingBracket(title));
        addUnique(out, withoutTrailingDash(title));
        return out;
    }

    /**
     * Title with a trailing {@code " - qualifier"} removed, or the input when that would empty it.
     *
     * <p>Unconditional where {@link LrclibQueryPlanner#normalizedTitle} strips only known
     * remaster/remix markers, because the qualifier is frequently local to one release: Spotify
     * reports "反乌托邦 - 拼接版" while LRCLIB, AMLL and NetEase all index the bare "反乌托邦",
     * and no marker list would ever contain "拼接版".
     *
     * <p>Nothing is replaced by this - it is one more query in the plan - so an over-eager strip
     * costs a request, never a wrong answer.
     */
    static String withoutTrailingDash(String title) {
        String value = text(title);
        if (value.isEmpty()) return value;
        String stripped = value.replaceFirst(TRAILING_DASH, "").trim();
        return stripped.isEmpty() ? value : stripped;
    }

    /**
     * Title-only query plan for the endpoints that take a title and nothing else (AMLL, LRCLIB).
     *
     * <p>Same widening as {@link #queries}, minus the artist: each spelling is offered in the
     * reported script first, then in the other one.
     */
    public static List<String> titleQueries(String title) {
        return titleQueries(title, Collections.<String>emptyList());
    }

    /** Title-only plan, with names an AI pass resolved for the same recording appended last. */
    public static List<String> titleQueries(String title, List<String> extraTitles) {
        List<String> out = new ArrayList<>(4);
        addUnique(out, text(title));
        if (extraTitles != null) {
            int used = 0;
            for (String extra : extraTitles) {
                if (used >= MAX_ALIAS_QUERIES) break;
                String alias = text(extra);
                if (alias.isEmpty()) continue;
                addUnique(out, alias);
                used++;
            }
        }
        for (String base : titleVariants(title)) {
            addUnique(out, base);
            addUnique(out, ChineseScriptVariants.toSimplified(base));
            addUnique(out, ChineseScriptVariants.toTraditional(base));
        }
        return out.size() <= MAX_QUERIES
                ? Collections.unmodifiableList(out)
                : Collections.unmodifiableList(new ArrayList<>(out.subList(0, MAX_QUERIES)));
    }

    /** Title with one trailing parenthetical removed, or the input when that would empty it. */
    static String withoutTrailingBracket(String title) {
        String value = text(title);
        if (value.isEmpty()) return value;
        String stripped = value.replaceFirst(TRAILING_BRACKET, "").trim();
        return stripped.isEmpty() ? value : stripped;
    }

    private static void offer(LinkedHashSet<String> out, String query) {
        String value = text(query);
        if (value.isEmpty()) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(value)) return;
        }
        out.add(value);
    }

    private static String join(String title, String artist) {
        String left = text(title);
        String right = text(artist);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + " " + right;
    }

    private static void addUnique(List<String> out, String value) {
        String v = text(value);
        if (v.isEmpty()) return;
        for (String existing : out) {
            if (existing.equals(v)) return;
        }
        out.add(v);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /** Key for a per-track variant cache: the reported metadata, not the URI (URIs can repeat). */
    public static String cacheKey(String title, String artist) {
        return text(title).toLowerCase(Locale.ROOT) + "\u0000" + text(artist).toLowerCase(Locale.ROOT);
    }
}
