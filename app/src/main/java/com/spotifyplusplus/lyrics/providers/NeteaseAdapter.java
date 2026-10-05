package com.spotifyplusplus.lyrics.providers;

import android.content.Context;
import android.net.Uri;

import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.Json;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.ai.AiLyricCandidateArbiter;
import com.spotifyplusplus.lyrics.catalog.CatalogAdapters;
import com.spotifyplusplus.lyrics.catalog.CatalogSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

import static com.spotifyplusplus.lyrics.LyricUtils.safe;

/**
 * NetEase Cloud Music fallback adapter: title search, ranked hits, word-level YRC over the
 * signed eapi transport first, plain line-level LRC fallback. Karaoke rewriting applies here
 * (search-based) and nowhere on exact-ID paths: a rewritten search stores
 * {@code KARAOKE_SUBSTITUTION}, a verbatim one {@code STRONG_SEARCH}.
 *
 * <p>Not in the default fallback order yet: Slice 6/7 wire explicit "check other sources" and
 * refresh paths onto this. Every terminal outcome persists a candidate or a source state.
 */
public final class NeteaseAdapter {
    public static final int ADAPTER_REVISION = 1;

    /**
     * A realistic browser UA, not the bare {@code "Mozilla/5.0"} this used to send.
     *
     * <p>NetEase answers a recognisable client normally and a stub UA with an empty result set
     * often enough that "no results" was indistinguishable from "the request was refused"; the
     * endpoint returns the right song for the right query with a real UA on every surface tested.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** The precise endpoint: exact matches, few hits. */
    private static final String SEARCH_URL =
            "https://music.163.com/api/search/get?s=%s&type=1&offset=0&limit=20";
    /**
     * The looser endpoint, tried when the precise one reports nothing.
     *
     * <p>Measured against the same query, {@code search/get} returned 2 hits and this returned 20 -
     * including the one that mattered. It is noisier, which the ranker already handles, and it is
     * only ever asked after the precise endpoint has already missed.
     */
    private static final String SEARCH_FALLBACK_URL =
            "https://music.163.com/api/search/get/web?s=%s&type=1&offset=0&limit=20";

    private final okhttp3.OkHttpClient http;
    private final LyricsRepository.Parser parser;

    public NeteaseAdapter(okhttp3.OkHttpClient http, LyricsRepository.Parser parser) {
        this.http = http;
        this.parser = parser;
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      LyricsRepository.ResultCallback callback) {
        fetch(context, track, generation, false, callback);
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      boolean karaokeOriginalLyrics,
                      LyricsRepository.ResultCallback callback) {
        SpotifyTrack searchTrack = KaraokeTitles.forLyricsSearch(track, karaokeOriginalLyrics);
        boolean substituted = searchTrack != track;
        // A row the owner picked out of the provider's own result list. Nothing is left to decide,
        // so no query is built, no candidate is scored, and no model is asked.
        String chosen = ManualLyricsQuery.exactFor("netease", track == null ? null : track.uri);
        if (!chosen.isEmpty()) {
            fetchLyricById(context, track, searchTrack, substituted, generation, chosen, callback);
            return;
        }
        // NetEase indexes Simplified titles; the reported title may be Traditional. Ask the
        // reported spelling first and walk the remaining spellings only while nothing matches.
        List<String> queries = SmartSearch.queries(context, searchTrack);
        // No candidate carried in yet: every spelling of the plan is walked and scored, and the
        // best hit across all of them is what gets fetched.
        searchVariant(context, track, searchTrack, substituted, generation, queries, 0, false,
                Collections.<NeteaseSongRanker.Candidate>emptyList(), callback);
    }

