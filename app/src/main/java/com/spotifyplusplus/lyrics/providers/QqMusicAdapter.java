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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;

import static com.spotifyplusplus.lyrics.LyricUtils.safe;

/**
 * QQ Music fallback adapter: title search, ranked hits, word-level QRC first, line-level LRC
 * fallback. Karaoke rewriting applies here (search-based) and nowhere on exact-ID paths: a
 * rewritten search stores {@code KARAOKE_SUBSTITUTION}, a verbatim one {@code STRONG_SEARCH}.
 *
 * <p>Not in the default fallback order yet: Slice 6/7 wire explicit "check other sources" and
 * refresh paths onto this. Every terminal outcome persists a candidate or a source state.
 */
public final class QqMusicAdapter {
    public static final int ADAPTER_REVISION = 1;
    private static final int SEARCH_RETRY_LIMIT = 2;
    private static final long SEARCH_RETRY_DELAY_MS = 500;

    /**
     * A realistic browser UA, not the bare {@code "Mozilla/5.0"} this used to send on every call.
     *
     * <p>That stub is what made this source look broken: the same search answered {@code code=2001}
     * with no results under {@code "Mozilla/5.0"} and returned the right three hits under a browser
     * UA, one after the other. It was read as a login requirement at first, and as intermittent
     * throttling after that; it is neither - it is the header.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final okhttp3.OkHttpClient http;
    private final LyricsRepository.Parser parser;
    private final ScheduledExecutorService scheduler;

    public QqMusicAdapter(okhttp3.OkHttpClient http, LyricsRepository.Parser parser,
                          ScheduledExecutorService scheduler) {
        this.http = http;
        this.parser = parser;
        this.scheduler = scheduler;
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      LyricsRepository.ResultCallback callback) {
        fetch(context, track, generation, false, callback);
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      boolean karaokeOriginalLyrics,
                      LyricsRepository.ResultCallback callback) {
        fetch(context, track, generation, karaokeOriginalLyrics, callback, 0);
    }

    private void fetch(Context context, SpotifyTrack track, int generation,
                       boolean karaokeOriginalLyrics,
                       LyricsRepository.ResultCallback callback, int retryCount) {
        SpotifyTrack searchTrack = KaraokeTitles.forLyricsSearch(track, karaokeOriginalLyrics);
        boolean substituted = searchTrack != track;
        // A row the owner picked out of QQ's own result list: fetch exactly that, skipping search,
        // ranking and the arbiter entirely.
        String chosen = ManualLyricsQuery.exactFor("qq", track == null ? null : track.uri);
        if (!chosen.isEmpty()) {
            fetchLineLyric(context, track, searchTrack, substituted, generation, chosen, callback);
            return;
        }
        // QQ Music indexes Simplified titles, and the reported title may not be one.
        List<String> queries = SmartSearch.queries(context, searchTrack);
        searchVariant(context, track, searchTrack, substituted, generation, karaokeOriginalLyrics,
                callback, retryCount, queries, 0);
    }

    private void searchVariant(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                               boolean substituted, int generation, boolean karaokeOriginalLyrics,
                               LyricsRepository.ResultCallback callback, int retryCount,
                               List<String> queries, int index) {
        if (queries == null || index >= queries.size()) {
            fail(context, track, callback, "QQ Music empty");
            return;
        }
        String query = queries.get(index);
        String data = "{\"music.search.SearchCgiService\":{\"method\":\"DoSearchForQQMusicDesktop\","
                + "\"module\":\"music.search.SearchCgiService\","
                + "\"param\":{\"num_per_page\":\"20\",\"page_num\":\"1\","
                + "\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"search_type\":\"0\"}}}";
        Request request = new Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(RequestBody.create(data, MediaType.parse("application/json")))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fail(context, track, callback, "QQ Music search failed: " + safe(e.getMessage()));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "QQ Music search HTTP " + response.code());
                        return;
                    }
                    String body = response.body().string();
                    int searchCode = searchServiceCode(body);
                    JsonArray hits = searchCode == 0 ? songsArray(body) : null;
                    String title = queryTrack == null ? null : queryTrack.title;
                    String artist = queryTrack == null ? null : queryTrack.artist;
                    String album = queryTrack == null ? null : queryTrack.album;
                    long duration = queryTrack == null ? 0L : queryTrack.duration;
                    List<QqSongRanker.Candidate> songs =
                            QqSongRanker.rank(hits, title, artist, album, duration);
                    if (songs.isEmpty()) {
                        if (searchCode == 0) {
                            // Nothing cleared the identity gate. Before moving on, let the model
                            // look at what the search did return: a hit that is plainly the same
                            // recording written differently is a decision a threshold cannot make.
                            List<QqSongRanker.Candidate> near = QqSongRanker.insufficient(
                                    hits, title, artist, album, duration,
                                    AiLyricCandidateArbiter.MAX_CANDIDATES);
                            int picked = arbitrate(context, queryTrack, near);
                            if (picked >= 0) {
                                fetchLyric(context, track, queryTrack, substituted, generation,
                                        Collections.singletonList(near.get(picked)), callback);
                                return;
                            }
                        }
                        // Retry an empty result whatever the reported code.
                        //
                        // This endpoint answers 200 with an empty list when it throttles, which is
                        // indistinguishable from "this catalogue does not carry the track" - and
                        // the owner sees a source that reads "not found" on one tap and resolves on
                        // the next. Retrying only on a non-zero code is what left the second tap
                        // doing the work. A query that genuinely has no hits pays two extra
                        // requests, which is the cheaper mistake by a wide margin.
                        if (retryCount < SEARCH_RETRY_LIMIT && scheduler != null) {
                            int nextRetry = retryCount + 1;
                            scheduler.schedule(
                                    () -> fetch(context, track, generation,
                                            karaokeOriginalLyrics, callback, nextRetry),
                                    SEARCH_RETRY_DELAY_MS * (nextRetry + 1L),
                                    TimeUnit.MILLISECONDS);
                            return;
                        }
                        if (index + 1 < queries.size()) {
                            searchVariant(context, track, queryTrack, substituted, generation,
                                    karaokeOriginalLyrics, callback, retryCount, queries,
                                    index + 1);
                            return;
                        }
                        fail(context, track, callback, "QQ Music empty"
                                + (searchCode != 0 ? " (code " + searchCode + ")" : "")
                                + NeteaseAdapter.aiNote(context));
                        return;
                    }
                    fetchLyric(context, track, queryTrack, substituted, generation, songs,
                            callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "QQ Music search parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void fetchLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                            boolean substituted, int generation,
                            List<QqSongRanker.Candidate> songs,
                            LyricsRepository.ResultCallback callback) {
        List<Long> wordIds = new ArrayList<>();
        for (QqSongRanker.Candidate candidate : songs) {
            if (!candidate.supportsWordLyrics()) continue;
            if (!wordIds.contains(candidate.id)) wordIds.add(candidate.id);
            if (wordIds.size() >= QqSongRanker.MAX_WORD_LYRIC_ATTEMPTS) break;
        }
        String lineMid = songs.get(0).mid;
        Runnable lineFallback =
                () -> fetchLineLyric(context, track, queryTrack, substituted, generation, lineMid,
                        callback);
        if (wordIds.isEmpty()) {
            lineFallback.run();
            return;
        }
        tryWordChain(context, track, queryTrack, substituted, generation, wordIds, 0, callback,
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
        okhttp3.FormBody form = new okhttp3.FormBody.Builder()
                .add("version", "15")
                .add("miniversion", "82")
                .add("lrctype", "4")
                .add("musicid", String.valueOf(songId))
                .build();
        Request request = new Request.Builder()
                .url("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg")
                .post(form)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://c.y.qq.com/")
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
                    LyricsDocument doc = parser.parseQqWordLyrics(context, queryTrack, raw);
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

    private void fetchLineLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                                boolean substituted, int generation, String songMid,
                                LyricsRepository.ResultCallback callback) {
        String callbackName = "MusicJsonCallback_lrc";
        long pcachetime = System.currentTimeMillis();
        String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                + "?callback=" + callbackName
                + "&pcachetime=" + pcachetime
                + "&songmid=" + Uri.encode(songMid)
                + "&g_tk=5381&jsonpCallback=" + callbackName
                + "&loginUin=0&hostUin=0&format=jsonp&inCharset=utf8&outCharset=utf8"
                + "&notice=0&platform=yqq&needNewCode=0";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fail(context, track, callback, "QQ Music lyric failed: " + safe(e.getMessage()));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "QQ Music lyric HTTP " + response.code());
                        return;
                    }
                    String raw = response.body().string();
                    if (raw.startsWith(callbackName + "(")) {
                        raw = raw.substring(callbackName.length() + 1);
                        if (raw.endsWith(")")) raw = raw.substring(0, raw.length() - 1);
                    }
                    LyricsDocument doc = parser.parseQqMusicLyrics(context, queryTrack, raw);
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        fail(context, track, callback, "QQ Music empty");
                        return;
                    }
                    succeed(context, track, doc, substituted, songMid, raw, callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "QQ Music lyric parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void succeed(Context context, SpotifyTrack track, LyricsDocument doc,
                         boolean substituted, String providerItemId, String raw,
                         LyricsRepository.ResultCallback callback) {
        doc.selectedSource = "QQ Music";
        doc.selectionMode = "strict";
        doc.selectionOverride = "QQ Music";
        CatalogSource.MatchMethod method = substituted
                ? CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION
                : CatalogSource.MatchMethod.STRONG_SEARCH;
        CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.QQ, track, doc, method,
                providerItemId, raw, ADAPTER_REVISION);
        callback.onSuccess(doc);
    }

    private void fail(Context context, SpotifyTrack track,
                      LyricsRepository.ResultCallback callback, String error) {
        CatalogAdapters.recordError(context, CatalogSource.SourceId.QQ, track, error);
        callback.onError(error);
    }

    static int searchServiceCode(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return 0;
            JsonObject service = Json.optObject(root.getAsJsonObject(),
                    "music.search.SearchCgiService");
            return service == null ? 0 : (int) Json.optDouble(service, 0d, "code");
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<QqSongRanker.Candidate> rankSongs(String body, SpotifyTrack track) {
        return QqSongRanker.rank(songsArray(body),
                track == null ? null : track.title,
                track == null ? null : track.artist,
                track == null ? null : track.album,
                track == null ? 0 : track.duration);
    }

    /** The {@code ...service.data.body.song.list} array of a QQ search response, or null. */
    static JsonArray songsArray(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject service = Json.optObject(root.getAsJsonObject(),
                    "music.search.SearchCgiService");
            JsonObject data = service == null ? null : Json.optObject(service, "data");
            JsonObject bodyObj = data == null ? null : Json.optObject(data, "body");
            JsonObject songObj = bodyObj == null ? null : Json.optObject(bodyObj, "song");
            return songObj == null ? null : Json.optArray(songObj, "list");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Asks the model which of the refused hits is the playing recording.
     *
     * <p>Runs on the HTTP callback thread, which is already off the main thread. Returns -1 for
     * every failure: an unanswerable question must not become a user-visible error.
     */
    private static int arbitrate(Context context, SpotifyTrack queryTrack,
                                 List<QqSongRanker.Candidate> near) {
        if (near == null || near.isEmpty()) return -1;
        List<AiLyricCandidateArbiter.Candidate> rows = new ArrayList<>();
        for (QqSongRanker.Candidate candidate : near) {
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
