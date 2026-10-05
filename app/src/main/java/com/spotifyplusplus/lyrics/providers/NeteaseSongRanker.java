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

    // Script preference is read from ChineseScriptVariants.traditionalConfidence in the
    // comparator, not here: TrackMatchScorer.compareName folds the script before comparing,
    // so both editions score identically and nothing downstream of it can prefer one.

    /**
     * The best hit a query returned for a target, with the tiers that decided it, logged when the
     * gate refused every hit.
     *
     * <p>Written because "twenty hits, none accepted" cannot be acted on: the tier that is out of
     * reach could be the title, the artist, the album or the runtime, and each has a different fix.
     */
    private static void traceRefusal(JsonArray list, TrackMatchScorer.Target target,
                                     String trackTitle, String trackArtist, String trackAlbum,
                                     long trackDurationMs) {
        try {
            if (list == null || list.size() == 0) return;
            TrackMatchScorer.Score best = null;
            String bestName = "";
            String bestArtist = "";
            long bestDuration = 0L;
            for (JsonElement element : list) {
                Candidate candidate = evaluate(element, target, false);
                if (candidate == null) continue;
                if (best == null || candidate.score.total > best.total) {
                    best = candidate.score;
                    bestName = candidate.title;
                    bestArtist = candidate.artists.isEmpty() ? "" : candidate.artists.get(0);
                    bestDuration = candidate.durationMs;
                }
            }
            if (best == null) return;
            com.spotifyplusplus.xposed.XpLog.log("SpotifyPlusSpicy netease refusal"
                    + " want=\"" + trackTitle + "\"/\"" + trackArtist + "\"/" + trackDurationMs
                    + " got=\"" + bestName + "\"/\"" + bestArtist + "\"/" + bestDuration
                    + " tiers title=" + best.title + " artist=" + best.artist
                    + " album=" + best.album + " dur=" + best.duration
                    + " total=" + Math.round(best.total * 10) / 10d
                    + " need=" + TrackMatchScorer.ACCEPT_SCORE
                    + " accepted=" + best.accepted());
        } catch (Throwable traceFailure) {
            // A trace must never affect the request it describes.
        }
    }

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
        if (accepted.isEmpty()) {
            // Nothing cleared the gate. Re-evaluate the closest hit without the gate and say
            // which tier refused it: twenty hits accepted none, and only the score knows which
            // of the four comparisons is the one out of reach.
            traceRefusal(songs, target, trackTitle, trackArtist, trackAlbum, trackDurationMs);
        }
        Collections.sort(accepted, (a, b) -> {
            // A Simplified edition wins when the match quality is otherwise equal - see
            // simplifiedEdition for why this cannot live in the scorer.
            // Ordered by how much of the title is Traditional, not by whether any of it is.
            // Two editions that both contain Traditional characters are still ordered against
            // each other, which a boolean could not do.
            double aTraditional = ChineseScriptVariants.traditionalConfidence(a.title);
            double bTraditional = ChineseScriptVariants.traditionalConfidence(b.title);
            if (aTraditional != bTraditional) return aTraditional < bTraditional ? -1 : 1;
            return TrackMatchScorer.compareForRanking(
                    a.score, a.supportsWordLyrics(), a.durationDiffMs,
                    b.score, b.supportsWordLyrics(), b.durationDiffMs);
        });
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
        // A different recording, not a different pressing. Refusing it here - rather than only
        // ranking it lower - is what makes a source whose every hit is the wrong length report
        // that it does not have the track, instead of the best of those hits being fetched and
        // read. Ordering alone cannot do this: it only chooses between candidates that are
        // already acceptable.
        long durationDiffMs = TrackMatchScorer.durationDiff(target.durationMs, durationMs);
        if (requireAccepted && durationDiffMs >= TrackMatchScorer.LENGTH_REJECT_MS) return null;
        if (requireAccepted && !score.accepted()) return null;
        return new Candidate(id, name, artists, score, durationMs, durationDiffMs);
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
