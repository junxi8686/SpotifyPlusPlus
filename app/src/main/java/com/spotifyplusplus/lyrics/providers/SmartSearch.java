package com.spotifyplusplus.lyrics.providers;

import android.content.Context;

import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.ai.AiTrackAliases;

import java.util.Collections;
import java.util.List;

/**
 * The query plan a search-based provider actually walks: local respellings, plus whatever a model
 * already resolved for this recording.
 *
 * <p>Two sources of widening, and they cost very different things. Respelling a title is free and
 * instant, so every query plan starts with it. Asking a model is neither, so it is only ever read
 * from a cache here — and a cache miss is what schedules the request for the next visit rather
 * than blocking this one.
 *
 * <p>The ordering that falls out of that is the one worth having: the reported spelling first, so
 * a track that resolves today is unchanged; the other script next, which is what fixes a
 * Traditional title against a Simplified index without anyone configuring anything; and the
 * model's names last, on the visit after the local plan came up empty.
 *
 * <p>Android-free apart from the context passed through to the alias cache: safe for unit tests.
 */
public final class SmartSearch {

    private SmartSearch() {
    }

    /** Full query plan for a track: title and artist, local spellings first, aliases last. */
    public static List<String> queries(Context context, SpotifyTrack track) {
        if (track == null) return Collections.emptyList();
        // A phrase the owner typed wins outright, for this track only. Every widening below starts
        // from the reported title, so a release catalogued under a genuinely different name is
        // unreachable by any of them - and the owner can see that name in the other app.
        String manual = ManualLyricsQuery.queryFor(track.uri);
        if (!manual.isEmpty()) {
            return Collections.singletonList(manual);
        }
        return SearchQueryVariants.queries(track, aliasesFor(context, track.title, track.artist));
    }

    /** Title-only plan for the endpoints that take a title and nothing else. */
    public static List<String> titleQueries(Context context, String title) {
        List<String> base = SearchQueryVariants.titleQueries(title);
        List<String> aliases = aliasesFor(context, title, "");
        if (aliases.isEmpty()) return base;
        return SearchQueryVariants.titleQueries(title, aliases);
    }

    /**
     * Aliases already known for this recording; schedules the model call when there are none.
     *
     * <p>Calling this is what makes the AI part free for every track that already resolves: the
     * provider only reaches here after its own queries missed, and a miss is exactly the moment a
     * model call can still change the outcome.
     */
    private static List<String> aliasesFor(Context context, String title, String artist) {
        // Empty by design: the plan is now purely the local spellings, as Lyricify's is.
        //
        // This is not only about leaving a model out of the search. The aliases used to be
        // offered ahead of the locally derived spellings while counting against the same
        // MAX_QUERIES budget, so a poor model answer spent four of the eight requests on names
        // no catalogue indexes and then truncated the bare-title query and the whole
        // titleVariants tail off the end - the widening that does work never ran.
        return Collections.emptyList();
    }
}
