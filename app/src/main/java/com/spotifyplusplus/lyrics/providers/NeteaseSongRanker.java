package com.spotifyplusplus.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotifyplusplus.lyrics.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Picks which NetEase Cloud Music search hits to pull lyrics from, in preference order.
 *
 * <p>Same job and same grading as {@link QqSongRanker} - both defer to {@link TrackMatchScorer} -
 * but NetEase's search JSON is a different shape: songs sit under {@code result.songs}, runtimes
 * are already milliseconds rather than seconds, credited artists are {@code artists} rather than
 * {@code singer}, and there is no grouped-alternates array to flatten.
 *
 * <p>What NetEase had before this was duration-closeness plus a flat artist penalty and <em>no
 * title comparison at all</em>, which is about as weak as matching gets: any song of roughly the
 * right length by roughly the right artist won. Every hit now has to clear the same identity gate
 * QQ's does.
 */
public final class NeteaseSongRanker {

    /** How many distinct hits the caller should be willing to try word-level lyrics on. */
    public static final int MAX_WORD_LYRIC_ATTEMPTS = 3;

    private NeteaseSongRanker() {
    }

    public static final class Candidate {
        public final long id;
        public final String title;
        /** Credited names as the provider listed them, lowercased; shown to the arbiter. */
        public final List<String> artists;
        public final TrackMatchScorer.Score score;
        public final long durationMs;
        /** Absolute runtime difference, or -1 when either side's runtime is unknown. */
        public final long durationDiffMs;

        Candidate(long id, String title, List<String> artists, TrackMatchScorer.Score score,
                  long durationMs, long durationDiffMs) {
            this.id = id;
            this.title = title;
            this.artists = artists == null ? Collections.<String>emptyList() : artists;
            this.score = score;
            this.durationMs = durationMs;
            this.durationDiffMs = durationDiffMs;
        }

        /**
         * Every NetEase hit is addressed by the same numeric id for both the line-level and the
         * word-level ("YRC") endpoint, so unlike QQ there is no per-hit word-level signal to rank
         * on - whether a track has YRC is only discoverable by asking. The caller therefore walks
         * the ranked list until one answers with word-level content.
         */
        public boolean supportsWordLyrics() {
            return id > 0;
        }

        @Override
        public String toString() {
            return "NeteaseCandidate{id=" + id
                    + ",score=" + Math.round(score.total * 10) / 10d
                    + "," + score.title + ",artist=" + score.artist + ",dt=" + durationDiffMs + "}";
        }
    }

    /**
     * Ranks every acceptable hit in a {@code result.songs} array, best first.
     *
     * @return an ordered, possibly empty list; never null.
     */
    public static List<Candidate> rank(JsonArray songs, String trackTitle, String trackArtist,
                                       String trackAlbum, long trackDurationMs) {
        if (songs == null || songs.size() == 0) return Collections.emptyList();
        TrackMatchScorer.Target target =
                new TrackMatchScorer.Target(trackTitle, trackArtist, trackAlbum, trackDurationMs);
        List<Candidate> accepted = new ArrayList<>();
        for (JsonElement element : songs) {
            Candidate candidate = evaluate(element, target);
            if (candidate != null) accepted.add(candidate);
        }
        Collections.sort(accepted, (a, b) -> TrackMatchScorer.compareForRanking(
                a.score, a.supportsWordLyrics(), a.durationDiffMs,
                b.score, b.supportsWordLyrics(), b.durationDiffMs));
        return accepted;
    }

    /** Best hit only, or null when nothing in the response is acceptable. */
    public static Candidate best(JsonArray songs, String trackTitle, String trackArtist,
                                 String trackAlbum, long trackDurationMs) {
        List<Candidate> ranked = rank(songs, trackTitle, trackArtist, trackAlbum, trackDurationMs);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * Hits the identity gate refused, closest first, for a model to arbitrate.
     *
     * <p>The gate has no third answer: a hit is either accepted, or the provider is reported as not
     * having the track at all. That is right for a threshold and wrong for the case this exists
     * for - "反乌托邦·拼接版" against "反乌托邦 - 拼接版" is one separator apart, and telling it
     * from "拼接乌托邦" is a judgement a model can make and a similarity score cannot.
     *
     * @param limit how many of the closest misses to hand over
     */
    public static List<Candidate> insufficient(JsonArray songs, String trackTitle,
                                               String trackArtist, String trackAlbum,
                                               long trackDurationMs, int limit) {
        if (songs == null || songs.size() == 0 || limit <= 0) return Collections.emptyList();
        TrackMatchScorer.Target target =
                new TrackMatchScorer.Target(trackTitle, trackArtist, trackAlbum, trackDurationMs);
        List<Candidate> near = new ArrayList<>();
        for (JsonElement element : songs) {
            Candidate candidate = evaluate(element, target, false);
            if (candidate != null && !candidate.score.accepted()) near.add(candidate);
        }
        Collections.sort(near, (a, b) -> Double.compare(b.score.total, a.score.total));
        return near.size() <= limit ? near : new ArrayList<>(near.subList(0, limit));
    }

    private static Candidate evaluate(JsonElement element, TrackMatchScorer.Target target) {
        return evaluate(element, target, true);
    }

    private static Candidate evaluate(JsonElement element, TrackMatchScorer.Target target,
                                      boolean requireAccepted) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject song = element.getAsJsonObject();
        long id = (long) Json.optDouble(song, -1d, "id", "songId");
        if (id <= 0) return null;

        String name = Json.optString(song, "name", "title");
        JsonObject album = Json.optObject(song, "album", "al");
        String albumName = album == null ? "" : Json.optString(album, "name", "title");
        // Already milliseconds here, unlike QQ's seconds. "dt" is the newer cloudsearch spelling.
        long durationMs = (long) Json.optDouble(song, 0d, "duration", "dt");

        List<String> artists = artistNames(song);
        TrackMatchScorer.Score score = TrackMatchScorer.score(
                target, name, artists, albumName, durationMs);
        if (requireAccepted && !score.accepted()) return null;
        return new Candidate(id, name, artists, score, durationMs,
                TrackMatchScorer.durationDiff(target.durationMs, durationMs));
    }

    /** Credited names from a NetEase hit, lowercased. "ar" is the newer cloudsearch spelling. */
    static List<String> artistNames(JsonObject song) {
        List<String> names = new ArrayList<>();
        JsonArray artists = Json.optArray(song, "artists", "ar", "artist");
        if (artists == null) return names;
        for (JsonElement element : artists) {
            if (element == null || !element.isJsonObject()) continue;
            String name = Json.optString(element.getAsJsonObject(), "name")
                    .toLowerCase(Locale.ROOT).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }
}