    private void searchVariant(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                               boolean substituted, int generation, List<String> queries,
                               int index, boolean webEndpoint,
                               List<NeteaseSongRanker.Candidate> carried,
                               LyricsRepository.ResultCallback callback) {
        if (queries == null || index >= queries.size()) {
            // Every spelling has been asked. The best hit found anywhere in the plan is the
            // answer; only a plan that produced no acceptable hit at all reports nothing.
            if (carried != null && !carried.isEmpty()) {
                fetchLyric(context, track, queryTrack, substituted, generation, carried, callback);
                return;
            }
            fail(context, track, callback, "NetEase empty");
            return;
        }
        String query = queries.get(index);
        String url = String.format(webEndpoint ? SEARCH_FALLBACK_URL : SEARCH_URL, Uri.encode(query));
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // An earlier spelling may already have produced an acceptable hit; a failure on
                // this one is a reason to stop asking, not a reason to throw that hit away.
                if (carried != null && !carried.isEmpty()) {
                    fetchLyric(context, track, queryTrack, substituted, generation, carried, callback);
                    return;
                }
                fail(context, track, callback, "NetEase search failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "NetEase search HTTP " + response.code());
                        return;
                    }
                    // The body is one-shot: parse it once and hand the same array to both the
                    // ranking pass and the fallback that follows an empty one.
                    JsonArray hits = songsArray(response.body().string());
                    String title = queryTrack == null ? null : queryTrack.title;
                    String artist = queryTrack == null ? null : queryTrack.artist;
                    String album = queryTrack == null ? null : queryTrack.album;
                    long duration = queryTrack == null ? 0L : queryTrack.duration;
                    List<NeteaseSongRanker.Candidate> songs =
                            NeteaseSongRanker.rank(hits, title, artist, album, duration);
                    // What the search actually returned for this spelling, and how much of it
                    // survived the identity gate. The candidate trace lists only survivors, so
                    // without this a source that found nothing, one whose hits were all refused
                    // and one whose lyrics then failed all look the same: absent.
                    try {
                        com.spotifyplusplus.xposed.XpLog.log("SpotifyPlusSpicy " + "netease q=\"" + query + "\" web=" + webEndpoint
                                + " hits=" + (hits == null ? 0 : hits.size())
                                + " accepted=" + songs.size());
                    } catch (Throwable traceFailure) {
                        // A trace must never affect the request it describes.
                    }
                    if (songs.isEmpty()) {
                        // Either nothing came back, or everything that did was refused. Nothing
                        // is promoted over that refusal any more: the model that used to be asked
                        // to override it is gone, and a refusal that stands is the honest answer.
                        if (!webEndpoint) {
                            // The looser endpoint returns a much wider set for the same spelling,
                            // so it is worth one try before abandoning the spelling.
                            searchVariant(context, track, queryTrack, substituted, generation,
                                    queries, index, true, carried, callback);
                            return;
                        }
                        searchVariant(context, track, queryTrack, substituted, generation,
                                queries, index + 1, false, carried, callback);
                        return;
                    }
                    List<NeteaseSongRanker.Candidate> best = betterOf(carried, songs);
                    if (settled(best) || index + 1 >= queries.size()) {
                        fetchLyric(context, track, queryTrack, substituted, generation, best,
                                callback);
                        return;
                    }
                    searchVariant(context, track, queryTrack, substituted, generation,
                            queries, index + 1, false, best, callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "NetEase search parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void fetchLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                            boolean substituted, int generation,
                            List<NeteaseSongRanker.Candidate> songs,
                            LyricsRepository.ResultCallback callback) {
        List<Long> ids = new ArrayList<>();
        for (NeteaseSongRanker.Candidate candidate : songs) {
            if (!candidate.supportsWordLyrics()) continue;
            if (!ids.contains(candidate.id)) ids.add(candidate.id);
            if (ids.size() >= NeteaseSongRanker.MAX_WORD_LYRIC_ATTEMPTS) break;
        }
        String lineId = String.valueOf(songs.get(0).id);
        Runnable lineFallback = () -> fetchLyricById(context, track, queryTrack, substituted,
                generation, lineId, callback);
        if (ids.isEmpty()) {
            lineFallback.run();
            return;
        }
        tryWordChain(context, track, queryTrack, substituted, generation, ids, 0, callback,
                lineFallback);
    }

    private void tryWordChain(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                              boolean substituted, int generation, List<Long> ids, int index,
                              LyricsRepository.ResultCallback callback, Runnable lineFallback) {
        if (index >= ids.size()) {
            lineFallback.run();
            return;
        }
        fetchWordLyric(context, track, queryTrack, substituted, generation, ids.get(index),
                callback, () -> tryWordChain(context, track, queryTrack, substituted, generation,
                        ids, index + 1, callback, lineFallback));
    }

    private void fetchWordLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                                boolean substituted, int generation, long songId,
                                LyricsRepository.ResultCallback callback, Runnable fallback) {
        String apiPath = "/api/song/lyric/v1";
        String payload = "{\"id\":\"" + songId + "\",\"cp\":\"false\",\"lv\":\"0\",\"kv\":\"0\","
                + "\"tv\":\"0\",\"rv\":\"0\",\"yv\":\"0\",\"ytv\":\"0\",\"yrv\":\"0\","
                + "\"csrf_token\":\"\",\"header\":" + NeteaseEapi.headerJson() + "}";
        String params = NeteaseEapi.params(apiPath, payload);
        if (params == null) {
            fallback.run();
            return;
        }
        Request request = new Request.Builder()
                .url("https://interface3.music.163.com/eapi/song/lyric/v1")
                .post(new okhttp3.FormBody.Builder().add("params", params).build())
                .header("User-Agent", NeteaseEapi.USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", NeteaseEapi.cookieHeader())
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fallback.run();
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fallback.run();
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc =
                            parser.parseNeteaseWordLyrics(context, queryTrack, raw);
                    if (doc == null || doc.lines.isEmpty()) {
                        // The same response carries the line-level lyrics: this request asks for
                        // every level, so lrc, tlyric and yrc all come back together. Reading only
                        // the word level and then falling through sent the line-level case to the old
                        // music.163.com/api/song/lyric endpoint, which now answers with an empty body
                        // for most tracks - so a song NetEase does carry line lyrics for was reported
                        // as having none, and the whole source read as not found.
                        doc = parser.parseNeteaseLyrics(context, queryTrack, raw);
                    }
                    if (doc == null || doc.lines.isEmpty()) {
                        fallback.run();
                        return;
                    }
                    doc.generation = generation;
                    succeed(context, track, doc, substituted, String.valueOf(songId), raw,
                            callback);
                } catch (Throwable t) {
                    fallback.run();
                }
            }
        });
    }

    private void fetchLyricById(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                                boolean substituted, int generation, String songId,
                                LyricsRepository.ResultCallback callback) {
        // Last resort only. This endpoint answers with an empty body for most tracks now; the
        // EAPI response is the one that carries the lyrics, and it is read before this runs.
        String url = "https://music.163.com/api/song/lyric?id=" + Uri.encode(songId)
                + "&lv=1&kv=1&tv=1";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail(context, track, callback, "NetEase lyric failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "NetEase lyric HTTP " + response.code());
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc =
                            parser.parseNeteaseLyrics(context, queryTrack, raw);
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        fail(context, track, callback, "NetEase empty");
                        return;
                    }
                    succeed(context, track, doc, substituted, songId, raw, callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "NetEase parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void succeed(Context context, SpotifyTrack track, LyricsDocument doc,
                         boolean substituted, String providerItemId, String raw,
                         LyricsRepository.ResultCallback callback) {
        doc.selectedSource = "NetEase";
        doc.selectionMode = "strict";
        doc.selectionOverride = "NetEase";
        CatalogSource.MatchMethod method = substituted
                ? CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION
                : CatalogSource.MatchMethod.STRONG_SEARCH;
        CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.NETEASE, track, doc, method,
                providerItemId, raw, ADAPTER_REVISION);
        callback.onSuccess(doc);
    }

    private void fail(Context context, SpotifyTrack track,
                      LyricsRepository.ResultCallback callback, String error) {
        CatalogAdapters.recordError(context, CatalogSource.SourceId.NETEASE, track, error);
        callback.onError(error);
    }

    /**
     * The better of two ranked lists, comparing their best entries the way the ranker orders them.
     *
     * <p>This is what makes the plan score its spellings together rather than in turn. A spelling
     * that produced an adequate hit no longer ends the search, so a later spelling still has the
     * chance to produce a better one.
     */
    static List<NeteaseSongRanker.Candidate> betterOf(
            List<NeteaseSongRanker.Candidate> carried, List<NeteaseSongRanker.Candidate> fresh) {
        if (fresh == null || fresh.isEmpty()) return carried;
        if (carried == null || carried.isEmpty()) return fresh;
        NeteaseSongRanker.Candidate a = carried.get(0);
        NeteaseSongRanker.Candidate b = fresh.get(0);
        return TrackMatchScorer.compareForRanking(
                a.score, a.supportsWordLyrics(), a.durationDiffMs,
                b.score, b.supportsWordLyrics(), b.durationDiffMs) <= 0 ? carried : fresh;
    }

    /**
     * Whether the walk can stop: an exact or near-exact title match on the playing recording, which
     * no other spelling of the same metadata is going to beat.
     *
     * <p>Kept at VERY_HIGH rather than MEDIUM on purpose. Stopping only at a perfect match would
     * send every request the plan allows, and these are third-party endpoints that rate limit;
     * stopping at merely-acceptable is what let a wrong recording end the search.
     */
    static boolean settled(List<NeteaseSongRanker.Candidate> carried) {
        if (carried == null || carried.isEmpty()) return false;
        TrackMatchScorer.Tier titleTier = carried.get(0).score.title;
        return titleTier == TrackMatchScorer.Tier.PERFECT
                || titleTier == TrackMatchScorer.Tier.VERY_HIGH;
    }

    static List<NeteaseSongRanker.Candidate> rankSongs(String body, SpotifyTrack track) {
        return NeteaseSongRanker.rank(songsArray(body),
                track == null ? null : track.title,
                track == null ? null : track.artist,
                track == null ? null : track.album,
                track == null ? 0 : track.duration);
    }

    /** The {@code result.songs} array of a NetEase search response, or null. */
    static JsonArray songsArray(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject result = Json.optObject(root.getAsJsonObject(), "result");
            return result == null ? null : Json.optArray(result, "songs");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Why the provider came up empty, in one clause the reader can act on.
     */
    static String aiNote(Context context) {
        try {
            return com.spotifyplusplus.lyrics.ai.AiTextCall.note(context);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Asks the model which of the refused hits is the playing recording.
     *
     * <p>Runs on the HTTP callback thread, which is already off the main thread and already at the
     * end of this provider's own options. Blocks for at most {@code AiTextCall}'s deadline, and
     * returns -1 for every failure - an unanswerable question must not become a user-visible error.
     */
    private static int arbitrate(Context context, SpotifyTrack queryTrack,
                                 List<NeteaseSongRanker.Candidate> near) {
        if (near == null || near.isEmpty()) return -1;
        List<AiLyricCandidateArbiter.Candidate> rows = new ArrayList<>();
        for (NeteaseSongRanker.Candidate candidate : near) {
            rows.add(new AiLyricCandidateArbiter.Candidate(candidate.title,
                    String.join("/", candidate.artists), candidate.durationMs));
        }
        return AiLyricCandidateArbiter.pick(context,
                queryTrack == null ? "" : queryTrack.title,
                queryTrack == null ? "" : queryTrack.artist,
                queryTrack == null ? 0L : queryTrack.duration,
                rows);
    }
}
