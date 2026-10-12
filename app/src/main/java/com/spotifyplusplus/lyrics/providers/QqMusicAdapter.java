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
    /**
     * Minimum interval between ANY two QQ searches, whichever path asks - a variant of the walk, a
     * later round, an explicit check from the picker.
     *
     * <p>This is the pacing the owner asked for, and it is the piece that was missing. The variant
     * gap below spaces one walk; it does not stop a second walk - a new round, a manual check -
     * starting immediately after the first. The in-flight guard stops two requests being in the air
     * at once, which is a different thing again: three <em>sequential</em> requests in one second
     * were always allowed.
     *
     * <p>1500 ms is chosen against the observed tolerance: the owner's log has refusals arriving in
     * bursts of three within a millisecond and then the same query answered correctly about five
     * seconds later, so the source tolerates roughly one request per second at worst and recovers
     * within seconds. Pacing slightly above that keeps the walk inside what QQ accepts instead of
     * paying for a burst with silence.
     *
     * <p>A request that arrives inside the interval is <em>skipped</em>, not queued: that source is
     * failed for the round with {@code QQ Music paced; next slot in Nms} and the round carries on
     * without it. Skipping is the deliberate choice, not a gap to close. Waiting for the slot would
     * hold up the whole round - the other five sources racing in it included - on one source's pacing,
     * which is the opposite of what a race is for; the next round asks again once the slot has passed,
     * so the only cost is one round of latency on that one source.
     */
    static final long MIN_REQUEST_INTERVAL_MS = 1500L;

    /**
     * Gap between spellings of one walk.
     *
     * <p>The plan holds up to {@link SearchQueryVariants#MAX_QUERIES} spellings, and they used to be
     * sent as fast as they could be built - the log has three of them inside 186 ms. At 450 ms three
     * spellings still landed inside about a second, which is exactly the "hit too many times within
     * one second" the owner describes, and it happens on the normal path: a first spelling that
     * answers {@code code=0} with no acceptable hit moves straight to the second and third, with
     * nothing wrong anywhere.
     *
     * <p>Two seconds between spellings, so a three-spelling walk takes four seconds rather than one.
     * Slower to exhaust the plan, and far cheaper than the throttle that exhausting it caused.
     */
    private static final long VARIANT_DELAY_MS = 2000L;

    /**
     * How many spellings one automatic attempt will try.
     *
     * <p>A cap, because the plan can hold twelve and each one is a separate search: an attempt that
     * finds nothing ranked well would spend twelve requests keeping the source busy for half a minute.
     * Three is the depth the walk actually reached in the log that motivated the spacing (three
     * spellings inside 186 ms), and the walk stops earlier anyway whenever a spelling produces a hit
     * that {@link QqSongRanker} considers settled - the common case for a track that exists in QQ's
     * catalogue. Each request now logs its {@code variant=i/N}, so the owner's next session shows
     * whether the third spelling ever produces the accepted hit; if it does not, this drops to two.
     */
    static final int MAX_SPELLINGS_PER_ATTEMPT = 3;

    /**
     * When the next QQ search may go out; see {@link #MIN_REQUEST_INTERVAL_MS}.
     *
     * <p>There is deliberately no cooldown beside it. A refusal ends the attempt and leaves the
     * source alone until this slot passes, which at 1500 ms is a few seconds at the very worst and
     * never the 30-second silence the owner objected to. Pacing is the fix; silence was the wrong
     * answer, and a source that is being refused has to stay available for the next round.
     */
    private static long nextAllowedRequestMs;

    /**
     * A distinct identifier per outbound search, so a request and its response can be counted
     * independently of any decision trace.
     *
     * <p>Asked for by name after the owner could not tell twenty-five log lines from twenty-five
     * requests, or from a handful printed repeatedly. Process-wide rather than per adapter: two
     * adapters for two rounds would otherwise both print {@code id=1}.
     */
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

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
        // No candidate carried in yet: the whole plan is walked and scored, and the best hit
        // across all of its spellings is what gets fetched.
        searchVariant(context, track, searchTrack, substituted, generation, karaokeOriginalLyrics,
                callback, queries, 0,
                Collections.<QqSongRanker.Candidate>emptyList());
    }

    private void searchVariant(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                               boolean substituted, int generation, boolean karaokeOriginalLyrics,
                               LyricsRepository.ResultCallback callback,
                               List<String> queries, int index,
                               List<QqSongRanker.Candidate> carried) {
        if (queries == null || index >= queries.size() || index >= MAX_SPELLINGS_PER_ATTEMPT) {
            // The best hit found anywhere in the plan is the answer; only a plan that produced
            // no acceptable hit at all reports nothing. Reaching the cap without a settled hit ends
            // the walk the same way - the remaining spellings are not worth three more requests.
            if (carried != null && !carried.isEmpty()) {
                fetchLyric(context, track, queryTrack, substituted, generation, carried, callback);
                return;
            }
            fail(context, track, callback, "QQ Music empty");
            return;
        }
        String query = queries.get(index);
        // The rate limiter, across every path that asks this source. A request that arrives inside
        // the pacing window is not sent and not waited for: QQ is skipped for this round and counted
        // as not answered, so the other five sources and the round's completion are not held up by
        // one source's pacing. The next round asks again once the slot has passed.
        long waitMs = waitForRequestSlot(System.currentTimeMillis());
        if (waitMs > 0L) {
            trace("qq paced skipped wait=" + waitMs + "ms variant=" + (index + 1)
                    + " q=\"" + query + "\"");
            fail(context, track, callback,
                    "QQ Music paced; next slot in " + waitMs + "ms");
            return;
        }
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
        final long requestId = REQUEST_SEQUENCE.incrementAndGet();
        final long startedAtMs = android.os.SystemClock.elapsedRealtime();
        final int variant = index;
        final int variants = queries.size();
        trace("qq request id=" + requestId + " variant=" + (variant + 1) + "/" + variants
                + " q=\"" + query + "\"");
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                trace("qq response id=" + requestId + " outcome=transport-error ms="
                        + elapsed(startedAtMs) + " detail=" + safe(e.getMessage()));
                if (carried != null && !carried.isEmpty()) {
                    fetchLyric(context, track, queryTrack, substituted, generation, carried, callback);
                    return;
                }
                fail(context, track, callback, "QQ Music search failed: " + safe(e.getMessage()));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        trace("qq response id=" + requestId + " outcome=http-" + response.code()
                                + " ms=" + elapsed(startedAtMs));
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
                    // As on NetEase: what came back, and what cleared the gate. code is included
                    // because this endpoint answers 200 with an empty list while throttling, which
                    // is otherwise indistinguishable from the catalogue not carrying the track.
                    trace("qq response id=" + requestId + " code=" + searchCode
                            + " hits=" + (hits == null ? 0 : hits.size())
                            + " accepted=" + songs.size() + " ms=" + elapsed(startedAtMs));
                    if (searchCode != 0) {
                        // A refusal is not a missing result, so it ends this attempt without trying
                        // the remaining spellings - they would be refused too. No cooldown is
                        // recorded: the pacing slot is the only bound, so the source is available
                        // again for the next round as soon as the minimum interval has passed, and a
                        // persistent refusal is visible in the log rather than hidden behind silence.
                        trace("qq refused code=" + searchCode + " variant=" + (variant + 1)
                                + " q=\"" + query + "\"");
                        fail(context, track, callback,
                                "QQ Music refused the search (code " + searchCode + ")");
                        return;
                    }
                    if (songs.isEmpty()) {
                        // A zero code means the endpoint read the query and has nothing acceptable,
                        // which repeating the identical query cannot change - so the plan moves on
                        // to the next spelling, or reports the miss.
                        if (index + 1 < queries.size()) {
                            // Next spelling, after a gap. The delay is dropped only when there is no
                            // scheduler, which is the synchronous test path.
                            final int nextIndex = index + 1;
                            if (scheduler != null) {
                                scheduler.schedule(
                                        () -> searchVariant(context, track, queryTrack, substituted,
                                                generation, karaokeOriginalLyrics, callback,
                                                queries, nextIndex, carried),
                                        VARIANT_DELAY_MS, TimeUnit.MILLISECONDS);
                            } else {
                                searchVariant(context, track, queryTrack, substituted, generation,
                                        karaokeOriginalLyrics, callback, queries,
                                        nextIndex, carried);
                            }
                            return;
                        }
                        if (carried != null && !carried.isEmpty()) {
                            fetchLyric(context, track, queryTrack, substituted, generation,
                                    carried, callback);
                            return;
                        }
                        fail(context, track, callback, "QQ Music empty");
                        return;
                    }
                    List<QqSongRanker.Candidate> best = betterOf(carried, songs);
                    if (settled(best) || index + 1 >= queries.size()) {
                        fetchLyric(context, track, queryTrack, substituted, generation, best,
                                callback);
                        return;
                    }
                    searchVariant(context, track, queryTrack, substituted, generation,
                            karaokeOriginalLyrics, callback, queries,
                            index + 1, best);
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

    /** A search line, written through the same guard as every other trace in this path. */
    private static void trace(String line) {
        try {
            com.spotifyplusplus.xposed.XpLog.log("SpotifyPlusSpicy " + line);
        } catch (Throwable ignored) {
            // A trace must never affect the request it describes.
        }
    }

    private static long elapsed(long startedAtMs) {
        return android.os.SystemClock.elapsedRealtime() - startedAtMs;
    }

    /**
     * Claims the next request slot: 0 when this request may go out now, otherwise how long it has to
     * wait. Claiming a slot starts the interval, so two callers cannot both be told to go.
     */
    static long waitForRequestSlot(long nowMs) {
        synchronized (QqMusicAdapter.class) {
            if (nowMs < nextAllowedRequestMs) return nextAllowedRequestMs - nowMs;
            nextAllowedRequestMs = nowMs + MIN_REQUEST_INTERVAL_MS;
            return 0L;
        }
    }

    /** Test seam: forget the pacing this process recorded. */
    static void resetPacing() {
        synchronized (QqMusicAdapter.class) {
            nextAllowedRequestMs = 0L;
        }
    }

    /** Test seam: the process-wide request id the next search will carry. */
    static long nextRequestId() {
        return REQUEST_SEQUENCE.get() + 1L;
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

    /** The better of two ranked lists, compared the way the ranker orders them. */
    static List<QqSongRanker.Candidate> betterOf(
            List<QqSongRanker.Candidate> carried, List<QqSongRanker.Candidate> fresh) {
        if (fresh == null || fresh.isEmpty()) return carried;
        if (carried == null || carried.isEmpty()) return fresh;
        QqSongRanker.Candidate a = carried.get(0);
        QqSongRanker.Candidate b = fresh.get(0);
        return TrackMatchScorer.compareForRanking(
                a.score, a.supportsWordLyrics(), a.durationDiffMs,
                b.score, b.supportsWordLyrics(), b.durationDiffMs) <= 0 ? carried : fresh;
    }

    /**
     * Whether the walk can stop: an exact or near-exact title match, which no other spelling of the
     * same metadata is going to beat. Deliberately not merely-acceptable, which is what let a wrong
     * recording end the search.
     */
    static boolean settled(List<QqSongRanker.Candidate> carried) {
        if (carried == null || carried.isEmpty()) return false;
        TrackMatchScorer.Tier titleTier = carried.get(0).score.title;
        return titleTier == TrackMatchScorer.Tier.PERFECT
                || titleTier == TrackMatchScorer.Tier.VERY_HIGH;
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
