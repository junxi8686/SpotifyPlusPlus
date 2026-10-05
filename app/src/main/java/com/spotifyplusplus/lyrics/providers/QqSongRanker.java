package com.spotifyplusplus.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotifyplusplus.lyrics.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.spotifyplusplus.lyrics.LyricUtils.isBlank;

/**
 * Picks which QQ Music search hits to pull lyrics from, in preference order.
 *
 * <p>Grading and ordering live in {@link TrackMatchScorer} (a port of Lyricify-Lyrics-Helper's
 * matching); this class only knows QQ's JSON shape and which of its hits can be asked for
 * word-level lyrics.
 */
public final class QqSongRanker {

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
                if (element == null || !element.isJsonObject()) continue;
                Candidate candidate = evaluate(element.getAsJsonObject(), target, false);
                if (candidate == null) continue;
                if (best == null || candidate.score.total > best.total) {
                    best = candidate.score;
                    bestName = candidate.title;
                    bestArtist = candidate.artists.isEmpty() ? "" : candidate.artists.get(0);
                    bestDuration = candidate.durationMs;
                }
            }
            if (best == null) return;
            com.spotifyplusplus.xposed.XpLog.log("SpotifyPlusSpicy qq refusal"
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

    private QqSongRanker() {
    }

    public static final class Candidate {
        public final String mid;
        public final long id;
        public final String title;
        /** Credited names as the provider listed them, lowercased; shown to the arbiter. */
        public final List<String> artists;
        public final TrackMatchScorer.Score score;
        public final long durationMs;
        /** Absolute runtime difference, or -1 when either side's runtime is unknown. */
        public final long durationDiffMs;

        Candidate(String mid, long id, String title, List<String> artists,
                  TrackMatchScorer.Score score, long durationMs, long durationDiffMs) {
            this.mid = mid;
            this.id = id;
            this.title = title;
            this.artists = artists == null ? Collections.<String>emptyList() : artists;
            this.score = score;
            this.durationMs = durationMs;
            this.durationDiffMs = durationDiffMs;
        }

        /** True when word-level ("QRC") lyrics can be requested at all for this hit. */
        public boolean supportsWordLyrics() {
            return id > 0;
        }

        @Override
        public String toString() {
            return "QqCandidate{" + mid + ",id=" + id
                    + ",score=" + Math.round(score.total * 10) / 10d
                    + "," + score.title + ",artist=" + score.artist + ",dt=" + durationDiffMs + "}";
        }
    }

    /**
     * Ranks every acceptable hit in the search response, best first.
     *
     * <p>Sub-entries under a hit's {@code grp} ("same-version tracks") array are flattened in
     * alongside their parent. QQ groups a song's other pressings there - the album cut, the single,
     * a regional release - and word-level lyrics are attached per entry, so the grouped siblings are
     * frequently the only ones that have any. Reading only the top-level list is why tracks that do
     * have karaoke timing kept coming back line-synced.
     *
     * @return an ordered, possibly empty list; never null.
     */
    public static List<Candidate> rank(JsonArray list, String trackTitle, String trackArtist,
                                       String trackAlbum, long trackDurationMs) {
        if (list == null || list.size() == 0) return Collections.emptyList();
        TrackMatchScorer.Target target =
                new TrackMatchScorer.Target(trackTitle, trackArtist, trackAlbum, trackDurationMs);
        List<Candidate> accepted = new ArrayList<>();
        for (JsonElement element : list) {
            collect(element, accepted, target, true, true);
        }
        if (accepted.isEmpty()) {
            // Nothing cleared the gate. Re-evaluate the closest hit without the gate and say
            // which tier refused it: twenty hits accepted none, and only the score knows which
            // of the four comparisons is the one out of reach.
            traceRefusal(list, target, trackTitle, trackArtist, trackAlbum, trackDurationMs);
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

    private static void collect(JsonElement element, List<Candidate> out,
                                TrackMatchScorer.Target target, boolean descend,
                                boolean requireAccepted) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject song = element.getAsJsonObject();
        Candidate candidate = evaluate(song, target, requireAccepted);
        if (candidate != null) out.add(candidate);
        if (!descend) return;
        JsonArray group = Json.optArray(song, "grp", "group");
        if (group == null) return;
        for (JsonElement sub : group) {
            // One level only: QQ never nests groups inside groups, and descending blindly would
            // risk looping on a malformed response.
            collect(sub, out, target, false, requireAccepted);
        }
    }

    private static Candidate evaluate(JsonObject song, TrackMatchScorer.Target target,
                                      boolean requireAccepted) {
        String mid = Json.optString(song, "mid", "songmid", "songMid");
        if (isBlank(mid)) return null;

        String name = firstNonBlank(Json.optString(song, "name"), Json.optString(song, "title"));
        JsonObject albumObject = Json.optObject(song, "album");
        String albumName = albumObject == null ? "" : firstNonBlank(
                Json.optString(albumObject, "name"), Json.optString(albumObject, "title"));
        long durationMs = (long) (Json.optDouble(song, 0d, "interval", "duration") * 1000d);

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
        return new Candidate(mid, numericSongId(song), name, artists, score, durationMs,
                durationDiffMs);
    }

    /**
     * Hits the identity gate refused, closest first, for a model to arbitrate.
     *
     * <p>The gate has no third answer: a hit is either accepted, or the provider is reported as not
     * having the track at all. That is right for a threshold and wrong for a hit whose title differs
     * from the playing one by a separator, a script or a version qualifier - a judgement a model can
     * make and a similarity score cannot.
     *
     * @param limit how many of the closest misses to hand over
     */
    public static List<Candidate> insufficient(JsonArray list, String trackTitle,
                                               String trackArtist, String trackAlbum,
                                               long trackDurationMs, int limit) {
        if (list == null || list.size() == 0 || limit <= 0) return Collections.emptyList();
        TrackMatchScorer.Target target =
                new TrackMatchScorer.Target(trackTitle, trackArtist, trackAlbum, trackDurationMs);
        List<Candidate> near = new ArrayList<>();
        for (JsonElement element : list) {
            List<Candidate> all = new ArrayList<>();
            collect(element, all, target, true, false);
            for (Candidate candidate : all) {
                if (!candidate.score.accepted()) near.add(candidate);
            }
        }
        Collections.sort(near, (a, b) -> Double.compare(b.score.total, a.score.total));
        return near.size() <= limit ? near : new ArrayList<>(near.subList(0, limit));
    }

    /** Best hit only, or null when nothing in the response is acceptable. */
    public static Candidate best(JsonArray list, String trackTitle, String trackArtist,
                                 String trackAlbum, long trackDurationMs) {
        List<Candidate> ranked = rank(list, trackTitle, trackArtist, trackAlbum, trackDurationMs);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * QQ spells the word-level lyric id differently depending on which search surface answered
     * ("id" on the desktop search cgi, "songid"/"numid"/"musicid" elsewhere). Reading only "id"
     * meant those responses looked like they had no word-level lyrics at all, and the track was
     * quietly fetched line-synced.
     */
    static long numericSongId(JsonObject song) {
        double value = Json.optDouble(song, -1d, "id", "songid", "songId", "numid", "musicid", "song_id");
        if (value > 0) return (long) value;
        JsonObject nested = Json.optObject(song, "track_info", "trackInfo", "songInfo");
        if (nested != null) {
            double inner = Json.optDouble(nested, -1d, "id", "songid", "songId", "numid", "musicid");
            if (inner > 0) return (long) inner;
        }
        return -1;
    }

    /** Credited names from a QQ hit, lowercased. */
    static List<String> artistNames(JsonObject song) {
        List<String> names = new ArrayList<>();
        JsonArray singers = Json.optArray(song, "singer", "singers", "artist");
        if (singers == null) return names;
        for (JsonElement element : singers) {
            if (element == null || !element.isJsonObject()) continue;
            String name = Json.optString(element.getAsJsonObject(), "name", "title")
                    .toLowerCase(Locale.ROOT).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }
}
